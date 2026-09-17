#!/usr/bin/env python3
"""Fail if a 64-bit ELF PT_LOAD segment is aligned below 16 KiB.

Android 15 16 KB-page devices (OnePlus 15 / Snapdragon 8 Elite) reject
arm64/x86_64 jniLibs with 4 KiB LOAD alignment as an invalid package.
32-bit ELF (armeabi-v7a) is not loaded on those devices.
"""
from __future__ import annotations

import struct
import sys
from pathlib import Path

MIN_ALIGN = 16384
PT_LOAD = 1


def load_aligns(path: Path) -> tuple[int, list[int]]:
    data = path.read_bytes()
    if data[:4] != b"\x7fELF":
        raise SystemExit(f"not ELF: {path}")
    if data[5] != 1:
        raise SystemExit(f"need little-endian ELF: {path}")
    ei_class = data[4]
    if ei_class == 2:
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_phentsize = struct.unpack_from("<H", data, 54)[0]
        e_phnum = struct.unpack_from("<H", data, 56)[0]
        align_off = 48
        align_fmt = "<Q"
        bits = 64
    elif ei_class == 1:
        e_phoff = struct.unpack_from("<I", data, 28)[0]
        e_phentsize = struct.unpack_from("<H", data, 42)[0]
        e_phnum = struct.unpack_from("<H", data, 44)[0]
        align_off = 28
        align_fmt = "<I"
        bits = 32
    else:
        raise SystemExit(f"unknown ELF class {ei_class}: {path}")
    aligns: list[int] = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        aligns.append(struct.unpack_from(align_fmt, data, off + align_off)[0])
    if not aligns:
        raise SystemExit(f"no PT_LOAD: {path}")
    return bits, aligns


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: check-elf-16k.py <elf> [elf...]", file=sys.stderr)
        return 2
    failed = 0
    for raw in argv[1:]:
        path = Path(raw)
        bits, aligns = load_aligns(path)
        shown = ",".join(hex(a) for a in aligns)
        if bits == 32:
            print(f"SKIP {path} ELF32 PT_LOAD align={shown}")
            continue
        bad = [a for a in aligns if a < MIN_ALIGN]
        if bad:
            print(f"FAIL {path} PT_LOAD align={shown} need >= {hex(MIN_ALIGN)}")
            failed = 1
        else:
            print(f"OK {path} PT_LOAD align={shown}")
    return failed


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
