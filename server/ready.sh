#!/bin/bash
# Container readiness: provision is not enough (GET /health always returns ok=true).
# Required processes and interfaces depend on ARDTT_ROLE / cascade.
set -euo pipefail

ROLE="${ARDTT_ROLE:-entry}"
CASCADE="${ARDTT_CASCADE_ENABLED:-0}"
[ "$ROLE" = "exit" ] && CASCADE=1
WARP_MODE="${ARDTT_WARP_MODE:-hideip}"

fail() { echo "not ready: $*" >&2; exit 1; }

alive() {
  local name="$1" pidfile="/var/run/ardtt-${name}.pid" pid
  [ -f "$pidfile" ] || return 1
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  [ -n "${pid:-}" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

iface() { ip link show "$1" >/dev/null 2>&1; }

curl -fsS --max-time 2 http://127.0.0.1:9100/health >/dev/null || fail "provision /health"
alive provision || fail "provision process"

if [ "$ROLE" = "exit" ]; then
  alive cascade || fail "cascade process"
  alive dns || fail "dns process"
  iface cascade0 || fail "cascade0"
  if [ "$WARP_MODE" = "exit-hideip" ]; then
    alive warp || fail "warp process"
  fi
elif [ "$CASCADE" = "1" ]; then
  alive direct || fail "direct process"
  alive bypass || fail "bypass process"
  alive cascade || fail "cascade process"
  iface awg0 || fail "awg0"
  iface wdttraw0 || fail "wdttraw0"
  iface cascade0 || fail "cascade0"
else
  alive direct || fail "direct process"
  alive bypass || fail "bypass process"
  alive dns || fail "dns process"
  iface awg0 || fail "awg0"
  iface wdttraw0 || fail "wdttraw0"
fi

if [ "$WARP_MODE" = "hideip" ]; then
  alive warp || fail "warp process"
fi

if [ "${ARDTT_SKIP_TELEMETRY:-0}" != "1" ]; then
  curl -fsS --max-time 2 http://127.0.0.1:9200/health >/dev/null || fail "telemetry /health"
fi

echo "ready"
exit 0
