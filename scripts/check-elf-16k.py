#!/usr/bin/env python3
"""Fail if an ELF PT_LOAD segment is aligned below 16 KiB.

Android 15 16 KB-page devices (OnePlus 15 / Snapdragon 8 Elite) reject
jniLibs with 4 KiB LOAD alignment as an invalid package.
"""
from __future__ import annotations

import struct
import sys
from pathlib import Path

MIN_ALIGN = 16384


def load_aligns(path: Path) -> list[int]:
    data = path.read_bytes()
    if data[:4] != b"\x7fELF":
        raise SystemExit(f"not ELF: {path}")
    if data[4] != 2 or data[5] != 1:
        raise SystemExit(f"need ELF64 little-endian: {path}")
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    aligns: list[int] = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != 1:
            continue
        p_align = struct.unpack_from("<Q", data, off + 48)[0]
        aligns.append(p_align)
    if not aligns:
        raise SystemExit(f"no PT_LOAD: {path}")
    return aligns


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: check-elf-16k.py <elf> [elf...]", file=sys.stderr)
        return 2
    failed = 0
    for raw in argv[1:]:
        path = Path(raw)
        aligns = load_aligns(path)
        bad = [a for a in aligns if a < MIN_ALIGN]
        shown = ",".join(hex(a) for a in aligns)
        if bad:
            print(f"FAIL {path} PT_LOAD align={shown} need >= {hex(MIN_ALIGN)}")
            failed = 1
        else:
            print(f"OK {path} PT_LOAD align={shown}")
    return failed


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
