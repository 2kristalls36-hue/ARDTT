#!/usr/bin/env bash
# Unit-ish check for fetch-and-install release resolution (no network).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/releases.json" <<'JSON'
[
  {
    "tag_name": "v0.5.259",
    "draft": false,
    "assets": [
      {"name": "ardtt-0.5.259.apk", "browser_download_url": "https://example/a.apk", "size": 1}
    ]
  },
  {
    "tag_name": "v0.5.258",
    "draft": false,
    "assets": [
      {
        "name": "ardtt-server-1.0.46-linux-amd64.tar.gz",
        "browser_download_url": "https://example/ardtt-server-1.0.46-linux-amd64.tar.gz",
        "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "size": 10
      },
      {
        "name": "SHA256SUMS-server.txt",
        "browser_download_url": "https://example/SHA256SUMS-server.txt"
      }
    ]
  }
]
JSON

WANT_VER= ARCH=amd64 python3 - "$TMP/releases.json" <<'PY'
import json, os, re, sys
path = sys.argv[1]
want = os.environ.get("WANT_VER", "").strip()
arch = os.environ["ARCH"]
pat = re.compile(r"^ardtt-server-(\d+\.\d+\.\d+)-linux-(amd64|arm64)\.tar\.gz$", re.I)
releases = json.load(open(path, encoding="utf-8"))
picked = None
for rel in releases:
    if rel.get("draft"):
        continue
    for a in rel.get("assets") or []:
        name = a.get("name") or ""
        m = pat.match(name)
        if not m:
            continue
        ver, aarch = m.group(1), m.group(2).lower()
        if aarch != arch:
            continue
        if want and ver != want:
            continue
        picked = ver
        break
    if picked:
        break
assert picked == "1.0.46", picked
print("OK fetch resolve latest", picked)
PY

WANT_VER=1.0.46 ARCH=amd64 python3 - "$TMP/releases.json" <<'PY'
import json, os, re, sys
path = sys.argv[1]
want = os.environ["WANT_VER"]
arch = os.environ["ARCH"]
pat = re.compile(r"^ardtt-server-(\d+\.\d+\.\d+)-linux-(amd64|arm64)\.tar\.gz$", re.I)
releases = json.load(open(path, encoding="utf-8"))
found = False
for rel in releases:
    for a in rel.get("assets") or []:
        m = pat.match(a.get("name") or "")
        if m and m.group(1) == want and m.group(2).lower() == arch:
            found = True
assert found
print("OK fetch resolve pin", want)
PY

bash -n "$ROOT/server/fetch-and-install.sh"
echo "OK test-fetch-and-install-resolve"
