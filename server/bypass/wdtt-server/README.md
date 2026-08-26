# WDTT server (Path B)

Vendored **WDTT Server v2** sources used to build `wdtt-server` for the bypass
container (`-listen-raw` RAW/WRAP path).

Upstream origin (GPL-3.0):

- https://github.com/amurcanov/proxy-turn-vk-android

The ARDTT image applies `../patches/raw-subnet.patch` so the RAW TUN uses
`10.9.0.0/24` (gateway `10.9.0.1`), matching provision/`users.json`.
