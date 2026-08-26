# Bypass server (Path B, RAW)

Vendored sources used to build `wdtt-server` for the bypass container
(`-listen-raw` RAW/WRAP path — **not** classic WireGuard-over-TURN).

## Provenance

| Line | Repo | Role for ARDTT |
|------|------|----------------|
| Classic WDTT | https://github.com/amurcanov/proxy-turn-vk-android | WG over TURN/DTLS — **not** our Path B mode |
| **qWDTT / SpaceNeuroX** | https://github.com/SpaceNeuroX/proxy-turn-vk-android | **RAW** (`-listen-raw`, `raw.go`) — source of this tree |

Directory name `wdtt-server` is historical; the product is **ARDTT**
(Amnezia + RAW Dial via TURN). License: GPL-3.0 (see root NOTICE / LICENSE).

The ARDTT image applies `../patches/raw-subnet.patch` so the RAW TUN uses
`10.9.0.0/24` (gateway `10.9.0.1`), matching provision/`users.json`.
