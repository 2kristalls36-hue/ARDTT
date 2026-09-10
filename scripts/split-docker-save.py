#!/usr/bin/env python3
"""Split a `docker save` tar into gzipped layers + layout.json.

The monolithic images/ardtt.tar is large on disk during install. Shipping
individually gzipped layers lets the installer stream-reassemble into
`docker load` without writing another full uncompressed image tar.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import shutil
import sys
import tarfile
from pathlib import Path


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

        # Preserve sibling json/VERSION files that classic save puts next to layer.tar
        extras: list[dict] = []
        packed_layers: list[dict] = []
        for idx, layer_name in enumerate(layer_names):
            member = tar.extractfile(layer_name)
            if member is None:
                print(f"missing layer {layer_name}", file=sys.stderr)
                return 1
            raw = member.read()
            digest = hashlib.sha256(raw).hexdigest()
            out_name = f"{idx:02d}-{digest[:16]}.tar.gz"
            out_path = layers_dir / out_name
            with gzip.open(out_path, "wb", compresslevel=6) as gz:
                gz.write(raw)
            packed_layers.append(
                {
                    "index": idx,
                    "savePath": layer_name,
                    "file": f"layers/{out_name}",
                    "sha256": digest,
                    "rawSize": len(raw),
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

    rootfs = cfg.get("rootfs") or {}
    layout = {
        "format": "ardtt-image-layers-v1",
        "repoTags": repo_tags,
        "configFile": "config.json",
        "configSavePath": config_name,
        "layers": packed_layers,
        "extras": extras,
        "repositories": repos_rel,
        "diffIds": rootfs.get("diff_ids") or [],
        "architecture": cfg.get("architecture") or "",
        "os": cfg.get("os") or "linux",
    }
    (out_dir / "layout.json").write_text(json.dumps(layout, indent=2) + "\n", encoding="utf-8")
    total_gz = sum(x["gzSize"] for x in packed_layers)
    total_raw = sum(x["rawSize"] for x in packed_layers)
    print(
        f"split {len(packed_layers)} layers "
        f"gz={total_gz} raw={total_raw} -> {out_dir / 'layout.json'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
