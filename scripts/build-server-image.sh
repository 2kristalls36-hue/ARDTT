#!/usr/bin/env bash
# Build the unified ARDTT image for one architecture (used in CI and by pack-server-package.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
ARCH="${1:-${ARDTT_PACKAGE_ARCH:-}}"
if [ -z "$ARCH" ]; then
  case "$(uname -m)" in
    x86_64|amd64) ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
    *) echo "usage: $0 amd64|arm64" >&2; exit 1 ;;
  esac
fi
COMMIT="${ARDTT_GIT_COMMIT:-$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
IMAGE="${ARDTT_IMAGE:-ardtt/server:${VER}}"

docker build \
  --platform "linux/${ARCH}" \
  --build-arg "ARDTT_DEPLOY_VERSION=${VER}" \
  --build-arg "ARDTT_GIT_COMMIT=${COMMIT}" \
  --build-arg "TARGETARCH=${ARCH}" \
  -t "$IMAGE" \
  -t "ardtt/server:${VER}-${ARCH}" \
  "$ROOT/server"

docker image inspect -f '{{.Id}} {{.Architecture}} {{index .Config.Labels "com.ardtt.deploy-version"}}' "$IMAGE"
