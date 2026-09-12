#!/usr/bin/env bash
# Put APKs from DIST_DIR onto GitHub Release TAG, or skip if that app version
# is already there. Attach of a newer server stack must not die because the
# Android artifact glob missed (race: latest green Android is still the
# previous versionName, or APKs were published by android-build.yml itself).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:-}"
VERSION="${2:-}"
DIST="${3:-}"
[ -n "$TAG" ] && [ -n "$VERSION" ] && [ -n "$DIST" ] || {
  echo "usage: $0 TAG VERSION DIST_DIR" >&2
  exit 1
}

apk_on_release() {
  gh release view "$TAG" --json assets --jq '.assets[].name' 2>/dev/null \
    | grep -qx "ardtt-${VERSION}-arm64-v8a.apk"
}

shopt -s nullglob
mapfile -t apks < <(find "$DIST" -type f -name "ardtt-${VERSION}-*.apk" | sort)

if [ "${#apks[@]}" -ge 1 ]; then
  arm64=""
  for apk in "${apks[@]}"; do
    [ "$(basename "$apk")" = "ardtt-${VERSION}-arm64-v8a.apk" ] && arm64="$apk"
  done
  [ -n "$arm64" ] || {
    echo "missing ardtt-${VERSION}-arm64-v8a.apk among: ${apks[*]}" >&2
    exit 1
  }
  dist_dir="$(dirname "$arm64")"
  python3 "$ROOT/scripts/rewrite-release-update-json.py" \
    "$TAG" "$VERSION" "${GITHUB_REPOSITORY:?}" "$dist_dir"
  (
    cd "$dist_dir"
    sha256sum ardtt-"${VERSION}"-*.apk ardtt-update.json > SHA256SUMS.txt
  )
  if gh release view "$TAG" >/dev/null 2>&1; then
    gh release upload "$TAG" \
      "$dist_dir"/ardtt-"${VERSION}"-*.apk \
      "$dist_dir"/ardtt-update.json \
      "$dist_dir"/SHA256SUMS.txt \
      --clobber
  else
    gh release create "$TAG" \
      "$dist_dir"/ardtt-"${VERSION}"-*.apk \
      "$dist_dir"/ardtt-update.json \
      "$dist_dir"/SHA256SUMS.txt \
      --title "ARDTT ${TAG}" --generate-notes --latest
  fi
  echo "APKs on https://github.com/${GITHUB_REPOSITORY}/releases/tag/${TAG}"
  exit 0
fi

echo "no APKs under ${DIST} for ${VERSION}"
ls -laR "$DIST" 2>/dev/null || true

if apk_on_release; then
  echo "ARDTT_INFO|APKs already on ${TAG}; skip APK upload"
  exit 0
fi

echo "need ardtt-${VERSION}-*.apk in ${DIST} or already on ${TAG}" >&2
exit 1
