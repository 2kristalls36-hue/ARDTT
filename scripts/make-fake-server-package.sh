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
mkdir -p "$STAGE/images" "$STAGE/bin" "$STAGE/install-lib" "$STAGE/scripts" "$STAGE/vendor"
echo "fake-docker-save" > "$STAGE/images/ardtt.tar"
mkdir -p "$STAGE/docker"
echo "#!/bin/sh" > "$STAGE/docker/dockerd"
chmod +x "$STAGE/docker/dockerd"
tar -czf "$STAGE/vendor/docker.tgz" -C "$STAGE" docker
rm -rf "$STAGE/docker"
ENGINE_SHA="$(sha256sum "$STAGE/vendor/docker.tgz" | awk '{print $1}')"
echo "#!/bin/sh" > "$STAGE/bin/docker-compose"
chmod +x "$STAGE/bin/docker-compose"
cp -f "$ROOT/server/install.sh" "$STAGE/install.sh"
cp -f "$ROOT/server/ready.sh" "$STAGE/ready.sh"
chmod 755 "$STAGE/ready.sh"
cp -a "$ROOT/server/install-lib/." "$STAGE/install-lib/"
cp -f "$ROOT/scripts/safe-extract-package.py" "$STAGE/scripts/safe-extract-package.py"
cp -f "$ROOT/server/docker-compose.yml" "$STAGE/docker-compose.yml"
cp -f "$ROOT/server/docker-compose.exit.yml" "$STAGE/docker-compose.exit.yml"
cp -f "$ROOT/server/.env.example" "$STAGE/.env.example"
printf '%s\n' "$VER" > "$STAGE/DEPLOY_VERSION"
cp -f "$ROOT/server/third-party.lock.json" "$STAGE/third-party.lock.json"
python3 - "$STAGE/third-party.lock.json" "$ENGINE_SHA" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
sha = sys.argv[2]
d = json.loads(path.read_text(encoding="utf-8"))
static = d.setdefault("dockerEngineStatic", {})
sha_map = static.setdefault("sha256", {})
sha_map["amd64"] = sha
sha_map["arm64"] = sha
path.write_text(json.dumps(d, indent=2) + "\n", encoding="utf-8")
PY
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
  "docker": {"engineRequired": false, "engineBundled": true, "engineTarball": "vendor/docker.tgz", "pull": "never", "build": false}
}
EOF
echo "test package" > "$STAGE/README.md"
(cd "$STAGE" && sha256sum install.sh ready.sh docker-compose.yml images/ardtt.tar vendor/docker.tgz manifest.json > SHA256SUMS)
tar -czf "$OUT" -C "$STAGE" .
sha256sum "$OUT" | awk '{print $1}' > "${OUT}.sha256"
echo "$OUT"
