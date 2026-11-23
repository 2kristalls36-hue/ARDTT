#!/usr/bin/env bash
# Deploy v2: attach the server assets of this commit to a GitHub Release.
#   scripts/publish-server-release.sh --tag vX.Y.Z --from-dir dist [--dry-run]
# Uploads ardtt-server-<ver>-linux-{amd64,arm64}.image.tar.gz and
# ardtt-deploy.sh, and merges their sums into SHA256SUMS-server.txt.
# Creates the Release when the Android workflow has not yet (both run on tags).
# Never overwrites an image that is already attached.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
TAG="" FROM="" DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --from-dir) FROM="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 1 ;;
  esac
done
[ -n "$TAG" ] && [ -d "$FROM" ] || { echo "usage: $0 --tag vX.Y.Z --from-dir DIR [--dry-run]" >&2; exit 1; }

W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
assets=()
for arch in amd64 arm64; do
  f="$(find "$FROM" -name "ardtt-server-${VER}-linux-${arch}.image.tar.gz" -type f | head -n 1)"
  [ -n "$f" ] || { echo "missing image for $arch under $FROM" >&2; exit 1; }
  cp -f "$f" "$W/"
  assets+=("$W/$(basename "$f")")
done
cp -f "$ROOT/server/deploy/ardtt-deploy.sh" "$W/ardtt-deploy.sh"
assets+=("$W/ardtt-deploy.sh")

run() { if [ "$DRY" = 1 ]; then echo "+ $*"; else "$@"; fi; }

if ! gh release view "$TAG" >/dev/null 2>&1; then
  # Android build may create it at the same moment; losing that race is fine.
  run gh release create "$TAG" --title "ARDTT ${TAG}" --notes "ARDTT ${TAG#v} (stack ${VER})" \
      --target "${GITHUB_SHA:-HEAD}" \
    || gh release view "$TAG" >/dev/null 2>&1 \
    || { echo "cannot create release $TAG" >&2; exit 1; }
fi
existing="$(gh release view "$TAG" --json assets -q '.assets[].name' 2>/dev/null || true)"

# What to upload: an image already attached stays as it is (a later push to
# main without a DEPLOY_VERSION bump); the installer is always refreshed.
upload=()
for a in "${assets[@]}"; do
  name="$(basename "$a")"
  if grep -qx "$name" <<<"$existing" && [ "$name" != ardtt-deploy.sh ]; then
    echo "$name is already attached to $TAG — kept as is" >&2
  else
    upload+=("$a")
  fi
done

# Sums: lines of the files we upload are replaced, all other lines (including
# images kept as they are) stay exactly as published.
: >"$W/SHA256SUMS-server.txt"
if grep -qx 'SHA256SUMS-server.txt' <<<"$existing"; then
  gh release download "$TAG" -p SHA256SUMS-server.txt -O "$W/old-sums" 2>/dev/null || true
fi
up_names=" "
for a in ${upload[@]+"${upload[@]}"}; do up_names="$up_names$(basename "$a") "; done
if [ -f "$W/old-sums" ]; then
  while read -r sum name; do
    name="${name#\*}"; name="${name#./}"
    [ -n "$name" ] || continue
    case "$up_names" in *" $name "*) continue ;; esac
    printf '%s  %s\n' "$sum" "$name" >>"$W/SHA256SUMS-server.txt"
  done <"$W/old-sums"
fi
for a in ${upload[@]+"${upload[@]}"}; do (cd "$W" && sha256sum "$(basename "$a")") >>"$W/SHA256SUMS-server.txt"; done

for a in ${upload[@]+"${upload[@]}"}; do
  run gh release upload "$TAG" "$a" --clobber
done
run gh release upload "$TAG" "$W/SHA256SUMS-server.txt" --clobber
echo "published stack ${VER} to ${TAG}"
