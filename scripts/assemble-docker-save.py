#!/usr/bin/env python3
"""Stream-reassemble ardtt-image-layers-v1 into a docker-save tar on stdout.

Used as: python3 assemble-docker-save.py <images-dir> | docker load
Avoids writing a second full images/ardtt.tar onto a tight VPS disk.
"""
from __future__ import annotations

import gzip
import io
import json
import sys
import tarfile
from pathlib import Path


def add_bytes(tar: tarfile.TarFile, name: str, data: bytes, mode: int = 0o644) -> None:
    info = tarfile.TarInfo(name=name)
    info.size = len(data)
    info.mode = mode
    tar.addfile(info, io.BytesIO(data))


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <images-dir>", file=sys.stderr)
        return 2
    root = Path(sys.argv[1])
    layout_path = root / "layout.json"
    if not layout_path.is_file():
        print("missing layout.json", file=sys.stderr)
        return 1
    layout = json.loads(layout_path.read_text(encoding="utf-8"))
    if layout.get("format") != "ardtt-image-layers-v1":
        print(f"unsupported layout format {layout.get('format')}", file=sys.stderr)
        return 1
    config_file = root / layout["configFile"]
    cfg_bytes = config_file.read_bytes()
    config_save = layout.get("configSavePath") or "config.json"
    layers = layout.get("layers") or []
    if not layers:
        print("layout has no layers", file=sys.stderr)
        return 1

    # Write docker-save tar to stdout (binary).
    # Use a streaming tarfile on sys.stdout.buffer.
    out = sys.stdout.buffer
    with tarfile.open(fileobj=out, mode="w|") as tar:
        add_bytes(tar, config_save, cfg_bytes)
        layer_paths = []
        for layer in layers:
            gz_path = root / layer["file"]
            save_path = layer["savePath"]
            with gzip.open(gz_path, "rb") as gz:
                raw = gz.read()
            add_bytes(tar, save_path, raw)
            layer_paths.append(save_path)
        for extra in layout.get("extras") or []:
            data = (root / extra["file"]).read_bytes()
            add_bytes(tar, extra["savePath"], data)
        repos = layout.get("repositories")
        if repos:
            add_bytes(tar, "repositories", (root / repos).read_bytes())
        manifest = [
            {
                "Config": config_save,
                "RepoTags": layout.get("repoTags") or [],
                "Layers": layer_paths,
            }
        ]
        add_bytes(tar, "manifest.json", (json.dumps(manifest) + "\n").encode("utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
