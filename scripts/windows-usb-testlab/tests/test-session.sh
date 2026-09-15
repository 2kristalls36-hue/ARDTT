#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
# shellcheck disable=SC1091
source "$LAB/lib/common.sh"

chmod +x "$LAB/lib/start-logcat.sh" "$LAB/lib/stop-logcat.sh" "$HERE/mock-adb.sh"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
export MOCK_ADB_LOG="$WORKDIR/adb.log"
SESSION="$WORKDIR/session"
mkdir -p "$SESSION"

"$LAB/lib/start-logcat.sh" --session "$SESSION" --adb "$HERE/mock-adb.sh" --serial MOCKSERIAL0123
[[ -f "$SESSION/session.json" ]] || { echo "FAIL no session.json" >&2; exit 1; }
python3 - "$SESSION/session.json" <<'PY'
import json, sys
from pathlib import Path
doc = json.loads(Path(sys.argv[1]).read_text())
assert doc["logcatPids"], doc
print("pids", doc["logcatPids"])
PY

echo '{"event":"user-mark","note":"repro"}' >>"$SESSION/timeline.jsonl"
sleep 0.5
[[ -s "$SESSION/logcat-main.txt" ]] || { echo "FAIL empty logcat" >&2; exit 1; }

"$LAB/lib/stop-logcat.sh" --session "$SESSION"
python3 - "$SESSION/session.json" <<'PY'
import json, sys
from pathlib import Path
doc = json.loads(Path(sys.argv[1]).read_text())
assert doc.get("stoppedAtUtc"), doc
assert doc.get("logcatPids") == [] or doc.get("stopped", True)
print("stopped", doc.get("stoppedAtUtc"))
PY

# PID must not still be running
if [[ -f "$SESSION/logcat.pids" ]]; then
  while read -r pid; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      echo "FAIL pid $pid still alive" >&2
      exit 1
    fi
  done <"$SESSION/logcat.pids"
fi
echo "session tests passed"
