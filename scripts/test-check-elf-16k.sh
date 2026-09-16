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

elf64_with_align(out / "page4k.so", 0x1000)
elf64_with_align(out / "page16k.so", 0x4000)
PY

if python3 "$PY" "$WORKDIR/page4k.so"; then
  echo "expected 4KiB ELF to fail" >&2
  exit 1
fi
python3 "$PY" "$WORKDIR/page16k.so"

echo "OK test-check-elf-16k"
