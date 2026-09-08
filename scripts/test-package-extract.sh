#!/usr/bin/env bash
# Safe extract: reject traversal, links, and execute nothing from the archive.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }
PY="$ROOT/scripts/safe-extract-package.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

good="$TMP/good.tar.gz"
mkdir -p "$TMP/src"
echo hi > "$TMP/src/install.sh"
tar -czf "$good" -C "$TMP/src" install.sh
mkdir -p "$TMP/out-good"
python3 "$PY" "$good" "$TMP/out-good" || err "good archive rejected"
[ -f "$TMP/out-good/install.sh" ] || err "missing extracted install.sh"
ok "extracts regular files"

# Path traversal
mkdir -p "$TMP/evil"
python3 - "$TMP/evil.tar.gz" <<'PY'
import tarfile, io, gzip, sys
buf=io.BytesIO()
with tarfile.open(fileobj=buf, mode="w") as tar:
    info=tarfile.TarInfo(name="../escape.sh")
    data=b"evil"
    info.size=len(data)
    tar.addfile(info, io.BytesIO(data))
open(sys.argv[1],"wb").write(gzip.compress(buf.getvalue()))
PY
set +e
python3 "$PY" "$TMP/evil.tar.gz" "$TMP/out-evil"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "traversal archive must be rejected"
[ ! -f "$TMP/out-evil/escape.sh" ] || err "escaped file written"
ok "rejects .. paths"

# Symlink member
python3 - "$TMP/link.tar.gz" <<'PY'
import tarfile, io, gzip, sys
buf=io.BytesIO()
with tarfile.open(fileobj=buf, mode="w") as tar:
    info=tarfile.TarInfo(name="link")
    info.type=tarfile.SYMTYPE
    info.linkname="/etc/passwd"
    tar.addfile(info)
open(sys.argv[1],"wb").write(gzip.compress(buf.getvalue()))
PY
set +e
python3 "$PY" "$TMP/link.tar.gz" "$TMP/out-link"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "symlink archive must be rejected"
ok "rejects symlinks"

if [ "$fail" -ne 0 ]; then
  echo "package extract tests failed" >&2
  exit 1
fi
ok "package extract safety"
