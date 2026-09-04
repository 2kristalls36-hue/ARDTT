#!/usr/bin/env bash
# wg_conf_field must keep WireGuard "=" padding (wireproxy 1.1.2 is strict).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENTRY="$ROOT/server/warp/entrypoint.sh"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

eval "$(sed -n '/^wg_conf_field()/,/^}/p' "$ENTRY")"
type wg_conf_field >/dev/null 2>&1 || { echo "wg_conf_field not extracted from $ENTRY" >&2; exit 1; }

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
cat >"$TMP" <<'EOF'
[Interface]
PrivateKey = eF0xJJ0cYcnXCA94IRk+RMPKma1361RZ2zdbQkyvk0E=
Address = 172.16.0.2/32, 2606:4700:110:883c:a528:9134:7d83:5b99/128
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280
[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = engage.cloudflareclient.com:2408
EOF

priv="$(wg_conf_field PrivateKey "$TMP")"
pub="$(wg_conf_field PublicKey "$TMP")"
endpoint="$(wg_conf_field Endpoint "$TMP")"
addr="$(wg_conf_field Address "$TMP" | cut -d, -f1 | tr -d ' ')"

[ "$priv" = "eF0xJJ0cYcnXCA94IRk+RMPKma1361RZ2zdbQkyvk0E=" ] || err "PrivateKey padding stripped: [$priv]"
[ "$pub" = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=" ] || err "PublicKey padding stripped: [$pub]"
[ "$endpoint" = "engage.cloudflareclient.com:2408" ] || err "Endpoint: [$endpoint]"
[ "$addr" = "172.16.0.2/32" ] || err "Address: [$addr]"

# The old awk -F'= *' bug, kept as a regression probe.
old="$(awk -F'= *' '/^PrivateKey/{print $2; exit}' "$TMP")"
[ "$old" != "$priv" ] || err "expected old awk splitter to differ from wg_conf_field"

if [ "$fail" -ne 0 ]; then
  echo "warp wgcf parse tests failed" >&2
  exit 1
fi
ok "warp wg_conf_field keeps base64 padding"
