#!/usr/bin/env bash
# jq parsing for cascade-exit Hide-IP prefix lists.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }

command -v jq >/dev/null 2>&1 || { echo "skip: no jq"; exit 0; }

peer_json='{"ok":true,"prefixes":["10.8.0.5/32","10.9.0.5/32"]}'
got="$(echo "$peer_json" | jq -r '.prefixes[]? // empty')"
echo "$got" | grep -qxF '10.8.0.5/32' || err "missing direct prefix"
echo "$got" | grep -qxF '10.9.0.5/32' || err "missing bypass prefix"

empty_json='{"ok":true,"prefixes":[]}'
got_empty="$(echo "$empty_json" | jq -r '.prefixes[]? // empty')"
[ -z "$got_empty" ] || err "empty prefixes should yield no lines"

echo "$peer_json" | jq -e '.ok == true' >/dev/null || err "ok true"

users="$(mktemp)"
cat >"$users" <<'JSON'
{
  "config": {"directSubnet": "10.8.0.0/24", "bypassSubnet": "10.9.0.0/24"},
  "users": [
    {"name": "on", "hostId": 5, "hideIp": true},
    {"name": "off", "hostId": 6, "hideIp": false},
    {"name": "dead", "hostId": 7, "hideIp": true, "deactivated": true}
  ]
}
JSON
from_file="$(jq -r '
  (.config.directSubnet // "10.8.0.0/24") as $d
  | (.config.bypassSubnet // "10.9.0.0/24") as $b
  | ($d | split(".") | .[0:3] | join(".")) as $db
  | ($b | split(".") | .[0:3] | join(".")) as $bb
  | .users[] | select(.hideIp == true and (.deactivated != true) and (.hostId > 0))
  | "\($db).\(.hostId)/32\n\($bb).\(.hostId)/32"
' "$users")"
rm -f "$users"
echo "$from_file" | grep -qxF '10.8.0.5/32' || err "jq users on direct"
if echo "$from_file" | grep -qxF '10.9.0.7/32'; then err "deactivated user leaked"; fi
if echo "$from_file" | grep -qxF '10.8.0.6/32'; then err "hideIp=false leaked"; fi

grep -q 'exit-hideip' "$ROOT/server/warp/entrypoint.sh" || err "entrypoint missing exit-hideip"
grep -q 'warp_passthrough_mode' "$ROOT/server/warp/entrypoint.sh" || err "entrypoint missing passthrough"

if [ "$fail" -ne 0 ]; then
  echo "warp hideIp prefix tests failed" >&2
  exit 1
fi
echo "OK warp hideIp prefixes"
