#!/usr/bin/env bash
# Split docker-save → gzipped layers → stream reassemble round-trip.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 - "$TMP/save" <<'PY'
import hashlib, io, json, pathlib, sys, tarfile
root = pathlib.Path(sys.argv[1])
root.mkdir(parents=True, exist_ok=True)
layer_a = b"layer-a-bytes-aaaaaaaa"
layer_b = b"layer-b-bytes-bbbbbbbb"
# split cross-checks every layer against rootfs.diff_ids: use real digests.
cfg = {
    "os": "linux",
    "architecture": "amd64",
    "rootfs": {
        "type": "layers",
        "diff_ids": [
            "sha256:" + hashlib.sha256(layer_a).hexdigest(),
            "sha256:" + hashlib.sha256(layer_b).hexdigest(),
        ],
    },
    "config": {
        "Entrypoint": ["/opt/ardtt/entrypoint.sh"],
        "Cmd": None,
        "Env": ["PATH=/usr/bin"],
        "User": "0",
        "WorkingDir": "/opt/ardtt",
    },
}
(root / "cfg.json").write_text(json.dumps(cfg), encoding="utf-8")
(root / "layer1").mkdir()
(root / "layer1" / "layer.tar").write_bytes(layer_a)
(root / "layer1" / "VERSION").write_text("1.0\n", encoding="utf-8")
(root / "layer1" / "json").write_text("{}\n", encoding="utf-8")
(root / "layer2").mkdir()
(root / "layer2" / "layer.tar").write_bytes(layer_b)
(root / "layer2" / "VERSION").write_text("1.0\n", encoding="utf-8")
(root / "layer2" / "json").write_text("{}\n", encoding="utf-8")
man = [{
    "Config": "cfg.json",
    "RepoTags": ["ardtt/server:test"],
    "Layers": ["layer1/layer.tar", "layer2/layer.tar"],
}]
(root / "manifest.json").write_text(json.dumps(man), encoding="utf-8")
with tarfile.open(root / "image.tar", "w") as t:
    for name in (
        "manifest.json", "cfg.json",
        "layer1/layer.tar", "layer1/VERSION", "layer1/json",
        "layer2/layer.tar", "layer2/VERSION", "layer2/json",
    ):
        t.add(root / name, arcname=name)
PY

mkdir -p "$TMP/images"
python3 "$ROOT/scripts/split-docker-save.py" "$TMP/save/image.tar" "$TMP/images"
test -f "$TMP/images/layout.json"
test -f "$TMP/images/config.json"
layer_count="$(find "$TMP/images/layers" -name '*.tar.gz' | wc -l)"
[ "$layer_count" = "2" ] || { echo "expected 2 layers, got $layer_count" >&2; exit 1; }

python3 "$ROOT/scripts/assemble-docker-save.py" "$TMP/images" > "$TMP/rebuilt.tar"
python3 - "$TMP/rebuilt.tar" <<'PY'
import json, sys, tarfile
with tarfile.open(sys.argv[1], "r:") as t:
    names = set(t.getnames())
    assert "manifest.json" in names
    assert "cfg.json" in names
    assert "layer1/layer.tar" in names
    assert "layer2/layer.tar" in names
    man = json.load(t.extractfile("manifest.json"))
    assert man[0]["RepoTags"] == ["ardtt/server:test"]
    assert t.extractfile("layer1/layer.tar").read() == b"layer-a-bytes-aaaaaaaa"
    assert t.extractfile("layer2/layer.tar").read() == b"layer-b-bytes-bbbbbbbb"
print("OK split/assemble round-trip")
PY

# Installer helpers must accept layout packages.
grep -q 'ardtt-image-layers-v1\|IMAGE_LAYOUT\|layout.json' \
  "$ROOT/server/install-lib/package.sh"
grep -q 'MIN_DISK_MB:-.*1600\|MIN_DISK_MB:-1600' "$ROOT/server/install.sh" \
  || grep -q 'MIN_DISK_MB:-${NVPN_MIN_DISK_MB:-1600}' "$ROOT/server/install.sh"
echo "OK test-image-layers"
