#!/usr/bin/env bash
# Hostfiles overlay: APK tarball supplies install.sh / Compose / install-lib;
# image layers + Engine/Compose still come from the published GitHub index.
# Unpublished ARDTT_DEPLOY_VERSION + overlay → DONE is the overlay version,
# not PACKAGE_RESOLVE and not the published stack version alone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

ARCH="$(uname -m)"
case "$ARCH" in x86_64|amd64) ARCH=amd64 ;; aarch64|arm64) ARCH=arm64 ;; esac
DIST="$TMP/dist"
mkdir -p "$DIST"
ARDTT_ARDTTCTL_BIN="$TMP/ardttctl"
bash "$ROOT/scripts/build-ardttctl.sh" "$ARCH" "$ARDTT_ARDTTCTL_BIN"
export ARDTT_ARDTTCTL_BIN

VER_PUB=1.0.45
VER_OVER=1.0.54
STAGE_PUB="$TMP/stage-pub"
ARDTT_FAKE_LAYERED=1 ARDTT_FAKE_LAYER_SEED="$VER_PUB" ARDTT_FAKE_STAGE_DIR="$STAGE_PUB" \
  bash "$ROOT/scripts/make-fake-server-package.sh" \
    "$DIST/ardtt-server-${VER_PUB}-linux-${ARCH}.tar.gz" "$ARCH" "$VER_PUB" >/dev/null
python3 "$ROOT/scripts/build-server-index.py" "$STAGE_PUB" "$DIST" --version "$VER_PUB" --arch "$ARCH" \
  --package "$DIST/ardtt-server-${VER_PUB}-linux-${ARCH}.tar.gz" --release-tag "v-$VER_PUB" \
  --engine-version 29.7.2 --compose-version 2.32.4 >/dev/null
( cd "$DIST" && sha256sum ardtt-* > SHA256SUMS-server.txt )

# Overlay tarball: same installer family as git, plus a marker the published
# 1.0.45 hostfiles do not contain. No images/, no Engine, no ardttctl.
OVER_STAGE="$TMP/overlay-stage"
mkdir -p "$OVER_STAGE/install-lib" "$OVER_STAGE/scripts"
cp -f "$ROOT/server/install.sh" "$OVER_STAGE/install.sh"
cp -f "$ROOT/server/fetch-and-install.sh" "$OVER_STAGE/fetch-and-install.sh"
cp -f "$ROOT/server/ready.sh" "$OVER_STAGE/ready.sh"
cp -a "$ROOT/server/install-lib/." "$OVER_STAGE/install-lib/"
cp -f "$ROOT/server/docker-compose.yml" "$OVER_STAGE/docker-compose.yml"
cp -f "$ROOT/server/docker-compose.exit.yml" "$OVER_STAGE/docker-compose.exit.yml"
cp -f "$ROOT/server/.env.example" "$OVER_STAGE/.env.example"
printf '%s\n' "$VER_OVER" > "$OVER_STAGE/DEPLOY_VERSION"
cp -f "$ROOT/scripts/safe-extract-package.py" "$OVER_STAGE/scripts/safe-extract-package.py"
cp -f "$ROOT/scripts/assemble-docker-save.py" "$OVER_STAGE/scripts/assemble-docker-save.py"
cp -f "$ROOT/scripts/layer-cache.py" "$OVER_STAGE/scripts/layer-cache.py"
printf 'overlay-hostfiles-%s\n' "$VER_OVER" > "$OVER_STAGE/OVERLAY_MARKER"
chmod 755 "$OVER_STAGE/install.sh" "$OVER_STAGE/fetch-and-install.sh" "$OVER_STAGE/ready.sh"
OVERLAY_TAR="$TMP/ardtt-hostfiles-overlay.tar.gz"
tar -czf "$OVERLAY_TAR" -C "$OVER_STAGE" .

# Older overlay must not replace a newer published installer.
OVER_OLD="$TMP/overlay-old"
mkdir -p "$OVER_OLD"
printf '1.0.40\n' > "$OVER_OLD/DEPLOY_VERSION"
printf 'should-not-apply\n' > "$OVER_OLD/OVERLAY_MARKER"
OVERLAY_OLD_TAR="$TMP/overlay-old.tar.gz"
tar -czf "$OVERLAY_OLD_TAR" -C "$OVER_OLD" .

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
BASE="http://127.0.0.1:${PORT}"
python3 - "$DIST" "$BASE" "$ARCH" "$VER_PUB" <<'PY'
import json, os, pathlib, sys
dist, base, arch, ver = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
def asset(name, digest=True):
    p = dist / name
    a = {"name": name, "browser_download_url": f"{base}/{name}", "size": p.stat().st_size}
    if digest:
        import hashlib
        a["digest"] = "sha256:" + hashlib.sha256(p.read_bytes()).hexdigest()
    return a
