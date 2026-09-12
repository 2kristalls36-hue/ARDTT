#!/usr/bin/env python3
"""Split a `docker save` tar into gzipped layers + layout.json.

Format: ardtt-image-layers-v1. Every layer becomes layers/<NN>-<diffid16>.tar.gz
where the layout's `sha256` / `diffId` is the layer *diff ID* — the SHA-256 of
the uncompressed layer tar. That value is stable for identical content no matter
how the blob was compressed, so it is the content-addressed key for the VPS
layer cache (only layers whose diff ID is missing locally are downloaded).

Input blobs that are already gzip (containerd image store exports layers as
they are stored) are kept byte-for-byte after their diff ID is verified;
uncompressed blobs are gzipped deterministically (mtime=0, no name) so the
same content always yields the same asset bytes.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import sys
import tarfile
import zlib
from pathlib import Path

CHUNK = 4 * 1024 * 1024
GZIP_MAGIC = b"\x1f\x8b"


def _split_one(src, out_path: Path) -> tuple[str, int, int]:
    """Write one layer blob to out_path as gzip; return (diff_id_hex, raw_size, gz_size)."""
    head = src.read(2)
    is_gzip = head == GZIP_MAGIC
    digest = hashlib.sha256()
    raw_size = 0
    tmp = out_path.with_name(out_path.name + ".part")
    with open(tmp, "wb") as fh:
        if is_gzip:
            # Keep the blob as-is; hash the decompressed stream for the diff ID.
            inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
            chunk = head
            while chunk:
                fh.write(chunk)
                raw = inflater.decompress(chunk)
                digest.update(raw)
                raw_size += len(raw)
                chunk = src.read(CHUNK)
            tail = inflater.flush()
            digest.update(tail)
            raw_size += len(tail)
        else:
            with gzip.GzipFile(filename="", mode="wb", fileobj=fh, compresslevel=6, mtime=0) as gz:
                chunk = head
                while chunk:
                    digest.update(chunk)
                    raw_size += len(chunk)
                    gz.write(chunk)
                    chunk = src.read(CHUNK)
    os.replace(tmp, out_path)
    return digest.hexdigest(), raw_size, out_path.stat().st_size


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} <docker-save.tar> <images-dir>", file=sys.stderr)
        return 2
    src = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    layers_dir = out_dir / "layers"
    layers_dir.mkdir(parents=True, exist_ok=True)

    with tarfile.open(src, "r:") as tar:
        names = tar.getnames()
        man_f = tar.extractfile("manifest.json")
        if man_f is None:
            print("docker save tar missing manifest.json", file=sys.stderr)
            return 1
        manifest = json.load(man_f)
        if not isinstance(manifest, list) or not manifest:
            print("invalid docker save manifest.json", file=sys.stderr)
            return 1
        if len(manifest) > 1:
            print("docker save tar contains several images; save exactly one tag", file=sys.stderr)
            return 1
        item = manifest[0]
        config_name = item.get("Config") or ""
        layer_names = list(item.get("Layers") or [])
        repo_tags = list(item.get("RepoTags") or [])
        if not config_name or not layer_names:
            print("manifest missing Config/Layers", file=sys.stderr)
            return 1

        cfg_f = tar.extractfile(config_name)
        if cfg_f is None:
            print(f"missing config {config_name}", file=sys.stderr)
            return 1
        cfg_bytes = cfg_f.read()
        cfg = json.loads(cfg_bytes)
        config_out = out_dir / "config.json"
        config_out.write_bytes(cfg_bytes)
        rootfs = cfg.get("rootfs") or {}
        expected_diff_ids = [str(x) for x in (rootfs.get("diff_ids") or [])]
        if expected_diff_ids and len(expected_diff_ids) != len(layer_names):
            print(
                f"config lists {len(expected_diff_ids)} diff_ids but manifest has {len(layer_names)} layers",
                file=sys.stderr,
            )
            return 1

        extras: list[dict] = []
        packed_layers: list[dict] = []
        for idx, layer_name in enumerate(layer_names):
            member = tar.extractfile(layer_name)
            if member is None:
                print(f"missing layer {layer_name}", file=sys.stderr)
                return 1
            # Temporary name until the diff ID is known.
            tmp_path = layers_dir / f"{idx:02d}-pending.tar.gz"
            digest, raw_size, _ = _split_one(member, tmp_path)
            if expected_diff_ids:
                want = expected_diff_ids[idx].removeprefix("sha256:").lower()
                if want != digest:
                    tmp_path.unlink(missing_ok=True)
                    print(
                        f"layer {idx} ({layer_name}) diff ID {digest} != config rootfs.diff_ids[{idx}] {want}",
                        file=sys.stderr,
                    )
                    return 1
            out_name = f"{idx:02d}-{digest[:16]}.tar.gz"
            out_path = layers_dir / out_name
            os.replace(tmp_path, out_path)
            packed_layers.append(
                {
                    "index": idx,
                    "savePath": layer_name,
                    "file": f"layers/{out_name}",
                    "sha256": digest,
                    "diffId": "sha256:" + digest,
                    "rawSize": raw_size,
                    "gzSize": out_path.stat().st_size,
                }
            )
            # Classic format: dirname/json + dirname/VERSION
            parent = str(Path(layer_name).parent)
            if parent and parent != ".":
                for extra in (f"{parent}/json", f"{parent}/VERSION"):
                    if extra in names and not any(e["savePath"] == extra for e in extras):
                        ef = tar.extractfile(extra)
                        if ef is None:
                            continue
                        data = ef.read()
                        rel = f"extras/{digest[:16]}-{Path(extra).name}"
                        (out_dir / rel).parent.mkdir(parents=True, exist_ok=True)
                        (out_dir / rel).write_bytes(data)
                        extras.append({"savePath": extra, "file": rel})

        # Also keep repositories file if present (optional for docker load).
        repos_rel = None
        if "repositories" in names:
            rf = tar.extractfile("repositories")
            if rf is not None:
                repos_path = out_dir / "repositories"
                repos_path.write_bytes(rf.read())
                repos_rel = "repositories"

    layout = {
        "format": "ardtt-image-layers-v1",
        "repoTags": repo_tags,
        "configFile": "config.json",
        "configSavePath": config_name,
        "configSha256": hashlib.sha256(cfg_bytes).hexdigest(),
        "layers": packed_layers,
        "extras": extras,
        "repositories": repos_rel,
        "diffIds": expected_diff_ids or ["sha256:" + x["sha256"] for x in packed_layers],
        "architecture": cfg.get("architecture") or "",
        "os": cfg.get("os") or "linux",
        "totalGzSize": sum(x["gzSize"] for x in packed_layers),
        "totalRawSize": sum(x["rawSize"] for x in packed_layers),
    }
    (out_dir / "layout.json").write_text(json.dumps(layout, indent=2) + "\n", encoding="utf-8")
    print(
        f"split {len(packed_layers)} layers "
        f"gz={layout['totalGzSize']} raw={layout['totalRawSize']} -> {out_dir / 'layout.json'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
