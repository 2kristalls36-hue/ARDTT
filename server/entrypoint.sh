#!/bin/bash
# Unified ARDTT stack: one netns, role-appropriate daemons.
# Isolated (default) keeps iptables / TUN / ip rules inside this container so a
# shared VPS keeps its other services. Host-net is ARDTT_NETWORK_MODE=hostnet.
set -euo pipefail

# shellcheck disable=SC1091
. /opt/ardtt/netns-guard.sh
ardtt_require_container_netns || exit 1

ROLE="${ARDTT_ROLE:-entry}"
CASCADE_ENABLED="${ARDTT_CASCADE_ENABLED:-0}"
TELEMETRY_LISTEN="${TELEMETRY_LISTEN:-${ARDTT_TELEMETRY_LISTEN:-0.0.0.0:9200}}"
TELEMETRY_PORT="${TELEMETRY_LISTEN##*:}"
SKIP_TELEMETRY="${ARDTT_SKIP_TELEMETRY:-0}"
DATA="${ARDTT_DATA:-/data}"

export ARDTT_DATA="$DATA"
export ARDTT_WDTT_CONFIG="${ARDTT_WDTT_CONFIG:-/data/wdtt}"
mkdir -p "$DATA" "$ARDTT_WDTT_CONFIG" "$DATA/warp" /var/run /var/log

if [ -z "${ARDTT_CASCADE_SECRET:-}" ] && [ -s "$DATA/cascade.secret" ]; then
  ARDTT_CASCADE_SECRET="$(tr -d '[:space:]' <"$DATA/cascade.secret")"
  export ARDTT_CASCADE_SECRET
fi
if [ -z "${TELEMETRY_UPLOAD_TOKEN:-}" ] && [ -s "$DATA/telemetry.token" ]; then
  TELEMETRY_UPLOAD_TOKEN="$(tr -d '[:space:]' <"$DATA/telemetry.token")"
  export TELEMETRY_UPLOAD_TOKEN
fi
if [ -z "${TELEMETRY_REVIEW_TOKEN:-}" ] && [ -s "$DATA/admin.token" ]; then
  TELEMETRY_REVIEW_TOKEN="$(tr -d '[:space:]' <"$DATA/admin.token")"
  export TELEMETRY_REVIEW_TOKEN
fi

if [ "$ROLE" = "exit" ]; then
  CASCADE_ENABLED=1
fi

echo "[ardtt] unified role=${ROLE} cascade=${CASCADE_ENABLED} telemetry=${TELEMETRY_LISTEN} skip_telemetry=${SKIP_TELEMETRY}"

if [ -w /proc/sys/net/ipv4/ip_forward ]; then
  echo 1 >/proc/sys/net/ipv4/ip_forward || true
fi

# Optional children (WARP egress, telemetry) restart on their own with backoff.
# Their failure must not stop Direct/Bypass: WARP registration needs Cloudflare,
# which may be unreachable from the VPS, and that used to crash-loop the whole
# container (and fail the deploy) although the tunnels were fine.
declare -A CHILD_CMD=()
declare -A CHILD_OPTIONAL=()
declare -A CHILD_NEXT_TRY=()
declare -A CHILD_BACKOFF=()
OPTIONAL_BACKOFF_MIN=10
OPTIONAL_BACKOFF_MAX=300

start_child() {
  local name="$1"
  shift
  echo "[ardtt] start ${name}"
  CHILD_CMD[$name]="$(printf '%q ' "$@")"
  "$@" &
  echo $! >"/var/run/ardtt-${name}.pid"
}

start_optional_child() {
  local name="$1"
  start_child "$@"
  CHILD_OPTIONAL[$name]=1
  CHILD_BACKOFF[$name]=$OPTIONAL_BACKOFF_MIN
}

