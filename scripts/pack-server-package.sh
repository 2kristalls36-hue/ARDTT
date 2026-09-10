#!/usr/bin/env bash
# Build ardtt-server-<DEPLOY_VERSION>-linux-<ARCH>.tar.gz
# Requires a docker image already tagged (see scripts/build-server-image.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$ROOT/server/DEPLOY_VERSION"
[ -f "$VERSION_FILE" ] || { echo "missing $VERSION_FILE" >&2; exit 1; }
VER="$(tr -d '[:space:]' < "$VERSION_FILE")"
[ -n "$VER" ] || { echo "empty DEPLOY_VERSION" >&2; exit 1; }

ARCH="${ARDTT_PACKAGE_ARCH:-}"
if [ -z "$ARCH" ]; then
  case "$(uname -m)" in
    x86_64|amd64) ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
    *) echo "set ARDTT_PACKAGE_ARCH=amd64|arm64" >&2; exit 1 ;;
  esac
fi
COMMIT="${ARDTT_GIT_COMMIT:-$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
TAG="${ARDTT_RELEASE_TAG:-}"
IMAGE="${ARDTT_IMAGE:-ardtt/server:${VER}}"
OUT="${1:-}"
if [ -z "$OUT" ]; then
  mkdir -p "$ROOT/dist"
  OUT="$ROOT/dist/ardtt-server-${VER}-linux-${ARCH}.tar.gz"
fi
mkdir -p "$(dirname "$OUT")"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "missing image $IMAGE — build with scripts/build-server-image.sh" >&2
  exit 1
fi

IMAGE_ID="$(docker image inspect -f '{{.Id}}' "$IMAGE")"
IMAGE_ARCH="$(docker image inspect -f '{{.Architecture}}' "$IMAGE")"
if [ "$IMAGE_ARCH" != "$ARCH" ]; then
  echo "image arch $IMAGE_ARCH != package arch $ARCH" >&2
  exit 1
fi

ASSET_DIR="$ROOT/android/app/src/main/assets/deploy"
mkdir -p "$ASSET_DIR"
cp -f "$VERSION_FILE" "$ASSET_DIR/DEPLOY_VERSION"

LOCK="$ROOT/server/third-party.lock.json"
COMPOSE_VER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dockerComposeCli"]["version"])' "$LOCK")"
COMPOSE_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dockerComposeCli"]["sha256"][sys.argv[2]])' "$LOCK" "$ARCH")"
case "$ARCH" in
  amd64) UNAME_ARCH=x86_64 ;;
  arm64) UNAME_ARCH=aarch64 ;;
esac

ENGINE_VER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dockerEngineStatic"]["version"])' "$LOCK")"
ENGINE_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dockerEngineStatic"]["sha256"][sys.argv[2]])' "$LOCK" "$ARCH")"
ENGINE_UNAME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dockerEngineStatic"]["unameArch"][sys.argv[2]])' "$LOCK" "$ARCH")"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/images" "$STAGE/bin" "$STAGE/install-lib" "$STAGE/scripts" "$STAGE/vendor"

docker save -o "$STAGE/images/ardtt.tar" "$IMAGE"
test -s "$STAGE/images/ardtt.tar"
python3 "$ROOT/scripts/split-docker-save.py" "$STAGE/images/ardtt.tar" "$STAGE/images"
test -f "$STAGE/images/layout.json"
# Keep the installer lean: ship gzipped layers, not the monolithic save tar.
rm -f "$STAGE/images/ardtt.tar"
cp -f "$ROOT/scripts/assemble-docker-save.py" "$STAGE/scripts/assemble-docker-save.py"
cp -f "$ROOT/scripts/split-docker-save.py" "$STAGE/scripts/split-docker-save.py" 2>/dev/null || true
chmod 755 "$STAGE/scripts/assemble-docker-save.py"

COMPOSE_URL="https://github.com/docker/compose/releases/download/v${COMPOSE_VER}/docker-compose-linux-${UNAME_ARCH}"
curl -fsSL -o "$STAGE/bin/docker-compose" "$COMPOSE_URL"
echo "${COMPOSE_SHA}  $STAGE/bin/docker-compose" | sha256sum -c -
chmod 755 "$STAGE/bin/docker-compose"

ENGINE_URL="https://download.docker.com/linux/static/stable/${ENGINE_UNAME}/docker-${ENGINE_VER}.tgz"
curl -fsSL -o "$STAGE/vendor/docker.tgz" "$ENGINE_URL"
echo "${ENGINE_SHA}  $STAGE/vendor/docker.tgz" | sha256sum -c -
test -s "$STAGE/vendor/docker.tgz"

cp -f "$ROOT/server/install.sh" "$STAGE/install.sh"
cp -f "$ROOT/server/fetch-and-install.sh" "$STAGE/fetch-and-install.sh"
chmod 755 "$STAGE/fetch-and-install.sh"
cp -f "$ROOT/server/ready.sh" "$STAGE/ready.sh"
chmod 755 "$STAGE/ready.sh"
cp -a "$ROOT/server/install-lib/." "$STAGE/install-lib/"
cp -f "$ROOT/scripts/safe-extract-package.py" "$STAGE/scripts/safe-extract-package.py"
# Bootstrap copy for the Android app (cold install: phone SFTPs only this small script).
mkdir -p "$ASSET_DIR"
cp -f "$ROOT/server/fetch-and-install.sh" "$ASSET_DIR/fetch-and-install.sh"
cp -f "$ROOT/server/docker-compose.yml" "$STAGE/docker-compose.yml"
cp -f "$ROOT/server/docker-compose.exit.yml" "$STAGE/docker-compose.exit.yml"
cp -f "$ROOT/server/.env.example" "$STAGE/.env.example"
cp -f "$ROOT/server/DEPLOY_VERSION" "$STAGE/DEPLOY_VERSION"
cp -f "$ROOT/server/third-party.lock.json" "$STAGE/third-party.lock.json"
cat > "$STAGE/README.md" <<EOF
# ARDTT server package ${VER} (${ARCH})

