#!/usr/bin/env bash
# Build signed release APK (+ optional AAB). Requires android/keystore.properties.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f android/keystore.properties ]]; then
  echo "Missing android/keystore.properties — run scripts/fetch-release-keystore.sh" >&2
  exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.0.12077973}"

./scripts/build-bypass-client.sh
cd android
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
./gradlew :app:assembleRelease :app:bundleRelease --no-daemon

AAB=app/build/outputs/bundle/release/app-release.aab
shopt -s nullglob
apks=(app/build/outputs/apk/release/*.apk)
if (( ${#apks[@]} == 0 )); then
  echo "No release APKs under app/build/outputs/apk/release/" >&2
  exit 1
fi
ls -lh "${apks[@]}" "$AAB"
sha256sum "${apks[@]}"
# ABI splits emit app-<abi>-release.apk plus optional universal; prefer arm64, then universal.
APK=""
for candidate in \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  app/build/outputs/apk/release/app-universal-release.apk \
  app/build/outputs/apk/release/app-release.apk
do
  if [[ -f "$candidate" ]]; then
    APK="$candidate"
    break
  fi
done
APK="${APK:-${apks[0]}}"
echo "APK=$ROOT_DIR/android/$APK"
echo "AAB=$ROOT_DIR/android/$AAB"
