#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/../lib/common.sh"
ardtt_load_versions
# shellcheck disable=SC1091
source "$HERE/../lib/apk-gate.sh"

FAIL=0
fail() { echo "FAIL $*" >&2; FAIL=1; }
ok() { echo "OK $*"; }

ardtt_apk_artifact_looks_current "ardtt-0.5.265-3f14a8c-arm64-v8a" || fail "0.5.265 artifact should pass"
ok "0.5.265 name accepted"

if ardtt_apk_artifact_looks_current "ardtt-0.5.264-51cf7ce-arm64-v8a"; then
  fail "0.5.264 artifact must be rejected"
else
  ok "0.5.264 name rejected"
fi

if ardtt_apk_artifact_looks_current "app-arm64-v8a-release.apk"; then
  fail "bare release name without version must be rejected"
else
  ok "bare apk name rejected"
fi

[[ "$ARDTT_MIN_INSTALL_VERSION_CODE" == "284" ]] || fail "min code pin"
[[ "$ARDTT_MIN_INSTALL_VERSION_NAME" == "0.5.265" ]] || fail "min name pin"
ok "pins 0.5.265 / 284"

if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi
echo "apk-gate tests passed"
