#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAIL=0
run() {
  local name=$1
  shift
  echo "==> $name"
  if "$@"; then
    echo "OK $name"
  else
    echo "FAIL $name" >&2
    FAIL=1
  fi
}

chmod +x "$HERE"/*.sh "$HERE/../lib"/*.sh "$HERE/../Install-LinuxToolchain.sh" \
  "$HERE/../Build-ARDTT.sh" "$HERE/../Doctor-ARDTT.sh" "$HERE/../Fetch-PreviewApk.sh" 2>/dev/null || true

run test-redact python3 "$HERE/test-redact.py"
run test-common "$HERE/test-common.sh"
run test-versions "$HERE/test-versions.sh"
run test-pack-session "$HERE/test-pack-session.sh"
run test-capture-state "$HERE/test-capture-state.sh"
run test-scenarios "$HERE/test-scenarios.sh"
run test-session "$HERE/test-session.sh"
run test-apk-gate "$HERE/test-apk-gate.sh"
run test-required-files "$HERE/test-required-files.sh"
run test-doctor-json "$HERE/test-doctor-json.sh"
run test-build-help "$HERE/test-build-help.sh"

if [[ "$FAIL" -ne 0 ]]; then
  echo "lab tests failed" >&2
  exit 1
fi
echo "all lab tests passed"
