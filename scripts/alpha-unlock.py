#!/usr/bin/env python3
"""Офлайн-разблокировка альфа-сборки ARDTT.

Запуск в терминале (интерактивно — просто вставляете код с телефона):

    python3 scripts/alpha-unlock.py

Или сразу с кодом:

    python3 scripts/alpha-unlock.py A1B2-C3D4-E5F6-7890

Интернет не нужен. Файл не отдавать тестерам.
"""

from __future__ import annotations

import hashlib
import hmac
import re
import struct
import subprocess
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


class BadChallenge(ValueError):
    pass


def normalize_challenge(raw: str) -> str:
    compact = (
        raw.strip()
        .lower()
        .replace("-", "")
        .replace(" ", "")
        .replace("\n", "")
        .replace("\r", "")
    )
    if not _HEX16.fullmatch(compact):
        raise BadChallenge(
            "Ожидаю 16 шестнадцатеричных символов (можно с дефисами), "
            f"получено: {raw.strip()!r}"
        )
    return compact


def one_time_code(challenge_hex: str) -> str:
    challenge = normalize_challenge(challenge_hex)
    digest = hmac.new(_SECRET, challenge.encode("ascii"), hashlib.sha256).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return f"{binary % 1_000_000:06d}"


def copy_to_clipboard(text: str) -> bool:
    for cmd in (
        ["wl-copy"],
        ["xclip", "-selection", "clipboard"],
        ["pbcopy"],
        ["clip"],
    ):
        try:
            subprocess.run(
                cmd,
                input=text.encode("utf-8"),
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            return True
        except (FileNotFoundError, subprocess.CalledProcessError, OSError):
            continue
    return False


def print_unlock_code(otp: str, *, labeled: bool) -> None:
    if labeled:
        copied = copy_to_clipboard(otp)
        extra = "  (скопирован)" if copied else ""
        print(f"Код разблокировки: {otp}{extra}")
    else:
        print(otp)


def interactive_loop() -> None:
    print("ARDTT — офлайн-разблокировка альфа-сборки")
    print("Вставьте код устройства с телефона и нажмите Enter.")
    print("Пустая строка или Ctrl+C — выход. Интернет не нужен.\n")
    while True:
        try:
            raw = input("Код устройства: ")
        except EOFError:
            print()
            return
        except KeyboardInterrupt:
            print()
            return
        if not raw.strip():
            return
        try:
            print_unlock_code(one_time_code(raw), labeled=True)
        except BadChallenge as exc:
            print(f"Ошибка: {exc}")
        print()


def codes_from_stdin() -> None:
    had_any = False
    for line in sys.stdin:
        raw = line.strip()
        if not raw:
            continue
        had_any = True
        print_unlock_code(one_time_code(raw), labeled=False)
    if not had_any:
        raise SystemExit("Нет кода устройства во вводе.")


def _self_test() -> None:
    assert one_time_code("0123456789abcdef") == "302184"
    assert one_time_code("A1B2-C3D4-E5F6-0718") == "881716"
    print("self-test ok")


def main(argv: list[str]) -> None:
    args = argv[1:]
    if args and args[0] in {"-h", "--help"}:
        print(__doc__.strip())
        return
    if args == ["--self-test"]:
        _self_test()
        return
    if args:
        try:
            for raw in args:
                print_unlock_code(one_time_code(raw), labeled=False)
        except BadChallenge as exc:
            raise SystemExit(str(exc)) from exc
        return
    if sys.stdin.isatty():
        interactive_loop()
        return
    try:
        codes_from_stdin()
    except BadChallenge as exc:
        raise SystemExit(str(exc)) from exc


if __name__ == "__main__":
    try:
        main(sys.argv)
    except KeyboardInterrupt:
        sys.exit(130)