names = sorted(x.name for x in dist.glob(f"ardtt-server-{ver}-*") if not x.name.endswith(".sha256"))
names += [f"ardtt-docker-engine-29.7.2-linux-{arch}.tgz", f"ardtt-docker-compose-2.32.4-linux-{arch}"]
assets = [asset(n) for n in names]
assets.append(asset("SHA256SUMS-server.txt", False))
releases = [{"tag_name": f"v-{ver}", "draft": False, "assets": assets}]
api = dist / "api" / "repos" / "o" / "r"
api.mkdir(parents=True)
(api / "releases").write_text(json.dumps(releases), encoding="utf-8")
PY

LOG="$TMP/http.log"
( cd "$DIST" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 ) >"$LOG" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 50); do curl -fs -o /dev/null "$BASE/SHA256SUMS-server.txt" && break; sleep 0.1; done

mkdir -p "$TMP/nodocker" "$TMP/withdocker"
for d in /usr/bin /bin; do
  for f in "$d"/*; do
    n="$(basename "$f")"
    case "$n" in docker*|podman|kubelet|ctr|nerdctl) continue ;; esac
    [ -e "$TMP/nodocker/$n" ] || ln -s "$f" "$TMP/nodocker/$n" 2>/dev/null || true
  done
done
cat > "$TMP/withdocker/docker" <<'EOF'
#!/bin/sh
case "$1" in
  compose) [ "$2" = "version" ] && exit 0 ;;
esac
exit 1
EOF
chmod +x "$TMP/withdocker/docker"

INSTALL="$TMP/opt"
run_fetch() {
  local name="$1"; shift
  rm -rf "$INSTALL"
  mkdir -p "$INSTALL"
  set +e
  env -i PATH="$TMP/withdocker:$TMP/nodocker" HOME="$TMP" \
    ARDTT_GITHUB_API="$BASE/api" ARDTT_GITHUB_REPO=o/r ARDTT_GITHUB_DOWNLOAD="$BASE/gh" \
    ARDTT_INSTALL_DIR="$INSTALL" ARDTT_PUBLIC_HOST=203.0.113.9 \
    ARDTT_DRY_RUN=1 ARDTT_SKIP_ROOT_CHECK=1 ARDTT_KEEP_INSTALL_LOG=1 \
    "$@" bash "$ROOT/server/fetch-and-install.sh" > "$TMP/out-$name.txt" 2>&1
  RUN_RC=$?
  set -e
  RUN_OUT="$TMP/out-$name.txt"
}

# --- A: unpublished pin + overlay → published layers, overlay hostfiles, DONE=overlay ver
run_fetch A \
  ARDTT_DEPLOY_VERSION="$VER_OVER" \
  ARDTT_HOSTFILES_OVERLAY=1 \
  ARDTT_HOSTFILES_OVERLAY_PATH="$OVERLAY_TAR"
[ "$RUN_RC" = 0 ] || err "A exit $RUN_RC: $(tail -8 "$RUN_OUT")"
grep -q 'ARDTT_DONE|dry_run=1' "$RUN_OUT" || err "A missing ARDTT_DONE: $(tail -5 "$RUN_OUT")"
grep -q "deploy_version=${VER_OVER}" "$RUN_OUT" || err "A must record overlay version: $(grep ARDTT_DONE "$RUN_OUT")"
grep -q "deploy_version=${VER_PUB}" "$RUN_OUT" && err "A must not record only the published stack as deploy_version"
grep -q 'PACKAGE_RESOLVE' "$RUN_OUT" && err "A must not PACKAGE_RESOLVE when overlay can supply hostfiles"
grep -qE "ARDTT_WARN\|.*overlay" "$RUN_OUT" || err "A expected overlay warn: $(grep ARDTT_WARN "$RUN_OUT")"
[ -f "$INSTALL/staging/OVERLAY_MARKER" ] || err "A staging missing OVERLAY_MARKER"
grep -qx "overlay-hostfiles-${VER_OVER}" "$INSTALL/staging/OVERLAY_MARKER" \
  || err "A overlay marker contents: $(cat "$INSTALL/staging/OVERLAY_MARKER" 2>/dev/null || true)"
grep -qx "$VER_OVER" "$INSTALL/staging/DEPLOY_VERSION" \
  || err "A staging DEPLOY_VERSION: $(cat "$INSTALL/staging/DEPLOY_VERSION" 2>/dev/null || true)"
python3 - "$INSTALL/staging/manifest.json" "$VER_OVER" "$VER_PUB" <<'PY' || err "A manifest must keep published image and overlay deployVersion"
import json, sys
man = json.load(open(sys.argv[1], encoding="utf-8"))
over, pub = sys.argv[2], sys.argv[3]
assert man.get("deployVersion") == over, man.get("deployVersion")
tag = ((man.get("image") or {}).get("tag") or "")
assert pub in tag or tag.endswith(":"+pub) or pub in tag, tag
PY
[ -f "$INSTALL/staging/images/layout.json" ] || err "A must keep published images/layout.json"
[ -d "$INSTALL/cache/layers" ] || err "A layer cache missing"
[ "$(find "$INSTALL/cache/layers" -name '*.tar.gz' | wc -l)" = 3 ] || err "A expected 3 published layers in cache"
ok "A overlay hostfiles on published layers"

# --- B: overlay requested but tarball missing → fail closed
run_fetch B \
  ARDTT_DEPLOY_VERSION="$VER_OVER" \
  ARDTT_HOSTFILES_OVERLAY=1 \
  ARDTT_HOSTFILES_OVERLAY_PATH="$TMP/missing-overlay.tar.gz"
[ "$RUN_RC" != 0 ] || err "B missing overlay must fail"
grep -q 'ARDTT_ERROR|code=HOSTFILES_OVERLAY_MISSING|' "$RUN_OUT" \
  || err "B expected HOSTFILES_OVERLAY_MISSING: $(tail -3 "$RUN_OUT")"
grep -q 'ARDTT_DONE' "$RUN_OUT" && err "B must not print ARDTT_DONE"
ok "B missing overlay fail-closed"

# --- C: older overlay than published stack is skipped; DONE stays published
run_fetch C \
  ARDTT_DEPLOY_VERSION="$VER_PUB" \
  ARDTT_HOSTFILES_OVERLAY=1 \
  ARDTT_HOSTFILES_OVERLAY_PATH="$OVERLAY_OLD_TAR"
[ "$RUN_RC" = 0 ] || err "C exit $RUN_RC: $(tail -8 "$RUN_OUT")"
grep -q "deploy_version=${VER_PUB}" "$RUN_OUT" || err "C must keep published version: $(grep ARDTT_DONE "$RUN_OUT")"
[ -f "$INSTALL/staging/OVERLAY_MARKER" ] && err "C must not copy older overlay marker"
ok "C older overlay skipped"

# --- D: no overlay env — unpublished pin still falls back (old APK)
run_fetch D ARDTT_DEPLOY_VERSION="$VER_OVER"
[ "$RUN_RC" = 0 ] || err "D exit $RUN_RC: $(tail -8 "$RUN_OUT")"
grep -q "deploy_version=${VER_PUB}" "$RUN_OUT" || err "D fallback published version: $(grep ARDTT_DONE "$RUN_OUT")"
grep -q "deploy_version=${VER_OVER}" "$RUN_OUT" && err "D without overlay must not claim overlay version"
[ -f "$INSTALL/staging/OVERLAY_MARKER" ] && err "D must not invent overlay files"
ok "D unpublished pin without overlay still falls back"

# --- E: tampered published hostfiles still rejected before overlay
python3 - "$DIST" "$ARCH" "$VER_PUB" <<'PY'
import pathlib, sys
dist, arch, ver = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
p = dist / f"ardtt-server-{ver}-linux-{arch}-hostfiles.tar.gz"
p.write_bytes(p.read_bytes() + b"\0")
PY
run_fetch E \
  ARDTT_DEPLOY_VERSION="$VER_OVER" \
  ARDTT_HOSTFILES_OVERLAY=1 \
  ARDTT_HOSTFILES_OVERLAY_PATH="$OVERLAY_TAR"
[ "$RUN_RC" != 0 ] || err "E tampered published hostfiles must fail"
grep -q 'ARDTT_ERROR|code=SHA256_MISMATCH|' "$RUN_OUT" \
  || err "E expected SHA256_MISMATCH: $(tail -3 "$RUN_OUT")"
grep -q 'ARDTT_DONE' "$RUN_OUT" && err "E must not print ARDTT_DONE"
ok "E overlay does not skip published hostfiles SHA-256"

# Pack script: no image/Engine/ardttctl, has installer, stays small.
if [ -f "$ROOT/scripts/pack-hostfiles-overlay.sh" ]; then
  bash "$ROOT/scripts/pack-hostfiles-overlay.sh" "$TMP/packed-overlay.tar.gz" \
    || err "pack-hostfiles-overlay.sh failed"
  python3 - "$TMP/packed-overlay.tar.gz" <<'PY' || err "packed overlay contents"
import tarfile, sys
path = sys.argv[1]
with tarfile.open(path, "r:gz") as tar:
    names = [n.replace("\\", "/").lstrip("./") for n in tar.getnames() if n and not str(n).endswith("/")]
assert "install.sh" in names, names
assert "DEPLOY_VERSION" in names
assert "install-lib/package.sh" in names
assert all(not n.startswith(("images/", "vendor/", "bin/")) and n != "ardttctl" for n in names), names
PY
  ok "pack-hostfiles-overlay.sh"
else
  err "missing scripts/pack-hostfiles-overlay.sh"
fi

if [ "$fail" -ne 0 ]; then
  echo "test-hostfiles-overlay failed" >&2
  exit 1
fi
echo "OK test-hostfiles-overlay"
