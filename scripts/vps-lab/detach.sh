#!/usr/bin/env bash
# Start scripts/vps-lab/run.sh <scenario> as a transient systemd unit and follow it.
# «diag» runs inline: it must work even when systemd-run is the problem.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCENARIO="${1:-full}"
LAB=/var/lib/ardtt-lab
# The lab installs a system stack: re-run as root when the runner account is not.
if [ "$(id -u)" -ne 0 ]; then
  exec sudo -E bash "$0" "$@"
fi
mkdir -p "$LAB"

if [ "$SCENARIO" = diag ] || ! command -v systemd-run >/dev/null 2>&1; then
  exec bash "$ROOT/scripts/vps-lab/run.sh" "$SCENARIO"
fi

# A private copy of the tree: the runner cleans its workspace between jobs.
STAMP="$(date +%Y%m%d-%H%M%S)"
COPY="$LAB/src-$STAMP"
cp -a "$ROOT" "$COPY"
LOG="$LAB/run-$SCENARIO-$STAMP.log"
RC="$LAB/run-$SCENARIO-$STAMP.rc"
ln -sfn "$LOG" "$LAB/last-run.log"
UNIT="ardtt-lab-$STAMP"

systemd-run --unit "$UNIT" --collect --quiet \
  -p StandardOutput=append:"$LOG" -p StandardError=append:"$LOG" \
  /bin/bash -c "bash '$COPY/scripts/vps-lab/run.sh' '$SCENARIO'; echo \$? > '$RC'" \
  || { echo "systemd-run failed"; exit 1; }
echo "scenario ${SCENARIO} → unit ${UNIT}, log ${LOG}"

touch "$LOG"
tail -n +1 -f "$LOG" &
TAIL=$!
while [ ! -f "$RC" ]; do
  sleep 5
done
sleep 2
kill "$TAIL" 2>/dev/null || true
rc="$(cat "$RC")"
echo "scenario exit ${rc}"
exit "$rc"
