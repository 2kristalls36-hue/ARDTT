#!/usr/bin/env bash
# Build the unified ARDTT image for one architecture (used in CI and by pack-server-package.sh).
#
# Partial deploy only pays off when unchanged Dockerfile steps produce
# byte-identical layers between builds. BuildKit's cache guarantees that for
# every step whose inputs did not change, so CI passes a persistent cache:
#   ARDTT_BUILD_CACHE_FROM=type=gha,scope=server-image-amd64
#   ARDTT_BUILD_CACHE_TO=type=gha,scope=server-image-amd64,mode=max
# Both are optional; a plain local build works without them.
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

args=(
  --platform "linux/${ARCH}"
  --build-arg "ARDTT_DEPLOY_VERSION=${VER}"
  --build-arg "ARDTT_GIT_COMMIT=${COMMIT}"
  --build-arg "TARGETARCH=${ARCH}"
  -t "$IMAGE"
  -t "ardtt/server:${VER}-${ARCH}"
)
cache=()
[ -n "${ARDTT_BUILD_CACHE_FROM:-}" ] && cache+=(--cache-from "$ARDTT_BUILD_CACHE_FROM")
[ -n "${ARDTT_BUILD_CACHE_TO:-}" ] && cache+=(--cache-to "$ARDTT_BUILD_CACHE_TO")

if docker buildx version >/dev/null 2>&1; then
  # --load: the packed archive is docker save of the local image, never a registry push.
  docker buildx build --load ${cache[@]+"${cache[@]}"} "${args[@]}" "$ROOT/server"
else
  [ "${#cache[@]}" -eq 0 ] || echo "warning: buildx missing, build cache options ignored" >&2
  docker build "${args[@]}" "$ROOT/server"
fi

docker image inspect -f '{{.Id}} {{.Architecture}} {{index .Config.Labels "com.ardtt.deploy-version"}}' "$IMAGE"
