#!/usr/bin/env bash
# Tiny package for installer unit tests (no real docker image).
#   ARDTT_FAKE_LAYERED=1        images/layout.json + gzip layers instead of images/ardtt.tar
#   ARDTT_FAKE_LAYER_SEED=str   vary layer content between fake versions (default: the version)
#   ARDTT_FAKE_STAGE_DIR=dir    also leave a copy of the assembled stage there (for build-server-index.py)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-}"
ARCH="${2:-amd64}"
VER="${3:-1.0.45-test}"
[ -n "$OUT" ] || { echo "usage: $0 outfile [arch] [version]" >&2; exit 1; }
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/images" "$STAGE/bin" "$STAGE/install-lib" "$STAGE/scripts" "$STAGE/vendor"
mkdir -p "$STAGE/docker"
echo "#!/bin/sh" > "$STAGE/docker/dockerd"
chmod +x "$STAGE/docker/dockerd"
# Deterministic bytes: like the real upstream tarball, the same Engine version
# must be identical across fake package versions (shared release asset).
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='@0' \
  -cf - -C "$STAGE" docker | gzip -n > "$STAGE/vendor/docker.tgz"
rm -rf "$STAGE/docker"
ENGINE_SHA="$(sha256sum "$STAGE/vendor/docker.tgz" | awk '{print $1}')"
echo "#!/bin/sh" > "$STAGE/bin/docker-compose"
chmod +x "$STAGE/bin/docker-compose"
cp -f "$ROOT/server/install.sh" "$STAGE/install.sh"
cp -f "$ROOT/server/fetch-and-install.sh" "$STAGE/fetch-and-install.sh"
chmod 755 "$STAGE/fetch-and-install.sh"
cp -f "$ROOT/server/ready.sh" "$STAGE/ready.sh"
chmod 755 "$STAGE/ready.sh"
cp -a "$ROOT/server/install-lib/." "$STAGE/install-lib/"
cp -f "$ROOT/scripts/safe-extract-package.py" "$STAGE/scripts/safe-extract-package.py"
cp -f "$ROOT/scripts/assemble-docker-save.py" "$STAGE/scripts/assemble-docker-save.py"
cp -f "$ROOT/scripts/layer-cache.py" "$STAGE/scripts/layer-cache.py"
chmod 755 "$STAGE/scripts/assemble-docker-save.py" "$STAGE/scripts/layer-cache.py"
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

IMAGE_FILES='"images/ardtt.tar": "'"$(printf x | sha256sum | awk '{print $1}')"'"'
if [ "${ARDTT_FAKE_LAYERED:-0}" = "1" ]; then
  # Three tiny layers; the seed changes only the last one so a second fake
  # version shares two layers with the first (what a real update looks like).
  ARDTT_FAKE_LAYER_SEED="${ARDTT_FAKE_LAYER_SEED:-$VER}" ARCH="$ARCH" VER="$VER" \
    python3 - "$STAGE/images" <<'PY'
import gzip, hashlib, json, os, pathlib, sys
images = pathlib.Path(sys.argv[1]); (images / "layers").mkdir(parents=True, exist_ok=True)
seed = os.environ["ARDTT_FAKE_LAYER_SEED"]
raws = [b"base-layer-" * 4000, b"tools-layer-" * 2000, (f"scripts-layer-{seed}-" * 500).encode()]
diff = [hashlib.sha256(r).hexdigest() for r in raws]
cfg = {"architecture": os.environ["ARCH"], "os": "linux",
       "rootfs": {"type": "layers", "diff_ids": ["sha256:" + d for d in diff]},
       "config": {"Entrypoint": ["/entrypoint.sh"], "Cmd": None, "Env": ["PATH=/usr/bin"], "User": "", "WorkingDir": ""}}
cfg_b = json.dumps(cfg).encode()
(images / "config.json").write_bytes(cfg_b)
layers = []
for i, (raw, d) in enumerate(zip(raws, diff)):
    name = f"layers/{i:02d}-{d[:16]}.tar.gz"
    gz = gzip.compress(raw, mtime=0)
    (images / name).write_bytes(gz)
    layers.append({"index": i, "savePath": f"{d}/layer.tar", "file": name, "sha256": d, "diffId": "sha256:" + d,
                   "rawSize": len(raw), "gzSize": len(gz)})
layout = {"format": "ardtt-image-layers-v1", "repoTags": ["ardtt/server:" + os.environ["VER"]],
          "configFile": "config.json", "configSavePath": "config.json",
          "configSha256": hashlib.sha256(cfg_b).hexdigest(), "layers": layers, "extras": [], "repositories": None,
          "diffIds": ["sha256:" + d for d in diff], "architecture": os.environ["ARCH"], "os": "linux",
          "totalGzSize": sum(l["gzSize"] for l in layers), "totalRawSize": sum(l["rawSize"] for l in layers)}
(images / "layout.json").write_text(json.dumps(layout, indent=2) + "\n", encoding="utf-8")
PY
  IMAGE_FILES='"images/layout.json": "'"$(sha256sum "$STAGE/images/layout.json" | awk '{print $1}')"'"'
  IMAGE_EXTRA=', "layout": "images/layout.json"'
else
  echo "fake-docker-save" > "$STAGE/images/ardtt.tar"
  IMAGE_FILES='"images/ardtt.tar": "'"$(sha256sum "$STAGE/images/ardtt.tar" | awk '{print $1}')"'"'
  IMAGE_EXTRA=''
fi
cat > "$STAGE/manifest.json" <<EOF
{
  "format": "ardtt-server-v1",
  "deployVersion": "$VER",
  "releaseTag": "v-test",
  "commit": "testdeadbeef",
  "os": "linux",
  "arch": "$ARCH",
  "image": {"tag": "ardtt/server:${VER}", "id": "sha256:deadbeef"${IMAGE_EXTRA}},
  "files": {${IMAGE_FILES}},
  "docker": {"engineRequired": false, "engineBundled": true, "engineTarball": "vendor/docker.tgz", "pull": "never", "build": false}
}
EOF
echo "test package" > "$STAGE/README.md"
(
  cd "$STAGE"
  sums=(install.sh fetch-and-install.sh ready.sh docker-compose.yml vendor/docker.tgz manifest.json)
  [ -f images/ardtt.tar ] && sums+=(images/ardtt.tar)
  [ -f images/layout.json ] && sums+=(images/layout.json)
  sha256sum "${sums[@]}" > SHA256SUMS
)
if [ -n "${ARDTT_FAKE_STAGE_DIR:-}" ]; then
  mkdir -p "$ARDTT_FAKE_STAGE_DIR"
  cp -a "$STAGE"/. "$ARDTT_FAKE_STAGE_DIR"/
fi
tar -czf "$OUT" -C "$STAGE" .
sha256sum "$OUT" | awk '{print $1}' > "${OUT}.sha256"
echo "$OUT"
