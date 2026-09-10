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
if [ -f "$STAGE/images/layout.json" ]; then
  test -d "$STAGE/images/layers"
  test -f "$STAGE/scripts/assemble-docker-save.py"
  echo "OK layered image layout"
elif [ -f "$STAGE/images/ardtt.tar" ]; then
  echo "OK legacy images/ardtt.tar"
else
  echo "missing images/layout.json and images/ardtt.tar" >&2
  exit 1
fi
test -f "$STAGE/vendor/docker.tgz"
# Do not `tar -tzf | grep -q`: grep -q closes the pipe and tar's SIGPIPE
# fails the script under pipefail even when dockerd is present.
python3 - "$STAGE/vendor/docker.tgz" <<'PY'
import sys, tarfile
path = sys.argv[1]
with tarfile.open(path, "r:gz") as tf:
    names = tf.getnames()
if "docker/dockerd" not in names:
    raise SystemExit("vendor/docker.tgz must contain docker/dockerd")
print("OK vendor/docker.tgz dockerd")
PY
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
if [ -f "$STAGE/images/layout.json" ]; then
  python3 "$STAGE/scripts/assemble-docker-save.py" "$STAGE/images" | docker load
else
  docker load -i "$STAGE/images/ardtt.tar"
fi
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
