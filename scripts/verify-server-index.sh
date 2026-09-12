#!/usr/bin/env bash
# Verify a partial-deploy index next to its components (dist/ after pack):
# every referenced asset exists with the recorded SHA-256, the host-files
# archive extracts safely and matches the per-file sums, every layer gzip
# decompresses to its diff ID, and the full archive digest matches.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INDEX="${1:-}"
[ -n "$INDEX" ] && [ -f "$INDEX" ] || { echo "usage: $0 dist/ardtt-server-<ver>-linux-<arch>.index.json" >&2; exit 1; }
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

python3 - "$INDEX" "$STAGE" "$ROOT/scripts/safe-extract-package.py" <<'PY'
import gzip, hashlib, json, os, pathlib, subprocess, sys
index = pathlib.Path(sys.argv[1]).resolve()
stage = pathlib.Path(sys.argv[2])
safe_extract = sys.argv[3]
root = index.parent
d = json.loads(index.read_text(encoding="utf-8"))
assert d["format"] == "ardtt-server-index-v1", d.get("format")
assert d["arch"] in ("amd64", "arm64"), d.get("arch")

def sha(path, decompress=False):
    h = hashlib.sha256()
    opener = gzip.open if decompress else open
    with opener(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(4 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def check(comp, label):
    p = root / comp["asset"]
    assert p.is_file(), f"{label}: missing {comp['asset']}"
    got = sha(p)
    assert got == comp["sha256"].lower(), f"{label}: {comp['asset']} sha256 {got} != {comp['sha256']}"
    assert p.stat().st_size == int(comp["size"]), f"{label}: {comp['asset']} size"
    return p

check(d["package"], "package")
host = check(d["hostfiles"], "hostfiles")
subprocess.check_call([sys.executable, safe_extract, str(host), str(stage)])
files = d["hostfiles"]["files"]
for rel, want in files.items():
    p = stage / rel
    assert p.is_file(), f"hostfiles: {rel} missing after extract"
    assert sha(p) == want.lower(), f"hostfiles: {rel} sha256 mismatch"
for p in stage.rglob("*"):
    if p.is_file():
        assert p.relative_to(stage).as_posix() in files, f"hostfiles: {p.relative_to(stage)} not listed in index"
for required in ("install.sh", "fetch-and-install.sh", "docker-compose.yml", "manifest.json",
                 "images/layout.json", "scripts/layer-cache.py", "scripts/assemble-docker-save.py"):
    assert required in files, f"hostfiles: {required} not shipped"
assert (stage / "vendor").exists() is False and (stage / "bin").exists() is False, "hostfiles must not carry Engine/Compose"
assert not list((stage / "images").glob("layers/*")), "hostfiles must not carry layer blobs"

layout = json.loads((stage / "images" / "layout.json").read_text(encoding="utf-8"))
assert sha(stage / "images" / "layout.json") == d["image"]["layoutSha256"].lower()
layout_layers = {l["diffId"] if "diffId" in l else "sha256:" + l["sha256"]: l for l in layout["layers"]}
assert len(layout_layers) == len(d["image"]["layers"]) == d["image"]["layerCount"]
for layer in d["image"]["layers"]:
    p = check({"asset": layer["asset"], "sha256": layer["sha256"], "size": layer["gzSize"]}, f"layer {layer['index']}")
    got = sha(p, decompress=True)
    assert got == layer["diffId"].removeprefix("sha256:"), f"layer {layer['index']}: diff ID {got} != {layer['diffId']}"
    assert layer["diffId"] in layout_layers, f"layer {layer['index']} not in layout.json"
    assert layout_layers[layer["diffId"]]["file"] == layer["file"]
for key in ("engine", "compose"):
    if d.get(key):
        check(d[key], key)
print(f"OK index {index.name}: {len(files)} host files, {len(d['image']['layers'])} layers, "
      f"engine={'yes' if d.get('engine') else 'no'} compose={'yes' if d.get('compose') else 'no'}")
PY
