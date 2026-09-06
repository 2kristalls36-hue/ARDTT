#!/usr/bin/env bash
# Pack server/ into ardtt-stack-<DEPLOY_VERSION>.tar.gz for GitHub Releases.
# The Android client downloads this archive (or a GitHub source tarball) at
# deploy time — it is no longer embedded in the APK.
#
# Canonical sources:
#   server/install.sh
#   server/DEPLOY_VERSION
#   server/{Dockerfile,entrypoint.sh,docker-compose.yml,provision,direct,bypass,dns,warp,telemetry-upload,…}
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

INSTALLER="$ROOT/server/install.sh"
VERSION_FILE="$ROOT/server/DEPLOY_VERSION"
[ -f "$INSTALLER" ] || { echo "missing $INSTALLER" >&2; exit 1; }
[ -f "$VERSION_FILE" ] || { echo "missing $VERSION_FILE" >&2; exit 1; }

VER="$(tr -d '[:space:]' < "$VERSION_FILE")"
[ -n "$VER" ] || { echo "empty DEPLOY_VERSION" >&2; exit 1; }

ASSET_DIR="$ROOT/android/app/src/main/assets/deploy"
mkdir -p "$ASSET_DIR"
cp -f "$VERSION_FILE" "$ASSET_DIR/DEPLOY_VERSION"

FALLBACK="$(
  sed -n 's/.*FALLBACK_VERSION = "\(.*\)".*/\1/p' \
    "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployBundle.kt" \
    | head -1
)"
if [ -n "$FALLBACK" ] && [ "$FALLBACK" != "$VER" ]; then
  echo "WARNING: DeployBundle.FALLBACK_VERSION=$FALLBACK but server/DEPLOY_VERSION=$VER" >&2
  echo "         Update FALLBACK_VERSION so the client compares /health correctly." >&2
fi

OUT="${1:-}"
if [ -z "$OUT" ]; then
  mkdir -p "$ROOT/dist"
  OUT="$ROOT/dist/ardtt-stack-${VER}.tar.gz"
else
  mkdir -p "$(dirname "$OUT")"
fi

TMP="$(mktemp)"
tar -czf "$TMP" \
  -C "$ROOT/server" \
  --exclude='data' \
  --exclude='*.tmp' \
  --exclude='__pycache__' \
  --exclude='*.pyc' \
  docker-compose.yml Dockerfile entrypoint.sh .env.example DEPLOY_VERSION README.md install.sh scripts \
  provision direct bypass dns warp telemetry-upload
cp -f "$TMP" "$OUT"
rm -f "$TMP"

ls -lh "$OUT" "$ASSET_DIR/DEPLOY_VERSION"
echo "Packed $OUT (deploy version $VER)"
