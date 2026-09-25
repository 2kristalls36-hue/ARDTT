#!/usr/bin/env bash
# Verify versions.env pins match the Android sources they describe.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$LAB/../.." && pwd)"
# shellcheck disable=SC1091
source "$LAB/lib/common.sh"
ardtt_load_versions

FAIL=0
fail() { echo "FAIL: $*" >&2; FAIL=1; }
ok() { echo "OK $*"; }

[[ -f "$LAB/versions.env" ]] || fail "versions.env missing"

agp="$(grep -E 'id\("com.android.application"\) version' "$ROOT/android/build.gradle.kts" | head -1 | sed -n 's/.*"\([^"]*\)".*/\1/p')"
[[ "$agp" == "$AGP_VERSION" ]] || fail "AGP $agp != $AGP_VERSION"
ok "AGP $AGP_VERSION"

gradle="$(sed -n 's/^distributionUrl=.*gradle-\([0-9.]*\)-bin.zip/\1/p' "$ROOT/android/gradle/wrapper/gradle-wrapper.properties")"
[[ "$gradle" == "$GRADLE_WRAPPER" ]] || fail "Gradle wrapper $gradle != $GRADLE_WRAPPER"
ok "Gradle $GRADLE_WRAPPER"

ndk="$(sed -n 's/.*ndkVersion = "\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
[[ "$ndk" == "$NDK_VERSION" ]] || fail "NDK $ndk != $NDK_VERSION"
ok "NDK $NDK_VERSION"

app_id="$(sed -n 's/.*applicationId = "\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
[[ "$app_id" == "$ARDTT_PACKAGE" ]] || fail "applicationId $app_id != $ARDTT_PACKAGE"
ok "package $ARDTT_PACKAGE"

vc="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
vn="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
[[ "$vc" == "$ARDTT_MIN_INSTALL_VERSION_CODE" ]] || fail "versionCode $vc != $ARDTT_MIN_INSTALL_VERSION_CODE"
[[ "$vn" == "$ARDTT_MIN_INSTALL_VERSION_NAME" ]] || fail "versionName $vn != $ARDTT_MIN_INSTALL_VERSION_NAME"
ok "install gate $vn / $vc"

mk_ver="$(sed -n 's/^GO_VERSION := //p' "$ROOT/android/tunnel/tools/libwg-go/Makefile" | head -1)"
mk_hash="$(sed -n 's/^GO_HASH_linux-amd64 := //p' "$ROOT/android/tunnel/tools/libwg-go/Makefile" | head -1)"
[[ "$mk_ver" == "$GO_LIBWG_VERSION" ]] || fail "libwg-go GO_VERSION $mk_ver != $GO_LIBWG_VERSION"
[[ "$mk_hash" == "$GO_LIBWG_SHA256" ]] || fail "libwg-go hash mismatch vs versions.env"
ok "libwg-go Go $GO_LIBWG_VERSION hash pinned"

go_mod="$(sed -n 's/^go //p' "$ROOT/android/go_client/go.mod" | head -1)"
[[ "$go_mod" == "1.25.0" ]] || fail "go_client go.mod $go_mod"
ok "go_client go.mod $go_mod (bootstrap $GO_BOOTSTRAP_VERSION)"

host_tag_check="$(grep -F 'linux-x86_64' "$ROOT/scripts/build-bypass-client.sh" | head -1 || true)"
[[ -n "$host_tag_check" ]] || fail "build-bypass-client.sh no longer pins linux-x86_64"
ok "native host tag still linux-x86_64"

if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi
echo "versions pin tests passed"
