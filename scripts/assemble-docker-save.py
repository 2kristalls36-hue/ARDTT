#!/usr/bin/env python3
"""Stream-reassemble ardtt-image-layers-v1 into a docker-save tar on stdout.

Used as: python3 assemble-docker-save.py <images-dir> | docker load
Avoids writing a second full images/ardtt.tar onto a tight VPS disk. Each layer
is decompressed on the fly (no full layer in memory) and its diff ID is checked
against layout.json while streaming; a mismatch aborts with a non-zero exit so
`docker load` never applies a layer that does not match the manifest.
"""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import sys
import tarfile
from pathlib import Path


class HashingReader:
    """Read-through wrapper: hashes what has been read, refuses to over-read."""

    def __init__(self, fh, expect_size: int, want_hex: str, label: str) -> None:
        self._fh = fh
        self._left = expect_size
        self._digest = hashlib.sha256()
        self._want = want_hex.lower()
        self._label = label

    def read(self, n: int = -1) -> bytes:
        if self._left <= 0:
            return b""
        if n < 0 or n > self._left:
            n = self._left
        data = self._fh.read(n)
        if not data:
            raise SystemExit(f"{self._label}: stream ended {self._left} bytes early")
        self._left -= len(data)
        self._digest.update(data)
        if self._left == 0:
            if self._fh.read(1):
                raise SystemExit(f"{self._label}: gz stream longer than layout rawSize")
            got = self._digest.hexdigest()
            if self._want and got != self._want:
                raise SystemExit(f"{self._label}: diff ID {got} != layout {self._want}")
        return data


def add_bytes(tar: tarfile.TarFile, name: str, data: bytes, mode: int = 0o644) -> None:
    info = tarfile.TarInfo(name=name)
    info.size = len(data)
    info.mode = mode
    tar.addfile(info, io.BytesIO(data))


def raw_size_of(gz_path: Path) -> int:
    size = 0
    with gzip.open(gz_path, "rb") as gz:
        while True:
            chunk = gz.read(4 * 1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
    return size


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
    for layer in layers:
        if not (root / layer["file"]).is_file():
            print(f"missing layer file {layer['file']}", file=sys.stderr)
            return 1

    out = sys.stdout.buffer
    with tarfile.open(fileobj=out, mode="w|") as tar:
        add_bytes(tar, config_save, cfg_bytes)
        layer_paths = []
        for layer in layers:
            gz_path = root / layer["file"]
            save_path = layer["savePath"]
            want = str(layer.get("diffId") or layer.get("sha256") or "").removeprefix("sha256:")
            raw_size = int(layer.get("rawSize") or 0)
            if raw_size <= 0:
                raw_size = raw_size_of(gz_path)
            info = tarfile.TarInfo(name=save_path)
            info.size = raw_size
            info.mode = 0o644
            with gzip.open(gz_path, "rb") as gz:
                tar.addfile(info, HashingReader(gz, raw_size, want, layer["file"]))
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
