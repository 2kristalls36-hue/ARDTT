#!/usr/bin/env bash
# Verify a packed ardtt-server-*.tar.gz: outer digest, safe extract, manifest,
# docker load, image arch, compose config. Does not pull or build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:-}"
EXPECT_SHA="${2:-}"
[ -n "$PKG" ] && [ -f "$PKG" ] || { echo "usage: $0 package.tar.gz [sha256]" >&2; exit 1; }

if [ -z "$EXPECT_SHA" ] && [ -f "${PKG}.sha256" ]; then
  EXPECT_SHA="$(tr -d '[:space:]' < "${PKG}.sha256")"
fi
if [ -n "$EXPECT_SHA" ]; then
  got="$(sha256sum "$PKG" | awk '{print $1}')"
  [ "$got" = "$EXPECT_SHA" ] || { echo "outer SHA-256 mismatch" >&2; exit 1; }
  echo "OK outer sha256"
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
python3 "$ROOT/scripts/safe-extract-package.py" "$PKG" "$STAGE"
test -f "$STAGE/manifest.json"
test -f "$STAGE/install.sh"
test -f "$STAGE/docker-compose.yml"
test -f "$STAGE/images/ardtt.tar"
test -f "$STAGE/vendor/docker.tgz"
tar -tzf "$STAGE/vendor/docker.tgz" | grep -q 'docker/dockerd' || {
  echo "vendor/docker.tgz must contain docker/dockerd" >&2
  exit 1
}
if grep -qE '^[[:space:]]*build:' "$STAGE/docker-compose.yml"; then
  echo "production compose must not contain build:" >&2
  exit 1
fi
python3 - "$STAGE/manifest.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
assert d["format"]=="ardtt-server-v1"
assert d["arch"] in ("amd64","arm64")
assert d.get("image",{}).get("tag")
print("OK manifest", d["deployVersion"], d["arch"], d["image"]["tag"])
PY

if ! command -v docker >/dev/null 2>&1; then
  echo "SKIP docker load (no docker CLI)"
  exit 0
fi
if ! docker info >/dev/null 2>&1; then
  echo "SKIP docker load (engine down)"
  exit 0
fi

IMAGE_TAG="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["image"]["tag"])' "$STAGE/manifest.json")"
WANT_ARCH="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["arch"])' "$STAGE/manifest.json")"
docker load -i "$STAGE/images/ardtt.tar"
got_arch="$(docker image inspect -f '{{.Architecture}}' "$IMAGE_TAG")"
[ "$got_arch" = "$WANT_ARCH" ] || { echo "image arch $got_arch != $WANT_ARCH" >&2; exit 1; }
echo "OK docker load $IMAGE_TAG ($got_arch)"

# Compose must resolve without pulling. Missing env is filled with placeholders.
(
  cd "$STAGE"
  export ARDTT_IMAGE="$IMAGE_TAG"
  export ARDTT_PUBLIC_HOST=127.0.0.1
  export ARDTT_INSTANCE_ID=verify
  export ARDTT_CONTAINER_NAME=ardtt-verify
  export ARDTT_NETWORK_NAME=ardtt-verify
  export ARDTT_BRIDGE_SUBNET=172.28.250.0/24
  export ARDTT_DATA_DIR="$STAGE/data"
  export ARDTT_LOG_DIR="$STAGE/logs"
  mkdir -p data logs
  docker compose -f docker-compose.yml config >/dev/null
)
echo "OK compose config --no-build"
echo "OK verify $PKG"
