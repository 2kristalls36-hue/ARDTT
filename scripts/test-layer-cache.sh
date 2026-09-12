#!/usr/bin/env bash
# layer-cache.py: plan / store / link / adopt / prune / seed (fake docker, no Engine).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
LC="$ROOT/scripts/layer-cache.py"

python3 - "$TMP" <<'PY'
import gzip, hashlib, io, json, pathlib, sys, tarfile
tmp = pathlib.Path(sys.argv[1])
layers = [b"layer-one-" * 3000, b"layer-two-" * 2000, b"layer-three-" * 1000]
diff = [hashlib.sha256(x).hexdigest() for x in layers]
cfg = {"os": "linux", "architecture": "amd64",
       "rootfs": {"type": "layers", "diff_ids": ["sha256:" + d for d in diff]},
       "config": {"Entrypoint": ["/entrypoint.sh"]}}
cfg_b = json.dumps(cfg).encode()
# docker-save tar the way the *old* image exists locally (classic layout)
with tarfile.open(tmp / "old-image.tar", "w") as t:
    def add(name, data):
        ti = tarfile.TarInfo(name); ti.size = len(data); t.addfile(ti, io.BytesIO(data))
    add("cfg.json", cfg_b)
    # containerd-style gzip blob for layer 0, classic layer.tar for 1; layer 2 is *not* in the old image
    add("blobs/sha256/" + hashlib.sha256(gzip.compress(layers[0])).hexdigest(), gzip.compress(layers[0]))
    add("l1/layer.tar", layers[1])
    add("manifest.json", json.dumps([{"Config": "cfg.json", "RepoTags": ["ardtt/server:old"],
                                     "Layers": ["blobs/sha256/x", "l1/layer.tar"]}]).encode())
# new package layout: all three layers
images = tmp / "images"; (images / "layers").mkdir(parents=True)
lay = {"format": "ardtt-image-layers-v1", "repoTags": ["ardtt/server:new"], "configFile": "config.json",
       "configSavePath": "cfg.json", "layers": [], "extras": [], "repositories": None,
       "diffIds": ["sha256:" + d for d in diff], "architecture": "amd64", "os": "linux"}
for i, (raw, d) in enumerate(zip(layers, diff)):
    gz = gzip.compress(raw, mtime=0)
    name = f"layers/{i:02d}-{d[:16]}.tar.gz"
    (tmp / "dl" ).mkdir(exist_ok=True)
    (tmp / "dl" / pathlib.Path(name).name).write_bytes(gz)   # "downloaded" copies
    lay["layers"].append({"index": i, "savePath": f"l{i}/layer.tar", "file": name, "sha256": d,
                          "diffId": "sha256:" + d, "rawSize": len(raw), "gzSize": len(gz)})
(images / "config.json").write_bytes(cfg_b)
(images / "layout.json").write_text(json.dumps(lay, indent=2))
(tmp / "diff.json").write_text(json.dumps(diff))
PY

# Fake docker: `save` streams the old image, `images` lists it.
mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<EOF
#!/bin/sh
case "\$1" in
  save) cat "$TMP/old-image.tar" ;;
  images) echo "ardtt/server:old" ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$TMP/bin/docker"

CACHE="$TMP/cache"
plan="$(python3 "$LC" plan "$TMP/images/layout.json" "$CACHE")"
echo "$plan" | python3 -c 'import json,sys; p=json.load(sys.stdin); assert p["missingCount"]==3 and p["cachedCount"]==0, p'
echo "OK plan: empty cache → 3 missing"

# Seed from the local image: layers 0 and 1 come from docker save (gz blob + classic), layer 2 stays missing.
out="$(python3 "$LC" seed "$CACHE" --want "$TMP/images/layout.json" --docker "$TMP/bin/docker")"
echo "$out" | grep -q 'seeded 2 layers' || { echo "FAIL seed: $out" >&2; exit 1; }
plan="$(python3 "$LC" plan "$TMP/images/layout.json" "$CACHE")"
echo "$plan" | python3 -c '
import json,sys; p=json.load(sys.stdin)
assert p["missingCount"]==1 and p["cachedCount"]==2, p
assert [x["cached"] for x in p["layers"]]==[True,True,False], p'
echo "OK seed: 2 layers from docker save, 1 still missing"

# Seeding again is a no-op that does not even call docker save.
out="$(python3 "$LC" seed "$CACHE" --want "$TMP/images/layout.json" --docker /nonexistent/docker)"
echo "$out" | grep -q 'seeded 0 layers' || { echo "FAIL seed idempotent: $out" >&2; exit 1; }
[ -z "$(find "$CACHE" -name '*.part')" ] || { echo "FAIL leftover .part in cache" >&2; exit 1; }

