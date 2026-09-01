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

APK=app/build/outputs/apk/release/app-release.apk
AAB=app/build/outputs/bundle/release/app-release.aab
ls -lh "$APK" "$AAB"
sha256sum "$APK"
echo "APK=$ROOT_DIR/android/$APK"
echo "AAB=$ROOT_DIR/android/$AAB"
