#!/usr/bin/env bash
# One-off Docker isolation check: install from a packed archive while foreign
# containers and a host HTTP listener keep running. Does not use the user's VPS.
# Readiness (WARP/Direct) is reported but does not fail this script unless
# ARDTT_REQUIRE_READY=1.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:-}"
[ -n "$PKG" ] && [ -f "$PKG" ] || { echo "usage: $0 ardtt-server-*.tar.gz" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || { echo "SKIP: no docker CLI"; exit 0; }
docker info >/dev/null 2>&1 || { echo "SKIP: docker engine down"; exit 0; }
[ -e /dev/net/tun ] || { echo "SKIP: no /dev/net/tun"; exit 0; }

SHA="${2:-}"
if [ -z "$SHA" ] && [ -f "${PKG}.sha256" ]; then
  SHA="$(awk '{print $1}' "${PKG}.sha256")"
fi
[ -n "$SHA" ] || SHA="$(sha256sum "$PKG" | awk '{print $1}')"

INSTALL="${ARDTT_LIVE_INSTALL_DIR:-$(mktemp -d /tmp/ardtt-live-XXXXXX)}"
FOREIGN=(stack-nginx-1 stack-xray-1 ardtt-lookalike ardtt)
RUN_ID="iso${RANDOM}$$"
CREATED_IDS=()
HOST_PORT="${ARDTT_LIVE_HTTP_PORT:-18080}"
HTTPD_PID=""
PROBE_PID=""
STAGE=""
INSTALL_SH="$ROOT/server/install.sh"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

live_isolation_require_free_names() {
  local name
  for name in "$@"; do
    if docker inspect "$name" >/dev/null 2>&1; then
      echo "ABORT: container $name already exists; not touching it" >&2
      return 1
    fi
  done
  return 0
}

live_uninstall_script() {
  if [ -n "${INSTALL:-}" ] && [ -f "${INSTALL}/current/install.sh" ]; then
    printf '%s' "${INSTALL}/current/install.sh"
    return 0
  fi
  if [ -n "${INSTALL_SH:-}" ] && [ -f "${INSTALL_SH}" ]; then
    printf '%s' "${INSTALL_SH}"
    return 0
  fi
  printf '%s' "$ROOT/server/install.sh"
}

cleanup() {
  local un id
  un="$(live_uninstall_script)"
  if [ -f "$un" ]; then
    unset ARDTT_PKG_DIR || true
    ARDTT_ACTION=uninstall ARDTT_INSTALL_DIR="$INSTALL" ARDTT_SKIP_ROOT_CHECK=1 \
      ARDTT_PURGE_DATA=1 ARDTT_PACKAGE="$PKG" \
      ARDTT_PACKAGE_SHA256="$SHA" \
      bash "$un" >/tmp/ardtt-live-uninstall.log 2>&1 || true
  fi
  if [ -n "${PROBE_PID:-}" ]; then
    kill "$PROBE_PID" >/dev/null 2>&1 || true
  fi
  for id in "${CREATED_IDS[@]:-}"; do
    [ -n "$id" ] || continue
    owner="$(docker inspect -f '{{index .Config.Labels "com.ardtt.test-run"}}' "$id" 2>/dev/null || true)"
    if [ "$owner" = "$RUN_ID" ]; then
      docker rm -f "$id" >/dev/null 2>&1 || true
    fi
  done
  if [ -n "${HTTPD_PID:-}" ]; then
    kill "$HTTPD_PID" >/dev/null 2>&1 || true
  fi
  [ -n "${STAGE:-}" ] && rm -rf "$STAGE"
}
trap cleanup EXIT

IMAGE="$(python3 - "$PKG" <<'PY' || true
import io, json, sys, tarfile
pkg = sys.argv[1]
with tarfile.open(pkg, "r:gz") as outer:
    man = outer.extractfile("manifest.json")
    print(json.load(io.TextIOWrapper(man, encoding="utf-8"))["image"]["tag"])
PY
)"
if [ -z "$IMAGE" ]; then
  echo "could not read image tag from package" >&2
  exit 1
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  STAGE_LOAD="$(mktemp -d)"
  python3 "$ROOT/scripts/safe-extract-package.py" "$PKG" "$STAGE_LOAD"
  docker load -i "$STAGE_LOAD/images/ardtt.tar"
  rm -rf "$STAGE_LOAD"
fi

STAGE="$(mktemp -d /tmp/ardtt-pkg-extract-XXXXXX)"
python3 "$ROOT/scripts/safe-extract-package.py" "$PKG" "$STAGE"
INSTALL_SH="$STAGE/install.sh"
test -f "$INSTALL_SH"

live_isolation_require_free_names "${FOREIGN[@]}" || exit 1

for name in "${FOREIGN[@]}"; do
  id="$(docker run -d --name "$name" --restart unless-stopped --network none \
    --label com.ardtt.owner=foreign --label com.ardtt.instance=foreign \
    --label com.ardtt.foreign=1 --label "com.ardtt.test-run=$RUN_ID" \
    --entrypoint sleep "$IMAGE" 7200)"
  CREATED_IDS+=("$id")
done

python3 -m http.server "$HOST_PORT" --bind 127.0.0.1 >/tmp/ardtt-live-httpd.log 2>&1 &
HTTPD_PID=$!
sleep 0.3
curl -fsS --max-time 2 "http://127.0.0.1:${HOST_PORT}/" >/dev/null || err "host httpd did not start"
: > /tmp/ardtt-live-http-probe.log
(
  while kill -0 "$HTTPD_PID" >/dev/null 2>&1; do
    if curl -fsS --max-time 1 "http://127.0.0.1:${HOST_PORT}/" >/dev/null 2>&1; then
      echo ok >> /tmp/ardtt-live-http-probe.log
    else
      echo fail >> /tmp/ardtt-live-http-probe.log
    fi
    sleep 0.4
  done
) &
PROBE_PID=$!

