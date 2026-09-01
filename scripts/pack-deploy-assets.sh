#!/usr/bin/env bash
# Refresh Android deploy assets from server/.
#
# Canonical sources:
#   server/install.sh
#   server/DEPLOY_VERSION
#   server/{docker-compose.yml,provision,direct,bypass,dns,warp,telemetry-upload,…}
#
# aapt/aapt2 may unpack *.gz and drop the suffix. Store the archive as
# stack.tar.gz.bin so the gzipped bytes survive packaging. DeployEngine also
# accepts stack.tar.gz / stack.tar as fallbacks.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/assets/deploy"
mkdir -p "$OUT"

INSTALLER="$ROOT/server/install.sh"
VERSION_FILE="$ROOT/server/DEPLOY_VERSION"
[ -f "$INSTALLER" ] || { echo "missing $INSTALLER" >&2; exit 1; }
[ -f "$VERSION_FILE" ] || { echo "missing $VERSION_FILE" >&2; exit 1; }

VER="$(tr -d '[:space:]' < "$VERSION_FILE")"
[ -n "$VER" ] || { echo "empty DEPLOY_VERSION" >&2; exit 1; }

cp -f "$INSTALLER" "$OUT/install.sh"
cp -f "$VERSION_FILE" "$OUT/DEPLOY_VERSION"
chmod +x "$OUT/install.sh"

FALLBACK="$(
  sed -n 's/.*FALLBACK_VERSION = "\(.*\)".*/\1/p' \
    "$ROOT/android/app/src/main/java/com/nonamevpn/app/deploy/DeployBundle.kt" \
    | head -1
)"
if [ -n "$FALLBACK" ] && [ "$FALLBACK" != "$VER" ]; then
  echo "WARNING: DeployBundle.FALLBACK_VERSION=$FALLBACK but server/DEPLOY_VERSION=$VER" >&2
  echo "         Update FALLBACK_VERSION so APKs without the asset still compare correctly." >&2
fi

TMP="$(mktemp)"
tar -czf "$TMP" \
  -C "$ROOT/server" \
  --exclude='data' \
  --exclude='*.tmp' \
  --exclude='__pycache__' \
  --exclude='*.pyc' \
  docker-compose.yml .env.example DEPLOY_VERSION README.md install.sh scripts \
  provision direct bypass dns warp telemetry-upload
cp -f "$TMP" "$OUT/stack.tar.gz.bin"
cp -f "$TMP" "$OUT/stack.tar.gz"
rm -f "$TMP"

ls -lh "$OUT/stack.tar.gz.bin" "$OUT/stack.tar.gz" "$OUT/install.sh" "$OUT/DEPLOY_VERSION"
echo "Updated $OUT (deploy version $VER)"
