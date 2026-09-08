#!/usr/bin/env bash
# Unit-test install-lib UDP/TCP auto-port helpers without Docker.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat >"$TMP/ports.sh" <<'EOF'
AUTO_PORTS=1
OUR_HOST_PORTS=""
die() { echo "ARDTT_ERROR|$*" >&2; exit 1; }
BUSY_PORTS=""
docker_published_port() { return 1; }
_ss_listen() {
  local proto="$1" port="$2" p
  for p in $BUSY_PORTS; do
    [ "$p" = "$port" ] && return 0
  done
  return 1
}
who_owns_port() { echo "mock owner"; }
EOF

# Override docker_published_port after sourcing by keeping our stub: source ports then re-define.
# shellcheck disable=SC1091
. "$TMP/ports.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/ports.sh"
docker_published_port() { return 1; }
_ss_listen() {
  local proto="$1" port="$2" p
  for p in $BUSY_PORTS; do
    [ "$p" = "$port" ] && return 0
  done
  return 1
}

BUSY_PORTS="51820 56003"
got="$(resolve_udp_host_port 51820 "Direct" 2>"$TMP/warn1")"
[ "$got" = "51821" ] || err "expected Direct 51821, got '$got'"
grep -q 'ARDTT_WARN|Direct' "$TMP/warn1" || err "missing Direct warn on stderr"
ok "auto Direct skips busy 51820 → $got"

got="$(resolve_udp_host_port 56003 "Bypass" 51821 2>"$TMP/warn2")"
[ "$got" = "56004" ] || err "expected Bypass 56004, got '$got'"
ok "auto Bypass skips busy 56003 → $got"

BUSY_PORTS=""
got="$(resolve_udp_host_port 51820 "Direct" 2>/dev/null)"
[ "$got" = "51820" ] || err "free preferred must stay 51820, got '$got'"
ok "keeps free preferred port"

AUTO_PORTS=0
BUSY_PORTS="51820"
set +e
out="$(resolve_udp_host_port 51820 "Direct" 2>&1)"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "manual mode must die on busy port (rc=$rc out='$out')"
echo "$out" | grep -q 'ARDTT_ERROR|' || err "manual busy must emit ARDTT_ERROR (out='$out')"
ok "manual mode errors on busy port"

AUTO_PORTS=1
BUSY_PORTS=""
got="$(resolve_udp_host_port 51820 "Bypass" 51820 2>/dev/null)"
[ "$got" = "51821" ] || err "must avoid reserved sibling port, got '$got'"
ok "avoids reserved Direct when picking Bypass"

AUTO_PORTS=1
BUSY_PORTS="9100 9200"
got="$(resolve_tcp_host_port 9100 "provision" 2>/dev/null)"
[ "$got" = "9101" ] || err "expected provision 9101, got '$got'"
ok "auto TCP provision skips busy 9100"

OUR_HOST_PORTS="udp:51820"
BUSY_PORTS="51820"
got="$(resolve_udp_host_port 51820 "Direct" 2>/dev/null)"
[ "$got" = "51820" ] || err "update must keep our published port, got '$got'"
ok "our published port is not a foreign conflict"

# HostPort is the host publish; container-port keys must not be treated as busy host ports.
mapped='{"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"9100"}]} {"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"9100"}]}'
inspect_json_has_host_port "$mapped" 9100 tcp || err "host 9100 via container 80/tcp must be busy"
inspect_json_has_host_port "$mapped" 80 tcp && err "container port 80 must not count as host 80"
inspect_json_has_host_port "$mapped" 9100 udp && err "tcp HostPort must not count as udp"
ok "HostPort 9100 from container 80/tcp"

shifted='{"9100/tcp":[{"HostIp":"","HostPort":"19100"}]}'
inspect_json_has_host_port "$shifted" 9100 tcp && err "container 9100 published as 19100 is not host 9100"
inspect_json_has_host_port "$shifted" 19100 tcp || err "host 19100 must be busy"
ok "container 9100/tcp → host 19100"

if [ "$fail" -ne 0 ]; then
  echo "auto-ports helper tests failed" >&2
  exit 1
fi
echo "OK auto-ports helpers"
