#!/usr/bin/env bash
# Credential fingerprint must include expiresAt / devices, not heartbeat fields.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FP_LINE="$(sed -n '/^users_cred_fp()/,/^}/p' "$ROOT/server/bypass/entrypoint.sh")"
echo "$FP_LINE" | grep -q 'expiresAt' || { echo "users_cred_fp missing expiresAt"; exit 1; }
echo "$FP_LINE" | grep -q 'maxDevices' || { echo "users_cred_fp missing maxDevices"; exit 1; }
echo "$FP_LINE" | grep -q 'deviceIds' || { echo "users_cred_fp missing deviceIds"; exit 1; }
echo "$FP_LINE" | grep -q 'deviceId' || { echo "users_cred_fp missing deviceId"; exit 1; }
echo "$FP_LINE" | grep -qv 'lastSeenAt' || true
if echo "$FP_LINE" | grep -q 'lastSeenAt'; then
  echo "users_cred_fp must not include lastSeenAt"; exit 1
fi
if echo "$FP_LINE" | grep -q 'downBytes'; then
  echo "users_cred_fp must not include traffic"; exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
cat >"$tmp" <<'JSON'
{"users":[
  {"password":"p1","hostId":5,"deactivated":false,"expiresAt":1,"maxDevices":2,"deviceId":"a","deviceIds":["a","b"]},
  {"password":"p2","hostId":6,"deactivated":false,"expiresAt":1,"maxDevices":1,"deviceId":"c","deviceIds":["c"]}
]}
JSON
jq_expr='[.users[] | {p:.password,h:.hostId,d:(.deactivated==true),e:(.expiresAt//0),m:(.maxDevices//1),id:(.deviceId//""),ids:((.deviceIds//[])|sort)}] | sort_by(.p)'
fp1="$(jq -c "$jq_expr" "$tmp")"
# heartbeat-only: lastSeenAt / traffic must not change fingerprint
python3 - "$tmp" <<'PY'
import json,sys
p=sys.argv[1]
with open(p) as f: d=json.load(f)
d["users"][0]["lastSeenAt"]=999
d["users"][0]["downBytes"]=123
with open(p,"w") as f: json.dump(d,f)
PY
fp2="$(jq -c "$jq_expr" "$tmp")"
[ "$fp1" = "$fp2" ] || { echo "heartbeat changed fingerprint"; exit 1; }

python3 - "$tmp" <<'PY'
import json,sys
p=sys.argv[1]
with open(p) as f: d=json.load(f)
d["users"][0]["expiresAt"]=2
with open(p,"w") as f: json.dump(d,f)
PY
fp3="$(jq -c "$jq_expr" "$tmp")"
[ "$fp1" != "$fp3" ] || { echo "expiresAt did not change fingerprint"; exit 1; }
echo "ok users_cred_fp"
