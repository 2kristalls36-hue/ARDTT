#!/usr/bin/env bash
# End-to-end check of fetch-and-install.sh against a local fake "GitHub":
# partial deploy (index + hostfiles + missing layers only), Engine/Compose
# skipped when present, SHA256SUMS fallback when the API is down, full-archive
# fallback for releases without an index, tampered asset rejected.
# No Docker Engine: install.sh runs with ARDTT_DRY_RUN=1.
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

make_release() { # version [layer-seed]
  local ver="$1" seed="${2:-$1}" stage="$TMP/stage-$1"
  ARDTT_FAKE_LAYERED=1 ARDTT_FAKE_LAYER_SEED="$seed" ARDTT_FAKE_STAGE_DIR="$stage" \
    bash "$ROOT/scripts/make-fake-server-package.sh" "$DIST/ardtt-server-${ver}-linux-${ARCH}.tar.gz" "$ARCH" "$ver" >/dev/null
  python3 "$ROOT/scripts/build-server-index.py" "$stage" "$DIST" --version "$ver" --arch "$ARCH" \
    --package "$DIST/ardtt-server-${ver}-linux-${ARCH}.tar.gz" --release-tag "v-$ver" \
    --engine-version 29.7.2 --compose-version 2.32.4 >/dev/null
}
make_release 1.0.45
make_release 1.0.46 shared-with-47   # layers 0,1 equal to 1.0.45; layer 2 differs
make_release 1.0.47 shared-with-47   # identical image to 1.0.46 (only host files differ by version)
( cd "$DIST" && sha256sum ardtt-* > SHA256SUMS-server.txt )

# Tamper with one 1.0.47-only asset: hostfiles (its version string makes it unique).
python3 - "$DIST" "$ARCH" <<'PY'
import pathlib, sys
dist, arch = pathlib.Path(sys.argv[1]), sys.argv[2]
p = dist / f"ardtt-server-1.0.47-linux-{arch}-hostfiles.tar.gz"
p.write_bytes(p.read_bytes() + b"\0")
PY

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
BASE="http://127.0.0.1:${PORT}"
python3 - "$DIST" "$BASE" "$ARCH" <<'PY'
import json, os, pathlib, sys
dist, base, arch = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
def asset(name, digest=True):
    p = dist / name
    a = {"name": name, "browser_download_url": f"{base}/{name}", "size": p.stat().st_size}
    if digest:
        import hashlib
        a["digest"] = "sha256:" + hashlib.sha256(p.read_bytes()).hexdigest()
    return a
def rel_assets(ver, digest):
    names = sorted(x.name for x in dist.glob(f"ardtt-server-{ver}-*") if not x.name.endswith(".sha256"))
    names += [f"ardtt-docker-engine-29.7.2-linux-{arch}.tgz", f"ardtt-docker-compose-2.32.4-linux-{arch}"]
    out = [asset(n, digest) for n in names]
    out.append(asset("SHA256SUMS-server.txt", False))
    return out
releases = [
    {"tag_name": "v0.5.302", "draft": False, "assets": rel_assets("1.0.47", True)},
    # newest stack without per-asset digest: forces the SHA256SUMS-server.txt path
    {"tag_name": "v0.5.301", "draft": False, "assets": rel_assets("1.0.46", False)},
    {"tag_name": "v0.5.300", "draft": True, "assets": rel_assets("1.0.46", True)},
    {"tag_name": "v0.5.299", "draft": False, "assets": rel_assets("1.0.45", True)},
    {"tag_name": "v0.5.298", "draft": False, "assets": [asset("SHA256SUMS-server.txt", False)]},
]
api = dist / "api" / "repos" / "o" / "r"
api.mkdir(parents=True)
(api / "releases").write_text(json.dumps(releases), encoding="utf-8")
# Release with only the monolithic archive (pre-index layout) → automatic full fallback.
legacy = [{"tag_name": "v0.5.290", "draft": False, "assets": [asset(f"ardtt-server-1.0.45-linux-{arch}.tar.gz", True)]}]
legacy_api = dist / "legacy" / "repos" / "o" / "r"
legacy_api.mkdir(parents=True)
(legacy_api / "releases").write_text(json.dumps(legacy), encoding="utf-8")
# No-API fallback: <download host>/<repo>/releases/latest/download/<asset>
gh = dist / "gh" / "o" / "r" / "releases" / "latest"
gh.mkdir(parents=True)
os.symlink(dist, gh / "download")
PY