# store: wrong diff ID is rejected and the file removed; correct one moves into the cache.
D2="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[2])' "$TMP/diff.json")"
D0="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0])' "$TMP/diff.json")"
f2="$(ls "$TMP"/dl/02-*.tar.gz)"
cp "$f2" "$TMP/bad.tar.gz"
if python3 "$LC" store "$CACHE" "$TMP/bad.tar.gz" "$D0" 2>/dev/null; then
  echo "FAIL store must reject a layer whose content does not match the diff ID" >&2; exit 1
fi
[ ! -e "$TMP/bad.tar.gz" ] || { echo "FAIL rejected file must be deleted" >&2; exit 1; }
python3 "$LC" store "$CACHE" "$f2" "$D2" >/dev/null
[ -f "$CACHE/$D2.tar.gz" ] || { echo "FAIL store did not place entry" >&2; exit 1; }
[ ! -e "$f2" ] || { echo "FAIL store must move (not copy)" >&2; exit 1; }
echo "OK store"

# Corrupt entry is dropped by plan and reported missing.
printf 'garbage' > "$CACHE/$D2.tar.gz"
plan="$(python3 "$LC" plan "$TMP/images/layout.json" "$CACHE")"
echo "$plan" | python3 -c 'import json,sys; p=json.load(sys.stdin); assert p["missingCount"]==1, p'
[ ! -e "$CACHE/$D2.tar.gz" ] || { echo "FAIL corrupt entry must be removed" >&2; exit 1; }
echo "OK plan drops corrupt entry"

# link fails while a layer is missing, succeeds once the cache is complete.
if python3 "$LC" link "$TMP/images/layout.json" "$CACHE" "$TMP/images" 2>/dev/null; then
  echo "FAIL link must fail with a missing layer" >&2; exit 1
fi
python3 - "$TMP" "$D2" <<'PY'
import gzip, pathlib, sys
tmp, d2 = pathlib.Path(sys.argv[1]), sys.argv[2]
raw = b"layer-three-" * 1000
(tmp / "cache" / (d2 + ".tar.gz")).write_bytes(gzip.compress(raw, mtime=0))
PY
python3 "$LC" link "$TMP/images/layout.json" "$CACHE" "$TMP/images" >/dev/null
n="$(find "$TMP/images/layers" -type f | wc -l)"
[ "$n" = "3" ] || { echo "FAIL link placed $n files" >&2; exit 1; }
# hardlink: same inode as the cache entry
python3 - "$TMP" "$D2" <<'PY'
import os, pathlib, sys, json
tmp, d2 = pathlib.Path(sys.argv[1]), sys.argv[2]
lay = json.loads((tmp / "images/layout.json").read_text())
staged = tmp / "images" / lay["layers"][2]["file"]
assert os.path.samefile(staged, tmp / "cache" / (d2 + ".tar.gz")) or staged.read_bytes() == (tmp / "cache" / (d2 + ".tar.gz")).read_bytes()
PY
# assemble must work from the linked layers (proves cache content is usable by docker load)
python3 "$ROOT/scripts/assemble-docker-save.py" "$TMP/images" > "$TMP/rebuilt.tar"
python3 - "$TMP/rebuilt.tar" <<'PY'
import sys, tarfile
with tarfile.open(sys.argv[1]) as t:
    assert t.extractfile("l2/layer.tar").read() == b"layer-three-" * 1000
PY
echo "OK link + assemble from cache"

# adopt: staged layer files (hardlinks) vanish from staging, cache untouched; a fresh file is moved in.
rm -f "$CACHE/$D0.tar.gz"
python3 "$LC" adopt "$TMP/images/layout.json" "$TMP/images" "$CACHE" | grep -q 'adopted 1 layers'
[ -z "$(find "$TMP/images/layers" -type f)" ] || { echo "FAIL adopt must clear staged layers" >&2; exit 1; }
[ "$(find "$CACHE" -name '*.tar.gz' | wc -l)" = "3" ] || { echo "FAIL adopt cache count" >&2; exit 1; }
echo "OK adopt"

# prune keeps only referenced layers.
printf 'x' > "$CACHE/$(printf 'f%.0s' $(seq 1 64)).tar.gz"
printf 'x' > "$CACHE/.seed-1-1.part"
python3 "$LC" prune "$CACHE" "$TMP/images/layout.json" >/dev/null
[ "$(find "$CACHE" -type f | wc -l)" = "3" ] || { echo "FAIL prune left $(ls -A "$CACHE")" >&2; exit 1; }
echo "OK prune"
echo "OK test-layer-cache"
