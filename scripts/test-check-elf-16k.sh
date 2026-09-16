#!/usr/bin/env bash
# check-elf-16k.py: 4 KiB LOAD fails, 16 KiB LOAD passes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="$ROOT/scripts/check-elf-16k.py"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

python3 - "$WORKDIR" <<'PY'
import struct, pathlib, sys
out = pathlib.Path(sys.argv[1])

def elf64_with_align(path: pathlib.Path, align: int) -> None:
    # Minimal ELF64 LE, ET_DYN, one PT_LOAD. Offsets match check-elf-16k.py.
    ehsize = 64
    phentsize = 56
    phoff = ehsize
    data = bytearray(ehsize + phentsize)
    data[0:4] = b"\x7fELF"
    data[4] = 2  # ELFCLASS64
    data[5] = 1  # ELFDATA2LSB
    data[6] = 1
    struct.pack_into("<H", data, 16, 3)  # ET_DYN
    struct.pack_into("<H", data, 18, 183)  # EM_AARCH64
    struct.pack_into("<I", data, 20, 1)
    struct.pack_into("<Q", data, 32, phoff)
    struct.pack_into("<H", data, 52, ehsize)
    struct.pack_into("<H", data, 54, phentsize)
    struct.pack_into("<H", data, 56, 1)
    struct.pack_into("<I", data, phoff, 1)  # PT_LOAD
    struct.pack_into("<Q", data, phoff + 48, align)
    path.write_bytes(bytes(data))

def elf32_with_align(path: pathlib.Path, align: int) -> None:
    ehsize = 52
    phentsize = 32
    phoff = ehsize
    data = bytearray(ehsize + phentsize)
    data[0:4] = b"\x7fELF"
    data[4] = 1  # ELFCLASS32
    data[5] = 1
    data[6] = 1
    struct.pack_into("<H", data, 16, 3)
    struct.pack_into("<H", data, 18, 40)  # EM_ARM
    struct.pack_into("<I", data, 20, 1)
    struct.pack_into("<I", data, 28, phoff)
    struct.pack_into("<H", data, 40, ehsize)
    struct.pack_into("<H", data, 42, phentsize)
    struct.pack_into("<H", data, 44, 1)
    struct.pack_into("<I", data, phoff, 1)  # PT_LOAD
    struct.pack_into("<I", data, phoff + 28, align)
    path.write_bytes(bytes(data))

elf64_with_align(out / "page4k.so", 0x1000)
elf64_with_align(out / "page16k.so", 0x4000)
elf32_with_align(out / "armv7-4k.so", 0x1000)
PY

if python3 "$PY" "$WORKDIR/page4k.so"; then
  echo "expected 4KiB ELF64 to fail" >&2
  exit 1
fi
python3 "$PY" "$WORKDIR/page16k.so"
# 16 KB page devices are 64-bit; armeabi-v7a must not fail the check.
python3 "$PY" "$WORKDIR/armv7-4k.so"

echo "OK test-check-elf-16k"
