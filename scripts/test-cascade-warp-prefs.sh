#!/usr/bin/env bash
# Cascade entry ip-rule prefs must not overlap WARP hideIp leftovers.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }

CASCADE="$ROOT/server/direct/cascade-entrypoint.sh"
WARP="$ROOT/server/warp/entrypoint.sh"
BYPASS="$ROOT/server/bypass/entrypoint.sh"
INSTALL="$ROOT/server/install.sh"
CORE="$ROOT/server/bypass/wdtt-server/server/core.go"

grep -q 'ENTRY_FROM_DIRECT_PRIO="${ARDTT_CASCADE_RULE_PRIO_DIRECT:-220}"' "$CASCADE" \
  || err "cascade Direct from-rule must default to pref 220"
grep -q 'ENTRY_FROM_BYPASS_PRIO="${ARDTT_CASCADE_RULE_PRIO_BYPASS:-221}"' "$CASCADE" \
  || err "cascade Bypass from-rule must default to pref 221"
if grep -q 'priority 320' "$CASCADE" || grep -q 'priority 321' "$CASCADE"; then
  err "cascade must not keep from-rules at 320/321 (WARP hideIp sweep used 300-364)"
fi

if grep -E 'seq "\$\{WARP_RULE_PRIO_BASE\}"' "$WARP" | grep -v '^#' >/dev/null; then
  err "warp must not wipe a numeric pref band that can include cascade rules"
fi
grep -q 'Do NOT sweep prefs 300' "$WARP" || err "warp should document why the numeric pref sweep is gone"

grep -q 'skip WAN MASQ for 10.9.0.0/24' "$BYPASS" \
  || err "bypass must skip WAN MASQ on cascade like Direct"
grep -q 'users.json mtime only (heartbeat) — skip SIGHUP' "$BYPASS" \
  || err "bypass must not SIGHUP WRAP keys on LastSeen heartbeats"

grep -q 'rawMTU = 1280' "$CORE" || err "RAW MTU must be 1280 to fit cascade0"

grep -q 'telemetry backend на :9199' "$INSTALL" \
  || err "install.sh must publish telemetry on :9199 when nginx owns :9200"
grep -q 'telemetry только внутри контейнера (nginx/socat)' "$INSTALL" \
  || err "install.sh must keep gunicorn when nginx :9200 and socat :9199 are both busy"
if grep -q 'внутри контейнера telemetry не стартуем' "$INSTALL"; then
  err "install.sh must not skip gunicorn because host telemetry ports are busy"
fi
if grep -q "sed -i '/ARDTT_TELEMETRY_PORT" "$INSTALL"; then
  err "install.sh must not delete the telemetry port publish when :9200 is nginx"
fi

_drop_sample="$(mktemp)"
printf '%s\n' \
  '      - "${ARDTT_DIRECT_PORT:-51820}:${ARDTT_DIRECT_PORT:-51820}/udp"' \
  '      - "${ARDTT_TELEMETRY_PORT:-9200}:9200/tcp"' \
  '      - "${ARDTT_PROVISION_PORT:-9100}:9100/tcp"' > "$_drop_sample"
_drop_out="$(mktemp)"
grep -v 'ARDTT_TELEMETRY_PORT.*9200/tcp' "$_drop_sample" > "$_drop_out"
if grep -q 'ARDTT_TELEMETRY_PORT' "$_drop_out"; then
  err "drop_telemetry_host_publish must remove the isolated :9200 mapping"
fi
grep -q 'ARDTT_DIRECT_PORT' "$_drop_out" && grep -q 'ARDTT_PROVISION_PORT' "$_drop_out" \
  || err "drop_telemetry_host_publish must keep Direct and provision publish"
rm -f "$_drop_sample" "$_drop_out"

if [ "$fail" -ne 0 ]; then
  echo "cascade/warp pref tests failed" >&2
  exit 1
fi
echo "OK cascade/warp prefs"
