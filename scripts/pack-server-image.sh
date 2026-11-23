#!/usr/bin/env bash
# Deploy v2 release asset: docker save of the built image, gzip.
#   scripts/pack-server-image.sh amd64|arm64 [outdir]
# → <outdir>/ardtt-server-<ver>-linux-<arch>.image.tar.gz (+ .sha256)
# The image must be built first (scripts/build-server-image.sh <arch>).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCH="${1:?usage: $0 amd64|arm64 [outdir]}"
OUT="${2:-$ROOT/dist}"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
IMAGE="ardtt/server:${VER}"
case "$ARCH" in amd64|arm64) ;; *) echo "arch must be amd64 or arm64" >&2; exit 1 ;; esac

got="$(docker image inspect -f '{{.Architecture}} {{index .Config.Labels "com.ardtt.deploy-version"}} {{index .Config.Labels "com.ardtt.owner"}}' "$IMAGE")"
[ "$got" = "$ARCH $VER ardtt" ] || { echo "$IMAGE: expected «$ARCH $VER ardtt», got «$got»" >&2; exit 1; }

mkdir -p "$OUT"
file="$OUT/ardtt-server-${VER}-linux-${ARCH}.image.tar.gz"
# Only the version tag: the installer loads it and runs ardtt/server:<ver>.
docker save "$IMAGE" | gzip -9 >"$file.partial"
mv -f "$file.partial" "$file"
(cd "$OUT" && sha256sum "$(basename "$file")" >"$(basename "$file").sha256")
ls -l "$file"
