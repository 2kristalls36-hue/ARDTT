#!/usr/bin/env bash
# Run Linux doctor when a local SDK is present (skipped in bare CI).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "SKIP doctor-json (no SDK at $SDK)"
  exit 0
fi
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
if [[ -x "$HOME/ardtt-testlab/tools/go/bin/go" ]]; then
  export PATH="$HOME/ardtt-testlab/tools/go/bin:$PATH"
fi
out="$(mktemp)"
trap 'rm -f "$out"' EXIT
set +e
"$LAB/Doctor-ARDTT.sh" --json-out "$out"
code=$?
set -e
python3 - "$out" "$code" <<'PY'
import json, sys
from pathlib import Path
doc = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
code = int(sys.argv[2])
assert doc["schema"] == "ardtt-lab-doctor/v1"
assert doc["summary"] in {"PASS", "FAIL", "BLOCKED"}
assert doc["checks"]
names = {c["name"] for c in doc["checks"]}
assert "java" in names and "ndk" in names
if doc["summary"] == "FAIL":
    assert code != 0
else:
    assert code == 0
print("doctor json", doc["summary"], "checks", len(doc["checks"]))
PY
echo "doctor-json tests passed"
