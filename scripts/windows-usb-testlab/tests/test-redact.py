#!/usr/bin/env python3
"""RED tests for lab redaction. Run from repo; expects lib/redact.py."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "lib"))

import redact  # noqa: E402


def expect(name: str, cond: bool, detail: str = "") -> None:
    if cond:
        print(f"OK {name}")
        return
    raise SystemExit(f"FAIL {name}: {detail}")


def main() -> None:
    text = "\n".join(
        [
            "Authorization: Bearer super-secret-token",
            "Cookie: remixsid=abc; session=xyz",
            "Set-Cookie: foo=bar",
            '{"password":"hunter2","token":"tok123","ok":"keep"}',
            "https://vk.com/call?access_token=ATOKEN&hash=CALLHASHVALUE",
            "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----",
            "echo 's3cret' | sudo -S apt-get update",
            "serial=ABCDEF123 and ssid=HomeNet bssid=aa:bb:cc:dd:ee:ff",
        ]
    )
    aliases = {
        "ABCDEF123": "device-01",
        "HomeNet": "wifi-01",
        "aa:bb:cc:dd:ee:ff": "bssid-01",
    }
    out = redact.redact_text(text, aliases=aliases, max_chars=50_000)

    expect("auth header", "super-secret-token" not in out and "[REDACTED]" in out)
    expect("cookie header", "remixsid=abc" not in out)
    expect("json password", "hunter2" not in out and '"ok":"keep"' in out)
    expect("query token", "ATOKEN" not in out)
    expect("private key", "BEGIN PRIVATE KEY" not in out or "REDACTED_PRIVATE_KEY" in out)
    expect("sudo pipe", "s3cret" not in out)
    expect("serial alias", "ABCDEF123" not in out and "device-01" in out)
    expect("ssid alias", "HomeNet" not in out and "wifi-01" in out)
    expect("bssid alias", "aa:bb:cc:dd:ee:ff" not in out and "bssid-01" in out)
    expect("keep public json key", '"ok":"keep"' in out)

    long = "A" * 3000
    trimmed = redact.redact_text(long, max_chars=100)
    expect("truncate", len(trimmed) > 100 and "truncated" in trimmed)

    print("all redact tests passed")


if __name__ == "__main__":
    main()
