#!/usr/bin/env bash
# Tiny package for installer unit tests (no real docker image).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-}"
ARCH="${2:-amd64}"
VER="${3:-1.0.45-test}"
[ -n "$OUT" ] || { echo "usage: $0 outfile [arch] [version]" >&2; exit 1; }
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/images" "$STAGE/bin" "$STAGE/install-lib" "$STAGE/scripts"
echo "fake-docker-save" > "$STAGE/images/ardtt.tar"
echo "#!/bin/sh" > "$STAGE/bin/docker-compose"
chmod +x "$STAGE/bin/docker-compose"
cp -f "$ROOT/server/install.sh" "$STAGE/install.sh"
cp -a "$ROOT/server/install-lib/." "$STAGE/install-lib/"
cp -f "$ROOT/scripts/safe-extract-package.py" "$STAGE/scripts/safe-extract-package.py"
cp -f "$ROOT/server/docker-compose.yml" "$STAGE/docker-compose.yml"
cp -f "$ROOT/server/docker-compose.exit.yml" "$STAGE/docker-compose.exit.yml"
cp -f "$ROOT/server/.env.example" "$STAGE/.env.example"
printf '%s\n' "$VER" > "$STAGE/DEPLOY_VERSION"
cp -f "$ROOT/server/third-party.lock.json" "$STAGE/third-party.lock.json"
IMAGE_SHA="$(sha256sum "$STAGE/images/ardtt.tar" | awk '{print $1}')"
cat > "$STAGE/manifest.json" <<EOF
{
  "format": "ardtt-server-v1",
  "deployVersion": "$VER",
  "releaseTag": "v-test",
  "commit": "testdeadbeef",
  "os": "linux",
  "arch": "$ARCH",
  "image": {"tag": "ardtt/server:${VER}", "id": "sha256:deadbeef"},
  "files": {"images/ardtt.tar": "$IMAGE_SHA"},
  "docker": {"engineRequired": true, "pull": "never", "build": false}
}
EOF
echo "test package" > "$STAGE/README.md"
(cd "$STAGE" && sha256sum install.sh docker-compose.yml images/ardtt.tar manifest.json > SHA256SUMS)
tar -czf "$OUT" -C "$STAGE" .
sha256sum "$OUT" | awk '{print $1}' > "${OUT}.sha256"
echo "$OUT"
