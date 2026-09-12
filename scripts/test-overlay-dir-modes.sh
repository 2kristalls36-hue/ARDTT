#!/usr/bin/env bash
# Overlay layer directory modes: BuildKit COPY --chmod=644 in FROM scratch
# applies that mode to newly created parent directories. 1.0.52 shipped
# /etc and /opt/ardtt/telemetry as 0644; overlayfs then made Debian /etc
# untraversable and the container crash-looped on first start.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

DOCKERFILE="$ROOT/server/Dockerfile"

python3 - "$DOCKERFILE" <<'PY' || err "overlay COPY --chmod without +x (parent dirs inherit it)"
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
m = re.search(r"^FROM scratch AS overlay\n(.*?)(?=^FROM )", text, re.M | re.S)
if not m:
    raise SystemExit("no FROM scratch AS overlay stage")
body = m.group(1)
bad = []
for line in body.splitlines():
    stripped = line.strip()
    if not stripped.startswith("COPY "):
        continue
    chmod = re.search(r"--chmod=([0-7]+)", stripped)
    if chmod and chmod.group(1) not in ("755", "0755"):
        bad.append(stripped)
    if re.search(r"\s/etc(/|\s|$)", stripped):
        bad.append("COPY into /etc (emits an /etc dir header over Debian): " + stripped)
if bad:
    raise SystemExit("; ".join(bad))
PY

grep -q 'COPY --from=overlay / /' "$DOCKERFILE" || err "scripts still merge via COPY --from=overlay / /"
grep -q 'dnsmasq.conf.tmpl /opt/ardtt/dnsmasq.conf.tmpl' "$DOCKERFILE" \
  || err "dnsmasq template must live under /opt/ardtt in the overlay (not /etc)"
grep -q 'ARDTT_DNS_TMPL:-/opt/ardtt/dnsmasq.conf.tmpl' "$ROOT/server/dns/entrypoint.sh" \
  || err "dns.sh default template must be /opt/ardtt/dnsmasq.conf.tmpl"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 - "$TMP" <<'PY' || err "layer-mode checker"
import io, os, stat, sys, tarfile

root = sys.argv[1]

def write_tar(path, dirs):
    # dirs: list of (name, mode, isdir, data)
    with tarfile.open(path, "w:gz") as t:
        for name, mode, isdir, data in dirs:
            info = tarfile.TarInfo(name=name)
            info.mode = mode
            info.uid = 0
            info.gid = 0
            if isdir:
                info.type = tarfile.DIRTYPE
                info.size = 0
                t.addfile(info)
            else:
                payload = data.encode("utf-8")
                info.type = tarfile.REGTYPE
                info.size = len(payload)
                t.addfile(info, io.BytesIO(payload))

def dirs_without_search(path):
    bad = []
    with tarfile.open(path, "r:gz") as t:
        for m in t.getmembers():
            if m.isdir() and (m.mode & 0o111) != 0o111:
                bad.append("%s mode=%s" % (m.name, oct(m.mode)))
    return bad

good = os.path.join(root, "good.tar.gz")
write_tar(good, [
    ("opt", 0o755, True, ""),
    ("opt/ardtt", 0o755, True, ""),
    ("opt/ardtt/dnsmasq.conf.tmpl", 0o644, False, "listen-address=10.8.0.1\n"),
    ("opt/ardtt/telemetry", 0o755, True, ""),
    ("opt/ardtt/telemetry/app.py", 0o644, False, "x = 1\n"),
    ("entrypoint.sh", 0o755, False, "#!/bin/bash\n"),
])
if dirs_without_search(good):
    raise SystemExit("false positive on 0755 dirs: " + str(dirs_without_search(good)))

bad_tar = os.path.join(root, "bad.tar.gz")
write_tar(bad_tar, [
    ("etc", 0o644, True, ""),
    ("etc/ardtt", 0o644, True, ""),
    ("etc/ardtt/dnsmasq.conf.tmpl", 0o644, False, "x\n"),
    ("opt/ardtt/telemetry", 0o644, True, ""),
    ("opt/ardtt/telemetry/app.py", 0o644, False, "x\n"),
])
found = dirs_without_search(bad_tar)
need = {"etc", "etc/ardtt", "opt/ardtt/telemetry"}
got = {x.split()[0] for x in found}
if not need <= got:
    raise SystemExit("missed 0644 dirs: %s" % found)
print("OK synthetic overlay tars")
PY

for layer in "$@"; do
  python3 - "$layer" <<'PY' || err "layer $layer has directories without +x"
import sys, tarfile
path = sys.argv[1]
bad = []
with tarfile.open(path, "r:gz") as t:
    for m in t.getmembers():
        if m.isdir() and (m.mode & 0o111) != 0o111:
            bad.append("%s mode=%s" % (m.name, oct(m.mode)))
if bad:
    raise SystemExit("%s: %s" % (path, "; ".join(bad)))
print("OK", path)
PY
done

if [ "$fail" -ne 0 ]; then
  echo "test-overlay-dir-modes FAILED" >&2
  exit 1
fi
echo "OK test-overlay-dir-modes"
