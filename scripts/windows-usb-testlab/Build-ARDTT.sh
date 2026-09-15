#!/usr/bin/env bash
# Build ARDTT native libs + APK in a Linux checkout. Records build-manifest.json.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/lib/common.sh"
ardtt_load_versions

CHECKOUT=""
ABI="${PREVIEW_ABI}"
VARIANT="debug"
FORCE_NATIVE=0
SKIP_UNIT=0
SKIP_GO=0
OUT_DIR=""
REQUESTED_SHA="${ARDTT_REQUESTED_SHA}"

usage() {
  cat <<'EOF'
Usage: Build-ARDTT.sh [options]
  --checkout DIR       Linux git checkout (default: repo containing this script)
  --abi ABI            default arm64-v8a
  --variant debug|release
  --force-native       rebuild libclient.so even if present
  --skip-unit-tests
  --skip-go-tests
  --out DIR            where to copy APK + build-manifest.json
  --requested-sha SHA  expected commit; mismatch is recorded, not silently ignored
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --checkout) CHECKOUT=$2; shift 2 ;;
    --abi) ABI=$2; shift 2 ;;
    --variant) VARIANT=$2; shift 2 ;;
    --force-native) FORCE_NATIVE=1; shift ;;
    --skip-unit-tests) SKIP_UNIT=1; shift ;;
    --skip-go-tests) SKIP_GO=1; shift ;;
    --out) OUT_DIR=$2; shift 2 ;;
    --requested-sha) REQUESTED_SHA=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done

if [[ -z "$CHECKOUT" ]]; then
  CHECKOUT="$(cd "$HERE/../.." && pwd)"
fi
[[ -d "$CHECKOUT/.git" ]] || ardtt_die "не git checkout: $CHECKOUT"
[[ -f "$CHECKOUT/android/gradlew" ]] || ardtt_die "нет android/gradlew в $CHECKOUT"

case "$VARIANT" in
  debug|release) ;;
  *) ardtt_die "--variant должен быть debug или release" ;;
esac

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK_HOME="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/${NDK_VERSION}}"
[[ -d "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64" ]] || ardtt_die "NDK linux-x86_64 не найден: $NDK_HOME"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_NDK_HOME="$NDK_HOME"
export ANDROID_NDK_ROOT="$NDK_HOME"

if command -v go >/dev/null 2>&1; then
  :
elif [[ -x "${ARDTT_TOOLS_ROOT:-$HOME/ardtt-testlab/tools}/go/bin/go" ]]; then
  ardtt_prepend_path "${ARDTT_TOOLS_ROOT:-$HOME/ardtt-testlab/tools}/go/bin"
fi
ardtt_require_cmd go
ardtt_require_cmd java
ardtt_require_cmd unzip
ardtt_require_cmd python3

cd "$CHECKOUT"
actual_sha="$(git rev-parse HEAD)"
actual_ref="$(git rev-parse --abbrev-ref HEAD || true)"
status_porcelain="$(git status --porcelain)"
tracked_diff="$(git diff HEAD)"
untracked="$(git ls-files --others --exclude-standard)"
diff_sha=""
if [[ -n "$status_porcelain" ]]; then
  diff_sha="$(printf '%s\n' "$status_porcelain" | sha256sum | awk '{print $1}')"
fi

LOG_DIR="${OUT_DIR:-$CHECKOUT/android/app/build/ardtt-lab}"
mkdir -p "$LOG_DIR"
BUILD_LOG="$LOG_DIR/build.log"
: >"$BUILD_LOG"
exec > >(tee -a "$BUILD_LOG") 2>&1

ardtt_log INFO "checkout=$CHECKOUT sha=$actual_sha requested=$REQUESTED_SHA abi=$ABI variant=$VARIANT"

if [[ -n "$REQUESTED_SHA" && "$actual_sha" != "$REQUESTED_SHA" ]]; then
  ardtt_log WARN "SHA checkout ($actual_sha) != requested ($REQUESTED_SHA); манифест запишет оба"
fi

printf 'sdk.dir=%s\n' "$SDK_ROOT" >"$CHECKOUT/android/local.properties"

SO_DIR="$CHECKOUT/android/app/src/main/jniLibs/$ABI"
SO_FILE="$SO_DIR/libclient.so"
STAMP="$SO_DIR/.ardtt-native-sha"
need_native=0
if [[ "$FORCE_NATIVE" -eq 1 ]]; then
  need_native=1
elif [[ ! -f "$SO_FILE" ]]; then
  need_native=1
elif [[ ! -f "$STAMP" ]]; then
  need_native=1
elif [[ "$(cat "$STAMP")" != "$actual_sha $ABI" ]]; then
  need_native=1
fi

if [[ "$need_native" -eq 1 ]]; then
  ardtt_log INFO "building libclient.so for $ABI (force=$FORCE_NATIVE)"
  rm -f "$SO_FILE"
  bash "$CHECKOUT/scripts/build-bypass-client.sh" "$ABI"
  mkdir -p "$SO_DIR"
  printf '%s %s\n' "$actual_sha" "$ABI" >"$STAMP"
else
  ardtt_log INFO "reusing libclient.so for $actual_sha $ABI"
fi
[[ -f "$SO_FILE" ]] || ardtt_die "нет $SO_FILE после native build"

if [[ "$SKIP_UNIT" -eq 0 ]]; then
  ardtt_log INFO "gradle :app:testDebugUnitTest"
  (
    cd "$CHECKOUT/android"
    ./gradlew :app:testDebugUnitTest --no-daemon
  )
fi

if [[ "$SKIP_GO" -eq 0 ]]; then
  ardtt_log INFO "go test android/go_client"
  (
    cd "$CHECKOUT/android/go_client"
    go test ./...
  )
