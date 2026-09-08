#!/usr/bin/env bash
# Compatibility wrapper. The VPS payload is now ardtt-server-<ver>-linux-<arch>.tar.gz
# (docker save image). This script only keeps assets/deploy/DEPLOY_VERSION in lockstep.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$ROOT/server/DEPLOY_VERSION"
[ -f "$VERSION_FILE" ] || { echo "missing $VERSION_FILE" >&2; exit 1; }
VER="$(tr -d '[:space:]' < "$VERSION_FILE")"
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
fi

if [ "${ARDTT_PACK_STACK_LEGACY:-0}" = "1" ]; then
  echo "ardtt-stack-*.tar.gz is not a production payload (no image). Use scripts/pack-server-package.sh" >&2
  exit 1
fi

echo "Synced $ASSET_DIR/DEPLOY_VERSION ($VER)"
echo "Production package: scripts/build-server-image.sh && scripts/pack-server-package.sh"
