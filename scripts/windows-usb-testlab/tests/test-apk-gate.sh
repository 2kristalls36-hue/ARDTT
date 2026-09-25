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

if ardtt_apk_artifact_looks_current "ardtt-0.5.265-51cf7ce-arm64-v8a"; then
  fail "51cf7ce must stay rejected even if versionName looks new"
else
  ok "51cf7ce name rejected"
fi

[[ "$ARDTT_REJECT_APK_SHA256" == "c6e9a361d1f12a6d943c9babbfdefdb37827f95c9d18a07a1e76251785ea20fd" ]] || fail "reject hash pin"
[[ "$ARDTT_PREVIEW_APK_SHA256" == "8ba45dd66a13bafeb4faec3ee8a114a0d31c35e0c94a06141637929ca34d0642" ]] || fail "preview hash pin"
[[ "$ARDTT_MIN_INSTALL_VERSION_CODE" == "284" ]] || fail "min code pin"
[[ "$ARDTT_MIN_INSTALL_VERSION_NAME" == "0.5.265" ]] || fail "min name pin"
ok "pins 0.5.265 / 284 and 51cf7ce blacklist"

if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi
echo "apk-gate tests passed"