This archive is the only software payload a VPS needs.

## Requires

- Linux ${ARCH}
- python3, iptables, /dev/net/tun
- systemd (to start bundled dockerd when Engine is missing)

This archive includes \`vendor/docker.tgz\` (Engine ${ENGINE_VER}) and
\`bin/docker-compose\`. The image is shipped as gzipped Docker layers
(\`images/layout.json\` + \`images/layers/\`) and streamed into \`docker load\`
on the VPS — no second full \`ardtt.tar\` is written. The installer unpacks
Engine only when \`docker info\` fails. It will **not** fetch a network Engine
installer, apt/dnf, or docker pull. A working Engine is left alone.

## Install

\`\`\`bash
# From the Android app, or:
install -d -m 755 /opt/ardtt/incoming
# copy this file to /opt/ardtt/incoming/ardtt-server-${VER}-linux-${ARCH}.tar.gz
export ARDTT_PUBLIC_HOST=YOUR_VPS_IP
export ARDTT_PACKAGE=/opt/ardtt/incoming/ardtt-server-${VER}-linux-${ARCH}.tar.gz
export ARDTT_PACKAGE_SHA256=\$(sha256sum "\$ARDTT_PACKAGE" | awk '{print \$1}')
# Prefer the digest published on the GitHub Release asset, not a self-hash
# computed after a tampered download.
tar -tzf "\$ARDTT_PACKAGE" >/dev/null
# After verifying SHA-256 against the release, extract and run:
python3 scripts/safe-extract-package.py "\$ARDTT_PACKAGE" /opt/ardtt/staging
bash /opt/ardtt/staging/install.sh
\`\`\`

Update is the same command. Uninstall: \`ARDTT_ACTION=uninstall bash /opt/ardtt/current/install.sh\`
(add \`ARDTT_PURGE_DATA=1\` to delete /opt/ardtt/data). Rollback: \`ARDTT_ACTION=rollback\`.
EOF

LAYOUT_SHA256="$(sha256sum "$STAGE/images/layout.json" | awk '{print $1}')"
INSTALL_SHA="$(sha256sum "$STAGE/install.sh" | awk '{print $1}')"
READY_SHA="$(sha256sum "$STAGE/ready.sh" | awk '{print $1}')"
COMPOSE_FILE_SHA="$(sha256sum "$STAGE/docker-compose.yml" | awk '{print $1}')"
ENGINE_FILE_SHA="$(sha256sum "$STAGE/vendor/docker.tgz" | awk '{print $1}')"
LAYER_COUNT="$(python3 -c 'import json; print(len(json.load(open("'"$STAGE/images/layout.json"'"))["layers"]))')"

python3 - "$STAGE/manifest.json" <<PY
import json, os, pathlib, sys
path = pathlib.Path(sys.argv[1])
manifest = {
  "format": "ardtt-server-v1",
  "deployVersion": "${VER}",
  "releaseTag": "${TAG}",
  "commit": "${COMMIT}",
  "os": "linux",
  "arch": "${ARCH}",
  "image": {
    "tag": "${IMAGE}",
    "id": "${IMAGE_ID}",
    "layout": "images/layout.json",
    "layerCount": int("${LAYER_COUNT}"),
    "note": "image.id is the docker image ID after load, not a registry RepoDigest; payload is gzipped layers",
  },
  "files": {
    "images/layout.json": "${LAYOUT_SHA256}",
    "install.sh": "${INSTALL_SHA}",
    "ready.sh": "${READY_SHA}",
    "docker-compose.yml": "${COMPOSE_FILE_SHA}",
    "vendor/docker.tgz": "${ENGINE_FILE_SHA}",
  },
  "docker": {
    "engineRequired": False,
    "engineBundled": True,
    "engineTarball": "vendor/docker.tgz",
    "engineVersion": "${ENGINE_VER}",
    "composeCli": "bin/docker-compose",
    "composeVersion": "${COMPOSE_VER}",
    "minApi": "1.44",
    "pull": "never",
    "build": False,
    "imageFormat": "ardtt-image-layers-v1",
  },
  "thirdPartyLock": "third-party.lock.json",
}
path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY

(
  cd "$STAGE"
  sha256sum install.sh ready.sh docker-compose.yml docker-compose.exit.yml \
    images/layout.json images/config.json bin/docker-compose vendor/docker.tgz \
    manifest.json third-party.lock.json scripts/assemble-docker-save.py > SHA256SUMS
  find images/layers -type f -name '*.tar.gz' -print0 | sort -z | xargs -0 sha256sum >> SHA256SUMS
)

tar -czf "$OUT" -C "$STAGE" \
  manifest.json SHA256SUMS README.md DEPLOY_VERSION third-party.lock.json \
  install.sh ready.sh install-lib scripts \
  docker-compose.yml docker-compose.exit.yml .env.example \
  images bin vendor

# Outer digest is published next to the archive (GitHub asset digest is the trust source).
sha256sum "$OUT" | awk '{print $1}' > "${OUT}.sha256"
ls -lh "$OUT"
echo "Packed $OUT"
echo "imageId=$IMAGE_ID arch=$ARCH deployVersion=$VER"
echo "outerSha256=$(cat "${OUT}.sha256")"
