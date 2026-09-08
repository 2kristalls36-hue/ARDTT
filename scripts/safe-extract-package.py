#!/usr/bin/env python3
"""Safely extract an ARDTT server package tarball.

Rejects absolute paths, .., symlink/hardlink members, and oversized archives.
Does not execute anything from the archive.
"""
from __future__ import annotations

import argparse
import gzip
import os
import stat
import sys
import tarfile

MAX_MEMBER_BYTES = 8 * 1024 * 1024 * 1024  # 8 GiB per member (image layers)
MAX_TOTAL_BYTES = 16 * 1024 * 1024 * 1024
MAX_MEMBERS = 4096
BLOCK = tarfile.BLOCKSIZE


class UnsafeTar(ValueError):
    pass


def _is_unsafe_name(name: str) -> str | None:
    n = name.replace("\\", "/")
    if n.startswith("/") or n.startswith("\\") or (len(n) >= 2 and n[1] == ":"):
        return "absolute path"
    parts = [p for p in n.split("/") if p not in ("", ".")]
    if any(p == ".." for p in parts):
        return "path traversal"
    return None


def validate_and_extract(src: str, dest: str) -> None:
    os.makedirs(dest, exist_ok=True)
    dest = os.path.abspath(dest)
    total = 0
    members = 0
    with gzip.open(src, "rb") as gz:
        with tarfile.open(fileobj=gz, mode="r|") as tar:
            for member in tar:
                members += 1
                if members > MAX_MEMBERS:
                    raise UnsafeTar("too many tar members")
                err = _is_unsafe_name(member.name)
                if err:
                    raise UnsafeTar(f"{err}: {member.name!r}")
                if member.issym() or member.islnk():
                    raise UnsafeTar(f"link not allowed: {member.name!r}")
                if member.isfile():
                    if member.size < 0 or member.size > MAX_MEMBER_BYTES:
                        raise UnsafeTar(f"member too large: {member.name!r}")
                    total += member.size
                    if total > MAX_TOTAL_BYTES:
                        raise UnsafeTar("unpacked size exceeds limit")
                elif not member.isdir():
                    raise UnsafeTar(f"unsupported type for {member.name!r}")
                target = os.path.abspath(os.path.join(dest, member.name))
                if not (target == dest or target.startswith(dest + os.sep)):
                    raise UnsafeTar(f"escapes dest: {member.name!r}")
                tar.extract(member, path=dest, set_attrs=False)
    # Second pass: no leftover links (old tar implementations).
    for root, dirs, files in os.walk(dest):
        for name in dirs + files:
            path = os.path.join(root, name)
            st = os.lstat(path)
            if stat.S_ISLNK(st.st_mode):
                raise UnsafeTar(f"symlink after extract: {path}")
            if st.st_nlink > 1 and stat.S_ISREG(st.st_mode):
                raise UnsafeTar(f"hardlink after extract: {path}")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("archive")
    p.add_argument("dest")
    args = p.parse_args()
    try:
        validate_and_extract(args.archive, args.dest)
    except UnsafeTar as e:
        print(f"unsafe package: {e}", file=sys.stderr)
        return 2
    except OSError as e:
        print(f"extract failed: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