LOG="$TMP/http.log"
( cd "$DIST" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 ) >"$LOG" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 50); do curl -fs -o /dev/null "$BASE/SHA256SUMS-server.txt" && break; sleep 0.1; done

# PATH without docker/podman: every other tool is symlinked so the scripts still run.
mkdir -p "$TMP/nodocker"
for d in /usr/bin /bin; do
  for f in "$d"/*; do
    n="$(basename "$f")"
    case "$n" in docker*|podman|kubelet|ctr|nerdctl) continue ;; esac
    [ -e "$TMP/nodocker/$n" ] || ln -s "$f" "$TMP/nodocker/$n" 2>/dev/null || true
  done
done
# Host with Docker + Compose plugin (daemon replies to nothing else).
mkdir -p "$TMP/withdocker"
cat > "$TMP/withdocker/docker" <<'EOF'
#!/bin/sh
case "$1" in
  compose) [ "$2" = "version" ] && exit 0 ;;
esac
exit 1
EOF
chmod +x "$TMP/withdocker/docker"

INSTALL="$TMP/opt"
run_fetch() { # name, then env assignments
  local name="$1"; shift
  local before after
  before="$(wc -l < "$LOG")"
  set +e
  env -i PATH="$RUN_PATH" HOME="$TMP" \
    ARDTT_GITHUB_API="${RUN_API:-$BASE/api}" ARDTT_GITHUB_REPO=o/r ARDTT_GITHUB_DOWNLOAD="$BASE/gh" \
    ARDTT_INSTALL_DIR="$INSTALL" ARDTT_PUBLIC_HOST=203.0.113.9 \
    ARDTT_DRY_RUN=1 ARDTT_SKIP_ROOT_CHECK=1 ARDTT_KEEP_INSTALL_LOG=1 \
    "$@" bash "$ROOT/server/fetch-and-install.sh" > "$TMP/out-$name.txt" 2>&1
  RUN_RC=$?
  set -e
  after="$(wc -l < "$LOG")"
  sed -n "$((before + 1)),${after}p" "$LOG" > "$TMP/req-$name.txt"
  RUN_OUT="$TMP/out-$name.txt"
  RUN_REQ="$TMP/req-$name.txt"
}
reqs() { grep -c "GET /$1 " "$RUN_REQ" || true; }

# --- A: fresh VPS without Docker, pinned 1.0.45: index + hostfiles + Engine + Compose + all 3 layers
RUN_PATH="$TMP/nodocker" run_fetch A ARDTT_DEPLOY_VERSION=1.0.45
[ "$RUN_RC" = 0 ] || err "A exit $RUN_RC: $(tail -5 "$RUN_OUT")"
grep -q 'ARDTT_DONE|dry_run=1' "$RUN_OUT" || err "A missing ARDTT_DONE"
grep -q 'deploy_version=1.0.45' "$RUN_OUT" || err "A wrong version: $(grep ARDTT_DONE "$RUN_OUT")"
grep -q 'ARDTT_INFO|индекс 1.0.45' "$RUN_OUT" || err "A install.sh did not verify staging against the index"
[ "$(reqs "ardtt-server-1.0.45-linux-${ARCH}.index.json")" = 1 ] || err "A index requests"
[ "$(reqs "ardtt-server-1.0.45-linux-${ARCH}-hostfiles.tar.gz")" = 1 ] || err "A hostfiles requests"
[ "$(reqs "ardtt-docker-engine-29.7.2-linux-${ARCH}.tgz")" = 1 ] || err "A engine must be downloaded on a VPS without docker"
[ "$(reqs "ardtt-docker-compose-2.32.4-linux-${ARCH}")" = 1 ] || err "A compose must be downloaded without a plugin"
[ "$(grep -c "layer-0[0-9]-" "$RUN_REQ")" = 3 ] || err "A expected 3 layer downloads: $(cat "$RUN_REQ")"
[ "$(grep -c "ardtt-server-1.0.45-linux-${ARCH}.tar.gz " "$RUN_REQ")" = 0 ] || err "A must not download the full archive"
[ "$(find "$INSTALL/cache/layers" -name '*.tar.gz' | wc -l)" = 3 ] || err "A cache must hold 3 layers"
[ -z "$(find "$INSTALL/incoming" -type f 2>/dev/null)" ] || err "A incoming must be empty after success: $(ls -R "$INSTALL/incoming")"
grep -q 'ARDTT_PROGRESS|0.1[0-9]*|Слой' "$RUN_OUT" || err "A per-layer progress lines"
ok "A partial install on a bare VPS"

# --- B: Docker + Compose present, unpinned → newest 1.0.47 is tampered? no: 1.0.47 hostfiles are corrupt → SHA mismatch
RUN_PATH="$TMP/withdocker:$TMP/nodocker" run_fetch B
[ "$RUN_RC" != 0 ] || err "B tampered hostfiles must fail"
grep -q 'ARDTT_ERROR|code=SHA256_MISMATCH|' "$RUN_OUT" || err "B expected SHA256_MISMATCH: $(tail -3 "$RUN_OUT")"
grep -q 'ARDTT_DONE' "$RUN_OUT" && err "B must not print ARDTT_DONE"
[ "$(grep -c "layer-0[0-9]-" "$RUN_REQ")" = 0 ] || err "B must stop before layers"
ok "B tampered asset rejected before anything is staged"

# --- C: Docker + Compose present, pinned 1.0.46 (no digests → SHA256SUMS-server.txt): only hostfiles + 1 new layer
RUN_PATH="$TMP/withdocker:$TMP/nodocker" run_fetch C ARDTT_DEPLOY_VERSION=1.0.46
[ "$RUN_RC" = 0 ] || err "C exit $RUN_RC: $(tail -5 "$RUN_OUT")"
grep -q 'deploy_version=1.0.46' "$RUN_OUT" || err "C version"
[ "$(reqs "SHA256SUMS-server.txt")" -ge 1 ] || err "C must read SHA256SUMS-server.txt when digest is absent"
[ "$(reqs "ardtt-docker-engine-29.7.2-linux-${ARCH}.tgz")" = 0 ] || err "C Engine must not be downloaded when docker exists"
[ "$(reqs "ardtt-docker-compose-2.32.4-linux-${ARCH}")" = 0 ] || err "C Compose must not be downloaded when the plugin exists"
[ "$(grep -c "layer-0[0-9]-" "$RUN_REQ")" = 1 ] || err "C expected exactly 1 layer download: $(cat "$RUN_REQ")"
grep -q "layer-02-" "$RUN_REQ" || err "C the changed layer is layer 02"
grep -q 'ARDTT_INFO|слои образа: 3 всего, 2 в кэше, скачать 1' "$RUN_OUT" || err "C cache summary: $(grep 'слои образа' "$RUN_OUT")"
grep -q 'не скачиваем' "$RUN_OUT" || err "C should report skipped Engine/Compose"
ok "C update downloads only the changed layer"

# --- D: API down → SHA256SUMS-server.txt of the latest release (1.0.47 is newest but tampered; pin 1.0.46)
RUN_PATH="$TMP/withdocker:$TMP/nodocker" RUN_API="$BASE/missing-api" run_fetch D ARDTT_DEPLOY_VERSION=1.0.46
[ "$RUN_RC" = 0 ] || err "D exit $RUN_RC: $(tail -5 "$RUN_OUT")"
grep -q 'ARDTT_WARN|GitHub API недоступен' "$RUN_OUT" || err "D must warn about API fallback"
grep -q 'GET /gh/o/r/releases/latest/download/SHA256SUMS-server.txt' "$RUN_REQ" || err "D must fetch SHA256SUMS via releases/latest/download"
[ "$(grep -c "layer-0[0-9]-" "$RUN_REQ")" = 0 ] || err "D all layers cached → 0 downloads: $(cat "$RUN_REQ")"
ok "D API outage falls back to SHA256SUMS-server.txt"

# --- E: forced full archive
RUN_PATH="$TMP/withdocker:$TMP/nodocker" run_fetch E ARDTT_DEPLOY_VERSION=1.0.45 ARDTT_FETCH_MODE=full
[ "$RUN_RC" = 0 ] || err "E exit $RUN_RC: $(tail -5 "$RUN_OUT")"
[ "$(reqs "ardtt-server-1.0.45-linux-${ARCH}.tar.gz")" = 1 ] || err "E full archive must be downloaded"
[ "$(reqs "ardtt-server-1.0.45-linux-${ARCH}.index.json")" = 0 ] || err "E must not touch the index"
grep -q 'ARDTT_DONE|dry_run=1' "$RUN_OUT" || err "E missing ARDTT_DONE"
ok "E ARDTT_FETCH_MODE=full"

# --- F: release without index (old layout) → automatic full fallback
rm -f "$INSTALL/incoming/"*.tar.gz   # E's dry-run left the archive in incoming (a real install deletes it)
RUN_PATH="$TMP/withdocker:$TMP/nodocker" RUN_API="$BASE/legacy" run_fetch F
[ "$RUN_RC" = 0 ] || err "F exit $RUN_RC: $(tail -5 "$RUN_OUT")"
[ "$(reqs "ardtt-server-1.0.45-linux-${ARCH}.tar.gz")" = 1 ] || err "F must fall back to the full archive"
grep -q 'ARDTT_DONE|dry_run=1' "$RUN_OUT" || err "F missing ARDTT_DONE"
ok "F pre-index release still installs"

# --- G: partial requested but release has no index
RUN_PATH="$TMP/withdocker:$TMP/nodocker" RUN_API="$BASE/legacy" run_fetch G ARDTT_FETCH_MODE=partial
[ "$RUN_RC" != 0 ] || err "G must fail"
grep -q 'ARDTT_ERROR|code=INDEX_MISSING|' "$RUN_OUT" || err "G expected INDEX_MISSING"
ok "G INDEX_MISSING"

# --- H: unknown pinned version
RUN_PATH="$TMP/withdocker:$TMP/nodocker" run_fetch H ARDTT_DEPLOY_VERSION=9.9.9
[ "$RUN_RC" != 0 ] || err "H must fail"
grep -q 'ARDTT_ERROR|code=PACKAGE_RESOLVE|' "$RUN_OUT" || err "H expected PACKAGE_RESOLVE"
ok "H PACKAGE_RESOLVE"

# --- I: cold cache but the image is already loaded → layers come from `docker save`, not the network
rm -rf "$INSTALL/cache"
python3 - "$TMP/stage-1.0.46/images" "$TMP/old-image.tar" <<'PY'
import gzip, io, json, pathlib, sys, tarfile
images, out = pathlib.Path(sys.argv[1]), sys.argv[2]
lay = json.loads((images / "layout.json").read_text())
with tarfile.open(out, "w") as t:
    def add(name, data):
        ti = tarfile.TarInfo(name); ti.size = len(data); t.addfile(ti, io.BytesIO(data))
    add("cfg.json", (images / "config.json").read_bytes())
    names = []
    for l in lay["layers"]:
        raw = gzip.decompress((images / l["file"]).read_bytes())
        names.append(l["sha256"] + "/layer.tar")
        add(names[-1], raw)
    add("manifest.json", json.dumps([{"Config": "cfg.json", "RepoTags": ["ardtt/server:1.0.46"], "Layers": names}]).encode())
PY
mkdir -p "$TMP/withimage"
cat > "$TMP/withimage/docker" <<EOF
#!/bin/sh
case "\$1" in
  compose) [ "\$2" = "version" ] && exit 0 ;;
  info) exit 0 ;;
  images) echo "ardtt/server:1.0.46"; exit 0 ;;
  save) cat "$TMP/old-image.tar"; exit 0 ;;
esac
exit 1
EOF
chmod +x "$TMP/withimage/docker"
RUN_PATH="$TMP/withimage:$TMP/nodocker" run_fetch I ARDTT_DEPLOY_VERSION=1.0.46
[ "$RUN_RC" = 0 ] || err "I exit $RUN_RC: $(tail -5 "$RUN_OUT")"
grep -q 'ARDTT_INFO|seeded 3 layers' "$RUN_OUT" || err "I expected the cache to be seeded from docker save: $(grep -i seed "$RUN_OUT")"
[ "$(grep -c "layer-0[0-9]-" "$RUN_REQ")" = 0 ] || err "I no layer must be downloaded after seeding: $(cat "$RUN_REQ")"
grep -q 'ARDTT_INFO|слои образа: 3 всего, 3 в кэше, скачать 0' "$RUN_OUT" || err "I cache summary: $(grep 'слои образа' "$RUN_OUT")"
ok "I cold cache seeded from the loaded image"

# --- J: no curl on the VPS (minimal Debian image) → python3 urllib downloader, same result
mkdir -p "$TMP/nocurl"
for f in "$TMP/nodocker"/*; do
  n="$(basename "$f")"
  [ "$n" = "curl" ] && continue
  ln -s "$(readlink "$f")" "$TMP/nocurl/$n" 2>/dev/null || true
done
rm -rf "$INSTALL/cache"
RUN_PATH="$TMP/withdocker:$TMP/nocurl" run_fetch J ARDTT_DEPLOY_VERSION=1.0.46
[ "$RUN_RC" = 0 ] || err "J exit $RUN_RC: $(tail -5 "$RUN_OUT")"
grep -q 'ARDTT_INFO|curl не найден' "$RUN_OUT" || err "J must announce the urllib fallback"
grep -q 'deploy_version=1.0.46' "$RUN_OUT" || err "J version"
[ "$(grep -c "layer-0[0-9]-" "$RUN_REQ")" = 3 ] || err "J expected 3 layer downloads via urllib: $(cat "$RUN_REQ")"
[ "$(reqs "ardtt-docker-engine-29.7.2-linux-${ARCH}.tgz")" = 0 ] || err "J Engine must not be downloaded when docker exists"
ok "J urllib downloader without curl"

# --- K: no docker, no iptables, operator forbids distro packages → fail before any download
if ! command -v iptables >/dev/null 2>&1; then
  before_k="$(wc -l < "$LOG")"
  set +e
  env -i PATH="$TMP/nodocker" HOME="$TMP" ARDTT_GITHUB_API="$BASE/api" ARDTT_GITHUB_REPO=o/r \
    ARDTT_INSTALL_DIR="$INSTALL" ARDTT_PUBLIC_HOST=203.0.113.9 ARDTT_SKIP_ROOT_CHECK=1 \
    ARDTT_INSTALL_IPTABLES=0 \
    bash "$ROOT/server/fetch-and-install.sh" > "$TMP/out-K.txt" 2>&1
  rc_k=$?
  set -e
  [ "$rc_k" != 0 ] || err "K must fail without iptables when ARDTT_INSTALL_IPTABLES=0"
  grep -q 'ARDTT_ERROR|code=IPTABLES_MISSING|' "$TMP/out-K.txt" || err "K expected IPTABLES_MISSING: $(tail -2 "$TMP/out-K.txt")"
  [ "$(wc -l < "$LOG")" = "$before_k" ] || err "K must not download anything before the iptables check"
  ok "K ARDTT_INSTALL_IPTABLES=0 is reported before any download"

  # --- L: one-button path — iptables is installed from the distro repo (fake apt-get)
  #        after the 56 KB host files and before the 80 MB Engine download.
  mkdir -p "$TMP/fakeapt"
  cat > "$TMP/fakeapt/apt-get" <<EOF
#!/bin/sh
case "\$*" in
  *install*iptables*) printf '#!/bin/sh\necho "iptables v1.8.11 (fake)"\n' > "$TMP/fakeapt/iptables"; chmod +x "$TMP/fakeapt/iptables"; echo "fake apt: installed iptables" ;;
  *update*) echo "fake apt: update" ;;
esac
exit 0
EOF
  chmod +x "$TMP/fakeapt/apt-get"
  rm -f "$TMP/fakeapt/iptables"
  before_l="$(wc -l < "$LOG")"
  set +e
  env -i PATH="$TMP/fakeapt:$TMP/nodocker" HOME="$TMP" ARDTT_GITHUB_API="$BASE/api" ARDTT_GITHUB_REPO=o/r \
    ARDTT_INSTALL_DIR="$INSTALL" ARDTT_PUBLIC_HOST=203.0.113.9 ARDTT_SKIP_ROOT_CHECK=1 \
    ARDTT_DEPLOY_VERSION=1.0.45 ARDTT_ENGINE_LIB="$TMP/engine-lib" ARDTT_ENGINE_BINDIR="$TMP/engine-bin" \
    bash "$ROOT/server/fetch-and-install.sh" > "$TMP/out-L.txt" 2>&1
  set -e
  grep -q 'ARDTT_PROGRESS|0.15|Установка iptables из репозитория дистрибутива (apt)' "$TMP/out-L.txt" \
    || err "L must install iptables via apt before Engine: $(grep -E 'ARDTT_(ERROR|PROGRESS)' "$TMP/out-L.txt" | head -5)"
  grep -q 'ARDTT_INFO|iptables поставлен из репозитория дистрибутива (apt)' "$TMP/out-L.txt" || err "L must report the installed iptables"
  [ -x "$TMP/fakeapt/iptables" ] || err "L fake apt did not run the install"
  sed -n "$((before_l + 1)),\$p" "$LOG" | grep -q "ardtt-docker-engine-29.7.2-linux-${ARCH}.tgz" \
    || err "L Engine must be downloaded after iptables is in place: $(sed -n "$((before_l + 1)),\$p" "$LOG" | awk '{print $7}')"
  # Order: host files first, then iptables, then Engine.
  python3 - "$TMP/out-L.txt" <<'PY' || err "L wrong order of host files / iptables / Engine"
import sys
t = open(sys.argv[1], encoding="utf-8").read()
a, b, c = t.find("Файлы установщика"), t.find("Установка iptables"), t.find("Docker Engine 29.7.2 из релиза")
assert 0 <= a < b < c, (a, b, c)
PY
  ok "L iptables installed from the distro repo between host files and Engine"
fi

# Bootstrap copy in APK assets must be the same script.
cmp -s "$ROOT/server/fetch-and-install.sh" "$ROOT/android/app/src/main/assets/deploy/fetch-and-install.sh" \
  || err "android/app/src/main/assets/deploy/fetch-and-install.sh differs from server/fetch-and-install.sh"

if [ "$fail" -ne 0 ]; then
  echo "test-fetch-partial failed" >&2
  exit 1
fi
echo "OK test-fetch-partial"
