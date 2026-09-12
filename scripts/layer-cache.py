#!/usr/bin/env python3
"""Content-addressed cache of gzipped image layers on the VPS.

Entries live as <cache-dir>/<diffId-hex>.tar.gz where diffId is the SHA-256 of
the *uncompressed* layer tar (the same value Docker records in
rootfs.diff_ids). Partial deploy downloads only layers whose diff ID is not in
this cache; everything else is hardlinked from the cache into the staging
package. Entries are re-verified (decompress + hash) before reuse.

Subcommands:
  plan  <layout.json> <cache-dir>              JSON plan: which layers are cached / missing
  store <cache-dir> <layer.tar.gz> <diffId>    verify and move a downloaded layer into the cache
  link  <layout.json> <cache-dir> <images-dir> hardlink (or copy) cached layers into images-dir
  adopt <layout.json> <images-dir> <cache-dir> move staged layers into the cache after docker load
  prune <cache-dir> <layout.json>...           drop entries not referenced by the given layouts
  seed  <cache-dir> [--want layout.json] [--docker BIN] [image-ref...]
                                                fill the cache from `docker save` of local images
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import zlib
from pathlib import Path

CHUNK = 4 * 1024 * 1024
GZIP_MAGIC = b"\x1f\x8b"
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"
SUFFIX = ".tar.gz"


def _hex(diff_id: str) -> str:
    return str(diff_id).strip().lower().removeprefix("sha256:")


def _entry(cache: Path, diff_id: str) -> Path:
    return cache / (_hex(diff_id) + SUFFIX)


def gz_diff_id(path: Path) -> tuple[str, int]:
    """Return (sha256 hex of decompressed content, raw size); raises on bad gzip."""
    digest = hashlib.sha256()
    size = 0
    with gzip.open(path, "rb") as gz:
        while True:
            chunk = gz.read(CHUNK)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def verify_entry(path: Path, diff_id: str) -> bool:
    try:
        got, _ = gz_diff_id(path)
    except (OSError, EOFError, zlib.error):
        return False
    return got == _hex(diff_id)


def load_layout(path: Path) -> dict:
    layout = json.loads(path.read_text(encoding="utf-8"))
    if layout.get("format") != "ardtt-image-layers-v1":
        raise SystemExit(f"unsupported layout format {layout.get('format')}")
    return layout


def layer_diff_id(layer: dict) -> str:
    return _hex(layer.get("diffId") or layer.get("sha256") or "")


def cmd_plan(argv: list[str]) -> int:
    layout_path, cache = Path(argv[0]), Path(argv[1])
    layout = load_layout(layout_path)
    cache.mkdir(parents=True, exist_ok=True)
    out = []
    cached_gz = missing_gz = 0
    for layer in layout.get("layers") or []:
        diff_id = layer_diff_id(layer)
        entry = _entry(cache, diff_id)
        cached = False
        if diff_id and entry.is_file():
            if verify_entry(entry, diff_id):
                cached = True
            else:
                # Corrupt or foreign file under our key: drop it, re-download.
                entry.unlink(missing_ok=True)
        gz_size = int(layer.get("gzSize") or 0)
        if cached:
            cached_gz += gz_size
        else:
            missing_gz += gz_size
        out.append(
            {
                "index": layer.get("index"),
                "file": layer.get("file"),
                "diffId": diff_id,
                "gzSize": gz_size,
                "rawSize": int(layer.get("rawSize") or 0),
                "cached": cached,
            }
        )
    plan = {
        "layers": out,
        "cachedCount": sum(1 for x in out if x["cached"]),
        "missingCount": sum(1 for x in out if not x["cached"]),
        "cachedGzSize": cached_gz,
        "missingGzSize": missing_gz,
    }
    print(json.dumps(plan))
    return 0


def cmd_store(argv: list[str]) -> int:
    cache, src, diff_id = Path(argv[0]), Path(argv[1]), _hex(argv[2])
    if not src.is_file():
        raise SystemExit(f"store: missing {src}")
    if len(diff_id) != 64:
        raise SystemExit(f"store: bad diffId {diff_id}")
    if not verify_entry(src, diff_id):
        src.unlink(missing_ok=True)
        raise SystemExit(f"store: {src.name} does not decompress to diff ID {diff_id}")
    cache.mkdir(parents=True, exist_ok=True)
    dst = _entry(cache, diff_id)
    if dst.exists():
        try:
            if os.path.samefile(src, dst):
                print(dst)
                return 0
        except OSError:
            pass
        dst.unlink()
    os.replace(src, dst)
    print(dst)
    return 0


def _place(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        try:
            if os.path.samefile(src, dst):
                return
        except OSError:
            pass
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copyfile(src, dst)


def cmd_link(argv: list[str]) -> int:
    layout_path, cache, images = Path(argv[0]), Path(argv[1]), Path(argv[2])
    layout = load_layout(layout_path)
    missing = []
    for layer in layout.get("layers") or []:
        diff_id = layer_diff_id(layer)
        entry = _entry(cache, diff_id)
        if not entry.is_file():
            missing.append(diff_id)
            continue
        _place(entry, images / layer["file"])
    if missing:
        raise SystemExit("link: layers missing from cache: " + ", ".join(m[:16] for m in missing))
    print(f"linked {len(layout.get('layers') or [])} layers from {cache}")
    return 0


def cmd_adopt(argv: list[str]) -> int:
    layout_path, images, cache = Path(argv[0]), Path(argv[1]), Path(argv[2])
    layout = load_layout(layout_path)
    cache.mkdir(parents=True, exist_ok=True)
    adopted = 0
    for layer in layout.get("layers") or []:
        src = images / layer["file"]
        if not src.is_file():
            continue
        diff_id = layer_diff_id(layer)
        dst = _entry(cache, diff_id)
        if dst.exists():
            try:
                same = os.path.samefile(src, dst)
            except OSError:
                same = False
            if same or verify_entry(dst, diff_id):
                src.unlink(missing_ok=True)
                continue
            dst.unlink()
        if not verify_entry(src, diff_id):
            src.unlink(missing_ok=True)
            continue
        try:
            os.replace(src, dst)
        except OSError:
            shutil.copyfile(src, dst)
            src.unlink(missing_ok=True)
        adopted += 1
    print(f"adopted {adopted} layers into {cache}")
    return 0


def cmd_prune(argv: list[str]) -> int:
    cache = Path(argv[0])
    keep: set[str] = set()
    for lp in argv[1:]:
        p = Path(lp)
        if not p.is_file():
            continue
        try:
            layout = load_layout(p)
        except (OSError, ValueError, SystemExit):
            continue
        for layer in layout.get("layers") or []:
            keep.add(layer_diff_id(layer))
    if not cache.is_dir():
        print("pruned 0 bytes")
        return 0
    freed = 0
    for entry in cache.iterdir():
        if not entry.is_file():
            continue
        name = entry.name
        if name.endswith(".part") or (name.endswith(SUFFIX) and name[: -len(SUFFIX)] not in keep):
            try:
                freed += entry.stat().st_size
                entry.unlink()
            except OSError:
                pass
    print(f"pruned {freed} bytes")
    return 0


def _discover_images(docker: str) -> list[str]:
    try:
        out = subprocess.check_output(
            [docker, "images", "--filter", "label=com.ardtt.owner=ardtt",
             "--format", "{{.Repository}}:{{.Tag}}"],
            text=True, stderr=subprocess.DEVNULL, timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    refs = []
    for line in out.splitlines():
        ref = line.strip()
        if ref and "<none>" not in ref and ref not in refs:
            refs.append(ref)
    return refs


def _seed_from_stream(stream, cache: Path, want: set[str] | None, have: set[str]) -> int:
    """Walk a docker-save tar stream; gzip every layer blob into the cache."""
    stored = 0
    tmp_idx = 0
    with tarfile.open(fileobj=stream, mode="r|") as tar:
        for member in tar:
            if not member.isfile() or member.size == 0:
                continue
            name = member.name.replace("\\", "/")
            if not (name.endswith("/layer.tar") or name.startswith("blobs/sha256/")):
                continue
            src = tar.extractfile(member)
            if src is None:
                continue
            head = src.read(4)
            if not head or head[:1] in (b"{", b"["):
                continue  # config / manifest JSON blob
            if head == ZSTD_MAGIC:
                continue  # zstd layer: no diff ID without a zstd codec, let the download path handle it
            tmp_idx += 1
            tmp = cache / f".seed-{os.getpid()}-{tmp_idx}.part"
            digest = hashlib.sha256()
            try:
                with open(tmp, "wb") as fh:
                    if head[:2] == GZIP_MAGIC:
                        inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
                        chunk = head
                        while chunk:
                            fh.write(chunk)
                            digest.update(inflater.decompress(chunk))
                            chunk = src.read(CHUNK)
                        digest.update(inflater.flush())
                    else:
                        with gzip.GzipFile(filename="", mode="wb", fileobj=fh, compresslevel=6, mtime=0) as gz:
                            chunk = head
                            while chunk:
                                digest.update(chunk)
                                gz.write(chunk)
                                chunk = src.read(CHUNK)
            except (OSError, zlib.error, EOFError):
                tmp.unlink(missing_ok=True)
                continue
            diff_id = digest.hexdigest()
            if (want is not None and diff_id not in want) or diff_id in have:
                tmp.unlink(missing_ok=True)
                continue
            os.replace(tmp, _entry(cache, diff_id))
            have.add(diff_id)
            stored += 1
    return stored


def cmd_seed(argv: list[str]) -> int:
    cache = Path(argv[0])
    rest = argv[1:]
    want: set[str] | None = None
    docker = "docker"
    refs: list[str] = []
    i = 0
    while i < len(rest):
        if rest[i] == "--want" and i + 1 < len(rest):
            layout = load_layout(Path(rest[i + 1]))
            want = {layer_diff_id(l) for l in layout.get("layers") or []}
            i += 2
        elif rest[i] == "--docker" and i + 1 < len(rest):
            docker = rest[i + 1]
            i += 2
        else:
            refs.append(rest[i])
            i += 1
    cache.mkdir(parents=True, exist_ok=True)
    have = {p.name[: -len(SUFFIX)] for p in cache.glob("*" + SUFFIX)}
    if want is not None and want <= have:
        print("seeded 0 layers (cache already complete)")
        return 0
    if not refs:
        refs = _discover_images(docker)
    if not refs:
        print("seeded 0 layers (no local ARDTT image)")
        return 0
    total = 0
    for ref in refs:
        if want is not None and want <= have:
            break
        try:
            proc = subprocess.Popen([docker, "save", ref], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError:
            break
        assert proc.stdout is not None
        try:
            total += _seed_from_stream(proc.stdout, cache, want, have)
        except (OSError, tarfile.TarError, EOFError) as exc:
            print(f"seed: {ref}: {exc}", file=sys.stderr)
        finally:
            try:
                proc.stdout.close()
            except OSError:
                pass
            proc.wait(timeout=600)
    for leftover in cache.glob(f".seed-{os.getpid()}-*.part"):
        leftover.unlink(missing_ok=True)
    print(f"seeded {total} layers from {len(refs)} image(s)")
    return 0


COMMANDS = {
    "plan": (cmd_plan, 2),
    "store": (cmd_store, 3),
    "link": (cmd_link, 3),
    "adopt": (cmd_adopt, 3),
    "prune": (cmd_prune, 1),
    "seed": (cmd_seed, 1),
}


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__, file=sys.stderr)
        return 2
    fn, min_args = COMMANDS[sys.argv[1]]
    argv = sys.argv[2:]
    if len(argv) < min_args:
        print(__doc__, file=sys.stderr)
        return 2
    return fn(argv)


if __name__ == "__main__":
    raise SystemExit(main())
