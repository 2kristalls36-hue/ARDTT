#!/usr/bin/env bash
# Stop only logcat PIDs recorded for this session.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/common.sh"

SESSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --session) SESSION=$2; shift 2 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done
[[ -n "$SESSION" && -d "$SESSION" ]] || ardtt_die "--session обязателен"

if [[ -f "$SESSION/logcat.pids" ]]; then
  while read -r pid; do
    [[ -n "${pid:-}" ]] || continue
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      sleep 0.1
      if kill -0 "$pid" 2>/dev/null; then
        kill -9 "$pid" 2>/dev/null || true
      fi
      ardtt_log INFO "stopped logcat pid=$pid"
    fi
  done <"$SESSION/logcat.pids"
fi

python3 - "$SESSION/session.json" <<'PY'
import json, os, sys, datetime
path = sys.argv[1]
doc = {}
if os.path.exists(path):
    doc = json.loads(open(path, encoding="utf-8").read() or "{}")
doc["stoppedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
doc["logcatPids"] = []
doc["stopped"] = True
open(path, "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
PY
rm -f "$SESSION/ACTIVE.lock"
printf '{"tsUtc":"%s","event":"session-stop"}\n' "$(ardtt_utc_now)" >>"$SESSION/timeline.jsonl"
