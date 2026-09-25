"""Redact secrets in diagnostic text before a shareable archive is built.

Mirrors android TelemetryRedactor plus stable aliases for device identifiers.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from typing import Mapping

PEM_BLOCK = re.compile(
    r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
    re.IGNORECASE | re.DOTALL,
)
JSON_SECRET = re.compile(
    r'(?i)("(?:privateKey|password|sudoPassword|keyPassphrase|passphrase|token|'
    r'access_token|refresh_token|remixsid|cookie|authorization)"\s*:\s*")([^"]*)(")'
)
QUERY_SECRET = re.compile(
    r"(?i)((?:access_token|refresh_token|token|password|passphrase|call[_-]?hash)=)[^&\s]+"
)
SENSITIVE_HEADER = re.compile(
    r"(?im)^(Authorization|Cookie|Set-Cookie|X-Auth-[^:]*):\s*.*$"
)
SUDO_PASSWORD_PIPE = re.compile(
    r"""(?i)echo\s+(['"]).*?\1\s*\|\s*sudo\s+-S"""
)
CALL_URL = re.compile(
    r"(?i)(https?://[^\s]*?(?:vk\.com|vk\.ru)[^\s]*?(?:call|calls)[^\s]*)"
)

DEFAULT_MAX_CHARS = 2048


def _alias_keys(aliases: Mapping[str, str]) -> list[str]:
    return sorted((k for k in aliases if k), key=len, reverse=True)


def apply_aliases(text: str, aliases: Mapping[str, str] | None) -> str:
    if not aliases:
        return text
    out = text
    for src in _alias_keys(aliases):
        dst = aliases[src]
        if not dst:
            continue
        out = out.replace(src, dst)
    return out


def redact_text(
    raw: str,
    aliases: Mapping[str, str] | None = None,
    max_chars: int = DEFAULT_MAX_CHARS,
) -> str:
    result = raw
    result = PEM_BLOCK.sub("[REDACTED_PRIVATE_KEY]", result)
    result = JSON_SECRET.sub(r"\1[REDACTED]\3", result)
    result = QUERY_SECRET.sub(r"\1[REDACTED]", result)
    result = SENSITIVE_HEADER.sub(r"\1: [REDACTED]", result)
    result = SUDO_PASSWORD_PIPE.sub("echo [REDACTED] | sudo -S", result)
    result = CALL_URL.sub("[REDACTED_CALL_URL]", result)
    result = apply_aliases(result, aliases)
    if max_chars > 0 and len(result) > max_chars:
        removed = len(result) - max_chars
        result = result[:max_chars] + f"…[truncated {removed} chars]"
    return result


def load_aliases(path: str | None) -> dict[str, str]:
    if not path:
        return {}
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        raise ValueError("aliases file must be a JSON object")
    return {str(k): str(v) for k, v in data.items()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Redact secrets in diagnostic text")
    parser.add_argument("paths", nargs="*", help="files to redact in place; stdin if omitted")
    parser.add_argument("--aliases", help="JSON object of exact string -> alias")
    parser.add_argument("--max-chars", type=int, default=0, help="0 = no truncation")
    parser.add_argument("--in-place", action="store_true")
    parser.add_argument("--output", help="write a single input to this path")
    args = parser.parse_args(argv)
    aliases = load_aliases(args.aliases)
    max_chars = args.max_chars if args.max_chars > 0 else 10**9

    if not args.paths:
        text = sys.stdin.read()
        sys.stdout.write(redact_text(text, aliases=aliases, max_chars=max_chars))
        return 0

    if args.output and len(args.paths) != 1:
        parser.error("--output requires exactly one input path")

    for path in args.paths:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        redacted = redact_text(text, aliases=aliases, max_chars=max_chars)
        dest = args.output if args.output else path
        if args.output or args.in_place:
            with open(dest, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(redacted)
        else:
            sys.stdout.write(redacted)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
