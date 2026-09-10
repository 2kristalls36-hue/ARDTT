#!/usr/bin/env bash
# Host-file repack must restore SETGID compose, ready.sh, and overlay installer
# into an otherwise stale ardtt-server archive (image blob left as-is).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
PKG="$WORKDIR/ardtt-server-${VER}-linux-amd64.tar.gz"
bash "$ROOT/scripts/make-fake-server-package.sh" "$PKG" amd64 "$VER"
python3 "$ROOT/scripts/safe-extract-package.py" "$PKG" "$WORKDIR/old"
printf '%s\n' '#!/bin/sh' 'echo stale' > "$WORKDIR/old/install.sh"
chmod 755 "$WORKDIR/old/install.sh"
rm -f "$WORKDIR/old/ready.sh"
python3 - "$WORKDIR/old/docker-compose.yml" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
lines = [ln for ln in p.read_text(encoding="utf-8").splitlines(True) if "SETGID" not in ln and "SETUID" not in ln]
p.write_text("".join(lines), encoding="utf-8")
PY
image_before="$(sha256sum "$WORKDIR/old/images/ardtt.tar" | awk '{print $1}')"
(
  cd "$WORKDIR/old"
  tar -czf "$PKG" manifest.json SHA256SUMS README.md DEPLOY_VERSION third-party.lock.json \
    install.sh install-lib scripts docker-compose.yml docker-compose.exit.yml .env.example \
    images bin vendor
)

bash "$ROOT/scripts/repack-server-host-files.sh" "$PKG"
python3 "$ROOT/scripts/safe-extract-package.py" "$PKG" "$WORKDIR/new"
grep -q 'overlay_ready_script' "$WORKDIR/new/install.sh" || err "install.sh not restored"
grep -q 'SETGID' "$WORKDIR/new/docker-compose.yml" || err "SETGID compose not restored"
[ -f "$WORKDIR/new/ready.sh" ] || err "ready.sh missing after repack"
image_after="$(sha256sum "$WORKDIR/new/images/ardtt.tar" | awk '{print $1}')"
[ "$image_before" = "$image_after" ] || err "image blob must not change"
[ -f "$WORKDIR/new/scripts/assemble-docker-save.py" ] || err "assemble script missing after repack"
grep -q 'ready.sh' "$WORKDIR/new/SHA256SUMS" || err "SHA256SUMS missing ready.sh"
python3 - "$WORKDIR/new/manifest.json" <<'PY' || err "manifest ready.sh hash"
import hashlib, json, pathlib, sys
stage = pathlib.Path(sys.argv[1]).parent
man = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
got = man["files"]["ready.sh"]
want = hashlib.sha256((stage / "ready.sh").read_bytes()).hexdigest()
assert got == want, (got, want)
PY
[ -f "$WORKDIR/new/vendor/docker.tgz" ] || err "vendor/docker.tgz dropped on repack"

if [ "$fail" -ne 0 ]; then
  echo "repack host files tests failed" >&2
  exit 1
fi
ok "repack restores installer/compose/ready.sh and keeps the image blob"
