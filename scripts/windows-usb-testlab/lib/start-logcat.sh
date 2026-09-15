#!/usr/bin/env bash
# Start continuous logcat processes for a session. Records PIDs; does not clear logcat.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/common.sh"

SESSION=""
SERIAL="${ANDROID_SERIAL:-}"
ADB_BIN="${ADB:-adb}"
BUFFERS="${ARDTT_LOGCAT_BUFFERS:-main,system,crash,events}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --session) SESSION=$2; shift 2 ;;
    --serial) SERIAL=$2; shift 2 ;;
    --adb) ADB_BIN=$2; shift 2 ;;
    --buffers) BUFFERS=$2; shift 2 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done
[[ -n "$SESSION" ]] || ardtt_die "--session обязателен"
mkdir -p "$SESSION"
LOCK="$SESSION/ACTIVE.lock"
if [[ -f "$LOCK" ]]; then
  oldpid="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("ownerPid",""))' "$LOCK" 2>/dev/null || true)"
  if [[ -n "$oldpid" ]] && kill -0 "$oldpid" 2>/dev/null; then
    ardtt_die "сессия уже активна (pid $oldpid): $SESSION"
  fi
fi

adb_args=()
if [[ -n "$SERIAL" ]]; then
  adb_args+=(-s "$SERIAL")
fi

: >"$SESSION/logcat.pids"
IFS=',' read -r -a bufs <<<"$BUFFERS"
pids=()
for buf in "${bufs[@]}"; do
  buf="$(echo "$buf" | tr -d ' ')"
  [[ -n "$buf" ]] || continue
  out="$SESSION/logcat-${buf}.txt"
  err="$SESSION/logcat-${buf}.stderr.txt"
  : >"$out"
  : >"$err"
  # Do not use -c. Marker is written by Mark, not by clearing the buffer.
  "$ADB_BIN" "${adb_args[@]}" logcat -v threadtime -b "$buf" >>"$out" 2>>"$err" &
  pid=$!
  pids+=("$pid")
  printf '%s\n' "$pid" >>"$SESSION/logcat.pids"
  ardtt_log INFO "logcat buffer=$buf pid=$pid"
done

python3 - "$SESSION/session.json" "$SESSION" "$SERIAL" $$ <<'PY'
import json, os, sys, datetime
path, session, serial, owner = sys.argv[1:5]
pids = []
pidfile = os.path.join(session, "logcat.pids")
if os.path.exists(pidfile):
    pids = [int(x) for x in open(pidfile).read().split() if x.strip().isdigit()]
doc = {
  "schema": "ardtt-lab-session/v1",
  "sessionDir": session,
  "startedAtUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
  "serialLocal": serial or None,
  "logcatPids": pids,
  "ownerPid": int(owner),
  "stoppedAtUtc": None,
}
open(path, "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
open(os.path.join(session, "ACTIVE.lock"), "w", encoding="utf-8").write(json.dumps({"ownerPid": int(owner), "sessionDir": session}) + "\n")
PY
: >"$SESSION/timeline.jsonl"
printf '{"tsUtc":"%s","event":"session-start","serial":"%s"}\n' "$(ardtt_utc_now)" "$SERIAL" >>"$SESSION/timeline.jsonl"