declare -A FOREIGN_ID
for name in "${FOREIGN[@]}"; do
  FOREIGN_ID[$name]="$(docker inspect -f '{{.Id}} {{.State.Status}} {{.RestartCount}}' "$name")"
done
DOCKER_VER_BEFORE="$(docker version --format '{{.Server.Version}}')"
ROUTE_BEFORE="$(ip -4 route show default || true)"

export ARDTT_SKIP_ROOT_CHECK=1
export ARDTT_INSTALL_DIR="$INSTALL"
export ARDTT_PACKAGE="$PKG"
export ARDTT_PACKAGE_SHA256="$SHA"
export ARDTT_PKG_DIR="$STAGE"
export ARDTT_PUBLIC_HOST=127.0.0.1
export ARDTT_AUTO_PORTS=1
export ARDTT_MIN_DISK_MB=500
export ARDTT_MIN_RAM_MB=128
unset ARDTT_ACTION || true

set +e
bash "$INSTALL_SH" > /tmp/ardtt-live-install.log 2>&1
install_rc=$?
set -e

if grep -q '^ARDTT_DONE|' /tmp/ardtt-live-install.log; then
  ok "install emitted ARDTT_DONE"
  READY=1
  cname="$(grep '^ARDTT_DONE|' /tmp/ardtt-live-install.log | tail -1 | tr '|' '\n' | sed -n 's/^container=//p')"
  if [ -n "$cname" ]; then
    mode="$(docker exec "$cname" stat -c '%a %u' /opt/ardtt/ready.sh 2>/dev/null || true)"
    echo "${mode:-}" | grep -q '^755 0$' || err "ready.sh should be mode 755 uid 0, got ${mode:-missing}"
    ready_json="$(curl -fsS --max-time 3 http://127.0.0.1:9100/ready || true)"
    echo "$ready_json" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*true' || err "GET /ready failed: ${ready_json:-empty}"
    ok "overlay ready.sh 755 root and GET /ready"
  fi
else
  echo "WARN: install did not emit ARDTT_DONE (rc=$install_rc). Last lines:"
  tail -20 /tmp/ardtt-live-install.log || true
  READY=0
  if [ "${ARDTT_REQUIRE_READY:-0}" = "1" ]; then
    err "ARDTT_REQUIRE_READY=1 and install failed"
  fi
fi

if grep -qE 'get\.docker\.com|docker builder prune|cleanup_host_dataplane|swapoff /swapfile' /tmp/ardtt-live-install.log; then
  err "install log contains forbidden host operations"
fi

for name in "${FOREIGN[@]}"; do
  now="$(docker inspect -f '{{.Id}} {{.State.Status}} {{.RestartCount}}' "$name" 2>/dev/null || true)"
  if [ -z "$now" ]; then
    err "foreign container $name was removed"
    continue
  fi
  before="${FOREIGN_ID[$name]}"
  before_id="${before%% *}"
  now_id="${now%% *}"
  now_status="$(echo "$now" | awk '{print $2}')"
  [ "$before_id" = "$now_id" ] || err "foreign container $name was recreated"
  [ "$now_status" = "running" ] || err "foreign container $name is $now_status"
done
ok "foreign containers still running with the same IDs"

DOCKER_VER_AFTER="$(docker version --format '{{.Server.Version}}')"
[ "$DOCKER_VER_BEFORE" = "$DOCKER_VER_AFTER" ] || err "Docker server version changed"
docker info >/dev/null 2>&1 || err "Docker engine no longer responds"
curl -fsS --max-time 2 "http://127.0.0.1:${HOST_PORT}/" >/dev/null || err "host httpd stopped"
if grep -q '^fail$' /tmp/ardtt-live-http-probe.log 2>/dev/null; then
  err "neighbor HTTP probe dropped during install"
else
  ok "neighbor HTTP stayed up during install"
fi
ROUTE_AFTER="$(ip -4 route show default || true)"
[ "$ROUTE_BEFORE" = "$ROUTE_AFTER" ] || err "default route changed"

if docker inspect ardtt >/dev/null 2>&1; then
  owner="$(docker inspect -f '{{index .Config.Labels "com.ardtt.owner"}}' ardtt 2>/dev/null || true)"
  inst="$(docker inspect -f '{{index .Config.Labels "com.ardtt.instance"}}' ardtt 2>/dev/null || true)"
  if [ "$owner" = "ardtt" ] && [ "$inst" != "foreign" ]; then
    err "installer reused the foreign name ardtt"
  else
    ok "name ardtt still belongs to the foreign container"
  fi
fi

export ARDTT_ACTION=uninstall
export ARDTT_PURGE_DATA=1
unset ARDTT_PKG_DIR || true
UNINSTALL_SH="$(live_uninstall_script)"
bash "$UNINSTALL_SH" > /tmp/ardtt-live-uninstall.log 2>&1 || true
grep -q ARDTT_UNINSTALLED /tmp/ardtt-live-uninstall.log || err "uninstall missing ARDTT_UNINSTALLED"

for name in "${FOREIGN[@]}"; do
  docker inspect "$name" >/dev/null 2>&1 || err "uninstall removed foreign $name"
done
docker info >/dev/null 2>&1 || err "Docker engine died during uninstall"
ok "uninstall left Docker and foreign containers"

# Prevent the EXIT trap from uninstalling twice / removing foreign before asserts
trap - EXIT
cleanup

if [ "$fail" -ne 0 ]; then
  echo "live isolation failed" >&2
  exit 1
fi
echo "OK live isolation (ready=$READY install_dir=$INSTALL)"
exit 0
