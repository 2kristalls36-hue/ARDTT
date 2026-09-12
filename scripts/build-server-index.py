#!/usr/bin/env python3
"""Emit partial-deploy assets + index next to a packed ardtt-server archive.

Given the fully assembled package stage directory (what pack-server-package.sh
tars into ardtt-server-<ver>-linux-<arch>.tar.gz), write into <out-dir>:

  ardtt-server-<ver>-linux-<arch>-hostfiles.tar.gz   everything except layers/Engine/Compose
  ardtt-server-<ver>-linux-<arch>-layer-NN-<16hex>.tar.gz   one asset per image layer
  ardtt-docker-engine-<engver>-linux-<arch>.tgz       vendor/docker.tgz verbatim
  ardtt-docker-compose-<cver>-linux-<arch>            bin/docker-compose verbatim
  ardtt-server-<ver>-linux-<arch>.index.json          ardtt-server-index-v1

The index is the single trust root for a partial download: the VPS verifies it
against the GitHub asset digest / SHA256SUMS-server.txt and every component
against the SHA-256 recorded here. Layers are keyed by diff ID so the VPS
downloads only the ones missing from its local cache.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import tarfile
import time
from pathlib import Path

INDEX_FORMAT = "ardtt-server-index-v1"
EXCLUDE_TOP = ("vendor", "bin")
EXCLUDE_PREFIX = ("images/layers/",)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def hostfile_members(stage: Path) -> list[Path]:
    out: list[Path] = []
    for root, dirs, files in os.walk(stage):
        rel_root = Path(root).relative_to(stage)
        dirs.sort()
        for name in sorted(files):
            rel = (rel_root / name).as_posix() if str(rel_root) != "." else name
            if rel.split("/", 1)[0] in EXCLUDE_TOP:
                continue
            if any(rel.startswith(p) for p in EXCLUDE_PREFIX):
                continue
            out.append(Path(rel))
    return out


def write_hostfiles(stage: Path, members: list[Path], out: Path) -> None:
    tmp = out.with_name(out.name + ".part")
    with open(tmp, "wb") as raw:
        # Deterministic gzip + tar metadata: identical host files -> identical asset.
        with tarfile.open(fileobj=raw, mode="w:gz", compresslevel=9) as tar:
            tar.format = tarfile.PAX_FORMAT
            for rel in members:
                src = stage / rel
                info = tar.gettarinfo(str(src), arcname=rel.as_posix())
                info.uid = info.gid = 0
                info.uname = info.gname = "root"
                info.mtime = 0
                # Keep executable bit, normalise the rest.
                info.mode = 0o755 if (src.stat().st_mode & 0o111) else 0o644
                with open(src, "rb") as fh:
                    tar.addfile(info, fh)
    os.replace(tmp, out)


def place(src: Path, dst: Path) -> None:
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copyfile(src, dst)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", help="assembled package stage directory")
    ap.add_argument("out_dir", help="dist directory (where the full archive lives)")
    ap.add_argument("--version", required=True)
    ap.add_argument("--arch", required=True, choices=("amd64", "arm64"))
    ap.add_argument("--package", required=True, help="path to the full ardtt-server-*.tar.gz")
    ap.add_argument("--release-tag", default="")
    ap.add_argument("--commit", default="unknown")
    ap.add_argument("--engine-version", default="")
    ap.add_argument("--compose-version", default="")
    args = ap.parse_args()

    stage = Path(args.stage)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    ver, arch = args.version, args.arch
    base = f"ardtt-server-{ver}-linux-{arch}"

    manifest = json.loads((stage / "manifest.json").read_text(encoding="utf-8"))
    layout_path = stage / "images" / "layout.json"
    if not layout_path.is_file():
        raise SystemExit("stage has no images/layout.json (layered image required for partial deploy)")
    layout = json.loads(layout_path.read_text(encoding="utf-8"))

    # 1. host files
    members = hostfile_members(stage)
    for required in ("manifest.json", "install.sh", "fetch-and-install.sh", "docker-compose.yml",
                     "images/layout.json", "images/config.json", "install-lib/common.sh",
                     "scripts/assemble-docker-save.py", "scripts/layer-cache.py",
                     "scripts/safe-extract-package.py"):
        if Path(required) not in members:
            raise SystemExit(f"stage is missing {required}")
    hostfiles_name = f"{base}-hostfiles.tar.gz"
    hostfiles_path = out_dir / hostfiles_name
    write_hostfiles(stage, members, hostfiles_path)
    hostfiles = {
        "asset": hostfiles_name,
        "sha256": sha256_file(hostfiles_path),
        "size": hostfiles_path.stat().st_size,
        "files": {m.as_posix(): sha256_file(stage / m) for m in members},
    }

    # 2. layers
    layers_out = []
    for layer in layout.get("layers") or []:
        src = stage / "images" / layer["file"]
        if not src.is_file():
            raise SystemExit(f"stage is missing layer images/{layer['file']}")
        diff_hex = str(layer.get("diffId") or layer.get("sha256")).removeprefix("sha256:")
        asset = f"{base}-layer-{int(layer['index']):02d}-{diff_hex[:16]}.tar.gz"
        dst = out_dir / asset
        place(src, dst)
        layers_out.append(
            {
                "index": int(layer["index"]),
                "file": layer["file"],
                "diffId": "sha256:" + diff_hex,
                "asset": asset,
                "sha256": sha256_file(dst),
                "gzSize": dst.stat().st_size,
                "rawSize": int(layer.get("rawSize") or 0),
            }
        )

    # 3. Engine + Compose (verbatim upstream artifacts, pinned in third-party.lock.json)
    engine = None
    engine_src = stage / "vendor" / "docker.tgz"
    if engine_src.is_file():
        name = f"ardtt-docker-engine-{args.engine_version or 'unknown'}-linux-{arch}.tgz"
        place(engine_src, out_dir / name)
        engine = {
            "asset": name,
            "path": "vendor/docker.tgz",
            "version": args.engine_version,
            "sha256": sha256_file(engine_src),
            "size": engine_src.stat().st_size,
        }
    compose = None
    compose_src = stage / "bin" / "docker-compose"
    if compose_src.is_file():
        name = f"ardtt-docker-compose-{args.compose_version or 'unknown'}-linux-{arch}"
        place(compose_src, out_dir / name)
        compose = {
            "asset": name,
            "path": "bin/docker-compose",
            "version": args.compose_version,
            "sha256": sha256_file(compose_src),
            "size": compose_src.stat().st_size,
        }

    package = Path(args.package)
    if not package.is_file():
        raise SystemExit(f"missing full package {package}")

    index = {
        "format": INDEX_FORMAT,
        "deployVersion": ver,
        "releaseTag": args.release_tag,
        "commit": args.commit,
        "os": "linux",
        "arch": arch,
        "generatedAt": int(time.time()),
        "package": {
            "asset": package.name,
            "sha256": sha256_file(package),
            "size": package.stat().st_size,
        },
        "hostfiles": hostfiles,
        "image": {
            "tag": (manifest.get("image") or {}).get("tag") or "",
            "id": (manifest.get("image") or {}).get("id") or "",
            "layout": "images/layout.json",
            "layoutSha256": sha256_file(layout_path),
            "configSha256": layout.get("configSha256") or sha256_file(stage / "images" / "config.json"),
            "layerCount": len(layers_out),
            "totalGzSize": sum(x["gzSize"] for x in layers_out),
            "layers": layers_out,
        },
        "engine": engine,
        "compose": compose,
    }
    index_path = out_dir / f"{base}.index.json"
    index_path.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out_dir / f"{base}.index.json.sha256").write_text(sha256_file(index_path) + "\n", encoding="utf-8")
    total_partial = hostfiles["size"] + index["image"]["totalGzSize"]
    print(
        f"index {index_path.name}: hostfiles={hostfiles['size']} layers={len(layers_out)} "
        f"({index['image']['totalGzSize']} gz) engine={(engine or {}).get('size', 0)} "
        f"compose={(compose or {}).get('size', 0)} full={index['package']['size']} "
        f"-> update without Engine/Compose downloads at most {total_partial} bytes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
