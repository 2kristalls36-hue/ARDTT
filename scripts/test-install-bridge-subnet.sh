#!/usr/bin/env bash
# pick_bridge_subnet must not get stuck when the host has 172.16.0.0/12.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

die() { echo "ARDTT_ERROR|$*" >&2; exit 1; }
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/network.sh"

list_host_cidrs() { printf '%s\n' '172.17.0.0/16' '10.0.0.0/24'; }
got="$(pick_bridge_subnet)"
[ "$got" = "172.28.20.0/24" ] || err "default family should stay 172.28.20.0/24, got '$got'"
ok "172.28.20.0/24 when the /12 is free"

list_host_cidrs() { printf '%s\n' '172.16.0.0/12' '172.17.0.0/16 '; }
got="$(pick_bridge_subnet)"
[ "$got" = "10.112.0.0/24" ] || err "trailing space on docker subnet must not break picker, got '$got'"
ok "strips trailing space from docker subnet CIDRs"

got="$(pick_bridge_subnet 10.210.40.0/24)"
[ "$got" = "10.210.40.0/24" ] || err "preferred 10.210.40.0/24 kept, got '$got'"
ok "honours a free preferred subnet"

if [ "$fail" -ne 0 ]; then
  echo "bridge subnet picker tests failed" >&2
  exit 1
fi
ok "bridge subnet picker"
