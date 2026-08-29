#!/usr/bin/env python3
"""Offline unlock helper for ARDTT alpha APKs.

The tester copies a 16-hex device code from the first-launch screen and sends it
to you. This script prints the matching 6-digit code. No network is used.

Keep this file private. Anyone with it (or a decompiled APK) can mint codes.

Usage:
    python3 scripts/alpha-unlock.py A1B2-C3D4-E5F6-7890
    python3 scripts/alpha-unlock.py a1b2c3d4e5f67890
"""

from __future__ import annotations

import hashlib
import hmac
import re
import struct
import sys

# Must stay in lockstep with AlphaGate.kt (MASK xor OBFUSCATED = HMAC secret).
_MASK = bytes(
    [
        0x9A,
        0x37,
        0x19,
        0xE4,
        0x42,
        0x1F,
        0x24,
        0xCD,
        0xA7,
        0xF8,
        0x51,
        0x63,
        0xCF,
        0x92,
        0x3F,
        0x6C,
        0xC1,
        0xAB,
        0x6D,
        0x63,
        0xF1,
        0x5B,
        0xAB,
        0x19,
        0x5E,
        0x7B,
        0xD4,
        0xC9,
        0xF8,
        0xFC,
        0xA2,
        0x85,
    ]
)
_OBFUSCATED = bytes(
    [
        0xF3,
        0x10,
        0x70,
        0x94,
        0x90,
        0x3A,
        0xEB,
        0xB2,
        0x7B,
        0x89,
        0x3F,
        0xE9,
        0xFC,
        0xE9,
        0x18,
        0xF2,
        0xD9,
        0xB6,
        0xFE,
        0x2D,
        0x31,
        0xA2,
        0xB6,
        0x5C,
        0x2A,
        0x69,
        0xCE,
        0x7A,
        0x8E,
        0xB5,
        0x49,
        0x41,
    ]
)
_SECRET = bytes(a ^ b for a, b in zip(_OBFUSCATED, _MASK))

_HEX16 = re.compile(r"^[0-9a-f]{16}$")


def normalize_challenge(raw: str) -> str:
    compact = raw.strip().lower().replace("-", "").replace(" ", "").replace("\n", "").replace("\r", "")
    if not _HEX16.fullmatch(compact):
        raise SystemExit(
            "Ожидаю 16 шестнадцатеричных символов (можно с дефисами), "
            f"получено: {raw!r}"
        )
    return compact


def one_time_code(challenge_hex: str) -> str:
    challenge = normalize_challenge(challenge_hex)
    digest = hmac.new(_SECRET, challenge.encode("ascii"), hashlib.sha256).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return f"{binary % 1_000_000:06d}"


def _self_test() -> None:
    assert one_time_code("0123456789abcdef") == "302184"
    assert one_time_code("A1B2-C3D4-E5F6-0718") == "881716"
    print("self-test ok")


def main(argv: list[str]) -> None:
    if len(argv) != 2 or argv[1] in {"-h", "--help"}:
        print(__doc__.strip(), file=sys.stderr)
        raise SystemExit(2)
    if argv[1] == "--self-test":
        _self_test()
        return
    print(one_time_code(argv[1]))


if __name__ == "__main__":
    main(sys.argv)
