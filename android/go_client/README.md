# go_client (Path B)

Vendored WDTT Path B client (`go_client` / android-client lineage) from
[WDTT](https://github.com/amurcanov/proxy-turn-vk-android), GPL-3.0.

Build Android `libclient.so`:

```bash
./scripts/build-bypass-client.sh
```

**nonameVPN delta vs upstream:** `-n` workers honour 1–9 (no force to multiples of 9). See `main.go`.
