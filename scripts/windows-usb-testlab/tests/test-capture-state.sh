#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
CAPTURE="$LAB/lib/capture-state.sh"
MOCK="$HERE/mock-adb.sh"

FAIL=0
fail() { echo "FAIL: $*" >&2; FAIL=1; }
ok() { echo "OK $*"; }

chmod +x "$CAPTURE" "$MOCK"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
export MOCK_ADB_LOG="$WORKDIR/adb.log"
export MOCK_ADB_ROOT="$WORKDIR/root"
mkdir -p "$MOCK_ADB_ROOT"

OUT="$WORKDIR/cap"
"$CAPTURE" --out "$OUT" --serial MOCKSERIAL0123 --adb "$MOCK"

[[ -f "$OUT/capture-index.json" ]] || fail "index missing"
ok "index"

python3 - "$OUT/capture-index.json" "$LAB/lib/capture-commands.json" "$MOCK_ADB_LOG" <<'PY' || fail "catalog coverage"
import json, sys
from pathlib import Path
idx = json.loads(Path(sys.argv[1]).read_text())
cat = json.loads(Path(sys.argv[2]).read_text())
log = Path(sys.argv[3]).read_text()
ids = [c["id"] for c in cat]
got = [r["id"] for r in idx["results"]]
assert got == ids, (got, ids)
assert all(r["status"] in {"ok", "unavailable", "timeout"} for r in idx["results"])
assert "dumpsys" in log
assert "connectivity" in log
print("covered", len(got))
PY
ok "all catalog commands invoked"

# screenshot helper
SCREEN="$LAB/lib/capture-screenshot.sh"
chmod +x "$SCREEN"
PNG="$WORKDIR/screen.png"
"$SCREEN" --out "$PNG" --serial MOCKSERIAL0123 --adb "$MOCK"
[[ -f "$PNG" ]] || fail "png missing"
python3 - "$PNG" <<'PY' || fail "png header"
from pathlib import Path
import sys
data = Path(sys.argv[1]).read_bytes()
assert data.startswith(b"\x89PNG\r\n\x1a\n"), data[:16]
print("png bytes", len(data))
PY
ok "png"

if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi
echo "capture-state tests passed"