fi

gradle_task=":app:assembleDebug"
if [[ "$VARIANT" == "release" ]]; then
  gradle_task=":app:assembleRelease"
fi
ardtt_log INFO "gradle $gradle_task -PtargetAbis=$ABI"
(
  cd "$CHECKOUT/android"
  ./gradlew "$gradle_task" -PtargetAbis="$ABI" --no-daemon
)

METADATA="$CHECKOUT/android/app/build/outputs/apk/${VARIANT}/output-metadata.json"
[[ -f "$METADATA" ]] || ardtt_die "нет $METADATA"

APK="$(python3 - "$METADATA" "$ABI" "$CHECKOUT/android/app/build/outputs/apk/${VARIANT}" <<'PY'
import json, sys
from pathlib import Path
meta = json.loads(Path(sys.argv[1]).read_text())
want_abi = sys.argv[2]
base = Path(sys.argv[3])
chosen = None
for el in meta.get("elements", []):
    filters = el.get("filters") or []
    abis = [f.get("value") for f in filters if f.get("filterType") == "ABI"]
    output = el.get("outputFile")
    if not output:
        continue
    path = base / output
    if want_abi in abis or not abis:
        chosen = path
        if want_abi in abis:
            break
if chosen is None:
    raise SystemExit("APK for ABI not listed in output-metadata.json")
print(chosen)
PY
)"
[[ -f "$APK" ]] || ardtt_die "APK не найден: $APK"

python3 - "$APK" "$ABI" <<'PY'
import sys, zipfile
apk, abi = sys.argv[1], sys.argv[2]
need = {f"lib/{abi}/libclient.so", f"lib/{abi}/libwg-go.so"}
with zipfile.ZipFile(apk) as z:
    names = set(z.namelist())
missing = sorted(need - names)
if missing:
    raise SystemExit("APK missing native libs: " + ", ".join(missing))
print("native libs present:", ", ".join(sorted(need)))
PY

APK_SHA="$(ardtt_sha256_file "$APK")"
CERT_SHA=""
PACKAGE_NAME=""
VERSION_NAME=""
VERSION_CODE=""
AAPT="$SDK_ROOT/build-tools/${BUILD_TOOLS}/aapt"
AAPT2="$SDK_ROOT/build-tools/${BUILD_TOOLS}/aapt2"
APKSIGNER="$SDK_ROOT/build-tools/${BUILD_TOOLS}/apksigner"
if [[ -x "$AAPT" ]]; then
  badging="$("$AAPT" dump badging "$APK" || true)"
  PACKAGE_NAME="$(printf '%s\n' "$badging" | sed -n "s/.*package: name='\([^']*\)'.*/\1/p" | head -1)"
  VERSION_CODE="$(printf '%s\n' "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
  VERSION_NAME="$(printf '%s\n' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
elif [[ -x "$AAPT2" ]]; then
  ardtt_log WARN "aapt отсутствует, aapt2 dump не полностью заменяет badging"
fi
if [[ -x "$APKSIGNER" ]]; then
  cert_out="$("$APKSIGNER" verify --print-certs "$APK" || true)"
  CERT_SHA="$(printf '%s\n' "$cert_out" | sed -n 's/.*SHA-256 digest: *//p' | head -1 | tr '[:upper:]' '[:lower:]' | tr -d ' :')"
fi

if [[ -n "$OUT_DIR" ]]; then
  mkdir -p "$OUT_DIR"
  cp -f "$APK" "$OUT_DIR/"
fi

GO_VER="$(go version | awk '{print $3}')"
JAVA_VER="$(java -version 2>&1 | head -1 | tr -d '\r')"

MANIFEST_PATH="$LOG_DIR/build-manifest.json"
python3 - "$MANIFEST_PATH" <<PY
import json, os, datetime
manifest = {
  "schema": "ardtt-lab-build-manifest/v1",
  "repository": "https://github.com/2kristalls36-hue/ARDTT",
  "pr": int(os.environ.get("ARDTT_PR", "217") or 217),
  "branch": "$actual_ref",
  "requestedSha": "$REQUESTED_SHA",
  "checkoutSha": "$actual_sha",
  "localDiffPresent": bool("""${status_porcelain}""".strip()),
  "localDiffSha256": "$diff_sha" or None,
  "trackedDiffPresent": bool("""${tracked_diff}""".strip()),
  "untrackedPresent": bool("""${untracked}""".strip()),
  "jdk": """$JAVA_VER""",
  "go": "$GO_VER",
  "agp": "$AGP_VERSION",
  "gradleWrapper": "$GRADLE_WRAPPER",
  "compileSdk": int("$COMPILE_SDK"),
  "ndk": "$NDK_VERSION",
  "cmake": "$CMAKE_VERSION",
  "abi": "$ABI",
  "variant": "$VARIANT",
  "builtAtUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
  "apkPath": "$APK",
  "apkSha256": "$APK_SHA",
  "package": "$PACKAGE_NAME" or "$ARDTT_PACKAGE",
  "versionName": "$VERSION_NAME",
  "versionCode": "$VERSION_CODE",
  "signingCertSha256": "$CERT_SHA" or None,
  "ciRunId": None,
  "ciRunUrl": None,
  "nativeForced": bool($FORCE_NATIVE),
}
with open("$MANIFEST_PATH", "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("wrote", "$MANIFEST_PATH")
PY

if [[ -n "$OUT_DIR" && "$OUT_DIR" != "$LOG_DIR" ]]; then
  cp -f "$MANIFEST_PATH" "$OUT_DIR/build-manifest.json"
fi

ardtt_log INFO "APK $APK sha256=$APK_SHA"
printf '%s\n' "$APK"