restart_optional_child() {
  local name="$1" now backoff
  now="$(date +%s)"
  backoff="${CHILD_BACKOFF[$name]:-$OPTIONAL_BACKOFF_MIN}"
  if [ -z "${CHILD_NEXT_TRY[$name]:-}" ]; then
    CHILD_NEXT_TRY[$name]=$((now + backoff))
    echo "[ardtt] optional ${name} stopped — tunnels keep running; retry in ${backoff}s"
    return 0
  fi
  [ "$now" -ge "${CHILD_NEXT_TRY[$name]}" ] || return 0
  unset "CHILD_NEXT_TRY[$name]"
  backoff=$((backoff * 2))
  [ "$backoff" -le "$OPTIONAL_BACKOFF_MAX" ] || backoff=$OPTIONAL_BACKOFF_MAX
  CHILD_BACKOFF[$name]=$backoff
  echo "[ardtt] restart optional ${name}"
  eval "${CHILD_CMD[$name]}" &
  echo $! >"/var/run/ardtt-${name}.pid"
}

port_busy() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | awk -v p=":${port}" '
      $4 == p || $4 ~ (p "$") { found=1 }
      END { exit !found }
    '
  else
    return 1
  fi
}

start_child provision /usr/local/bin/provision -cmd serve -data "$DATA"

if [ "$ROLE" = "exit" ]; then
  start_child cascade /cascade-entrypoint.sh
  start_child dns /opt/ardtt/dns.sh
  start_optional_child warp /opt/ardtt/warp.sh
elif [ "$CASCADE_ENABLED" = "1" ]; then
  start_child direct /opt/ardtt/direct.sh
  start_child bypass /opt/ardtt/bypass.sh
  start_optional_child warp /opt/ardtt/warp.sh
  start_child cascade /cascade-entrypoint.sh
else
  start_child direct /opt/ardtt/direct.sh
  start_child bypass /opt/ardtt/bypass.sh
  start_child dns /opt/ardtt/dns.sh
  start_optional_child warp /opt/ardtt/warp.sh
fi

if [ "$SKIP_TELEMETRY" = "1" ]; then
  echo "[ardtt] telemetry skipped (ARDTT_SKIP_TELEMETRY=1)"
elif port_busy "$TELEMETRY_PORT"; then
  echo "[ardtt] WARN: :${TELEMETRY_PORT} already listening — skip telemetry"
else
  start_optional_child telemetry python3 -m gunicorn \
    --chdir /opt/ardtt/telemetry \
    --bind "${TELEMETRY_LISTEN}" \
    --workers 2 \
    --timeout 120 \
    app:app
fi

reap() {
  echo "[ardtt] stopping children"
  local pidfile pid i any
  for pidfile in /var/run/ardtt-*.pid; do
    [ -f "$pidfile" ] || continue
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null || true
  done
  # Warp/cascade EXIT traps need more than a fraction of a second (iptables,
  # ip rule, tun2socks). SIGKILL at 0.4s left hostnet leftovers on the VPS.
  for i in $(seq 1 15); do
    any=0
    for pidfile in /var/run/ardtt-*.pid; do
      [ -f "$pidfile" ] || continue
      pid="$(cat "$pidfile" 2>/dev/null || true)"
      [ -n "${pid:-}" ] || continue
      if kill -0 "$pid" 2>/dev/null; then
        any=1
        break
      fi
    done
    [ "$any" = 0 ] && break
    sleep 0.2
  done
  for pidfile in /var/run/ardtt-*.pid; do
    [ -f "$pidfile" ] || continue
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    [ -n "${pid:-}" ] && kill -9 "$pid" 2>/dev/null || true
    rm -f "$pidfile"
  done
}

# TERM/INT must end the script: a trap that only reaps returns into the loop
# below, and `docker stop` then waits its whole timeout before SIGKILL (every
# update cost ~30 s of VPN downtime). The sleep runs in the background so the
# signal is handled at once, not after it.
trap reap EXIT
trap 'reap; trap - EXIT; exit 0' INT TERM

while true; do
  sleep 2 &
  wait $! || true
  for pidfile in /var/run/ardtt-*.pid; do
    [ -f "$pidfile" ] || continue
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    [ -n "${pid:-}" ] || continue
    if ! kill -0 "$pid" 2>/dev/null; then
      name="$(basename "$pidfile" .pid)"
      name="${name#ardtt-}"
      if [ -n "${CHILD_OPTIONAL[$name]:-}" ]; then
        restart_optional_child "$name"
        continue
      fi
      echo "[ardtt] child ${name} pid=${pid} died"
      exit 1
    fi
  done
done
