# go_client (Path B)

Vendored Path B client (`go_client` / android-client lineage) for ARDTT RAW dial.

## Provenance

| Layer | Origin |
|-------|--------|
| Classic WDTT idea (WG over TURN/DTLS) | [amurcanov/proxy-turn-vk-android](https://github.com/amurcanov/proxy-turn-vk-android) — **not** ARDTT’s Path B mode |
| **RAW dial** (`-raw`, WRAP, no nested WG/DTLS on that path) | [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (**qWDTT**), GPL-3.0 |

See root [`NOTICE`](../../NOTICE) and [`docs/LEGEND.md`](../../docs/LEGEND.md).

Build Android `libclient.so`:

```bash
./scripts/build-bypass-client.sh
```

**ARDTT delta vs upstream:** `-n` workers honour 1–9 (no force to multiples of 9). See `main.go`.
