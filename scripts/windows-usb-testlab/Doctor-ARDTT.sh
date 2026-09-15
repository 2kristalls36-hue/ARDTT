#!/usr/bin/env bash
# Doctor: Linux/WSL toolchain, optional ADB, lab files.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/lib/common.sh"
ardtt_load_versions

JSON_OUT=""
TIMEOUT_SEC=20
ADB_BIN="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --json-out) JSON_OUT=$2; shift 2 ;;
    --timeout) TIMEOUT_SEC=$2; shift 2 ;;
    --adb) ADB_BIN=$2; shift 2 ;;
    --serial) SERIAL=$2; shift 2 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK_HOME="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/${NDK_VERSION}}"
if [[ -x "$SDK_ROOT/platform-tools/adb" ]]; then
  ardtt_prepend_path "$SDK_ROOT/platform-tools"
  if [[ "$ADB_BIN" == "adb" ]]; then
    ADB_BIN="$SDK_ROOT/platform-tools/adb"
  fi
fi
GO_BIN="$(command -v go || true)"
if [[ -z "$GO_BIN" && -x "$HOME/ardtt-testlab/tools/go/bin/go" ]]; then
  GO_BIN="$HOME/ardtt-testlab/tools/go/bin/go"
fi

CHECKS_FILE="$(mktemp)"
trap 'rm -f "$CHECKS_FILE"' EXIT
add_check() {
  local name=$1 status=$2 detail=$3
  python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"status":sys.argv[2],"detail":sys.argv[3]}, ensure_ascii=False))' "$name" "$status" "$detail" >>"$CHECKS_FILE"
}

have_java=0
if command -v java >/dev/null; then
  jv="$(java -version 2>&1 | head -1 | tr -d '\r')"
  add_check "java" "PASS" "$jv"
  have_java=1
else
  add_check "java" "FAIL" "java not on PATH"
fi

if [[ -n "$GO_BIN" ]]; then
  add_check "go" "PASS" "$("$GO_BIN" version | tr -d '\r')"
else
  add_check "go" "FAIL" "go not found"
fi

if [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  add_check "sdkmanager" "PASS" "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
else
  add_check "sdkmanager" "FAIL" "missing $SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
fi

if [[ -d "$SDK_ROOT/platforms/android-${COMPILE_SDK}" ]]; then
  add_check "platform" "PASS" "android-${COMPILE_SDK}"
else
  add_check "platform" "FAIL" "no platforms/android-${COMPILE_SDK}"
fi

if [[ -d "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
  add_check "ndk" "PASS" "$NDK_HOME"
else
  add_check "ndk" "FAIL" "NDK linux-x86_64 missing at $NDK_HOME"
fi

clang="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${MIN_SDK}-clang"
if [[ -x "$clang" ]]; then
  add_check "clang-arm64" "PASS" "$clang"
else
  add_check "clang-arm64" "FAIL" "$clang"
fi

if [[ -d "$SDK_ROOT/cmake/${CMAKE_VERSION}" || -d "$SDK_ROOT/cmake/${CMAKE_VERSION}.0" ]]; then
  add_check "cmake-sdk" "PASS" "cmake ${CMAKE_VERSION}"
else
  add_check "cmake-sdk" "FAIL" "cmake ${CMAKE_VERSION} not installed"
fi

if [[ -x "$HERE/../../android/gradlew" ]]; then
  add_check "gradlew" "PASS" "$HERE/../../android/gradlew"
else
  add_check "gradlew" "FAIL" "wrapper missing"
fi

# ADB / USB are optional on Linux cloud
adb_status="BLOCKED"
adb_detail="adb not probed"
if command -v "$ADB_BIN" >/dev/null 2>&1 || [[ -x "$ADB_BIN" ]]; then
  if timeout "$TIMEOUT_SEC" "$ADB_BIN" version >/dev/null 2>&1; then
    adb_status="PASS"
    adb_detail="$("$ADB_BIN" version | head -1 | tr -d '\r')"
    if timeout "$TIMEOUT_SEC" "$ADB_BIN" ${SERIAL:+-s "$SERIAL"} devices >/tmp/ardtt-adb-devices.txt 2>/tmp/ardtt-adb-devices.err; then
      if grep -E $'\tdevice(\s|$)' /tmp/ardtt-adb-devices.txt >/dev/null; then
        add_check "usb-device" "PASS" "$(tr -d '\r' </tmp/ardtt-adb-devices.txt | tail -n +2 | head -1)"
      else
        add_check "usb-device" "BLOCKED" "no device in state device (unauthorized/offline/missing)"
      fi
    else
      add_check "usb-device" "BLOCKED" "adb devices timed out"
    fi
  else
    adb_status="FAIL"
    adb_detail="adb version failed/timeout"
  fi
else
  adb_status="BLOCKED"
  adb_detail="adb binary not found — expected on Windows host, not in this Linux cloud"
  add_check "usb-device" "BLOCKED" "no USB from this environment"
fi
add_check "adb" "$adb_status" "$adb_detail"

host="$(uname -s)-$(uname -m)"
if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
  add_check "linux-x86_64-host" "FAIL" "$host native NDK host tag is linux-x86_64"
else
  add_check "linux-x86_64-host" "PASS" "$host"
fi

python3 - "$CHECKS_FILE" "$JSON_OUT" "$host" <<'PY'
import json, sys, datetime
checks = []
with open(sys.argv[1], encoding="utf-8") as fh:
    for line in fh:
        line = line.strip()
        if line:
            checks.append(json.loads(line))
fail = any(c["status"] == "FAIL" for c in checks)
blocked_only = (not fail) and any(c["status"] == "BLOCKED" for c in checks)
summary = "FAIL" if fail else ("BLOCKED" if blocked_only else "PASS")
doc = {
  "schema": "ardtt-lab-doctor/v1",
  "checkedAtUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
  "host": sys.argv[3],
  "summary": summary,
  "checks": checks,
}
text = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
out = sys.argv[2]
if out:
    open(out, "w", encoding="utf-8").write(text)
print(text)
if summary == "FAIL":
    raise SystemExit(1)
PY
