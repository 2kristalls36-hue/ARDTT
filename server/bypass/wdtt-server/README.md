# Bypass server (Path B, RAW)

Vendored **RAW-only** listener used by the `bypass` container
(`wdtt-server -listen-raw`). Classic WDTT (WireGuard / DTLS / Telegram bot /
admin HTTP) is not compiled into this binary.

## Provenance

| Line | Repo | Role for ARDTT |
|------|------|----------------|
| Classic WDTT | https://github.com/amurcanov/proxy-turn-vk-android | WG over TURN/DTLS — **not** Path B |
| **qWDTT / SpaceNeuroX** | https://github.com/SpaceNeuroX/proxy-turn-vk-android | RAW (`-listen-raw`, WRAP) — origin of this tree |

Directory name `wdtt-server` is historical; the product is **ARDTT**.
License: GPL-3.0 (see root NOTICE / LICENSE).

RAW TUN: `wdttraw0` on `10.9.0.0/24` (gateway `10.9.0.1`), matching
provision/`users.json`. `SIGHUP` reloads `passwords.json` and WRAP keys.
