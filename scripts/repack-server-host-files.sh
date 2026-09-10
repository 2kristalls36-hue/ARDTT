#!/usr/bin/env bash
# Replace host-side files in an existing ardtt-server-*.tar.gz with this
# checkout (install.sh, ready.sh, Compose, install-lib). Keeps the image
# payload (images/layout.json + layers/ or legacy images/ardtt.tar),
# bin/docker-compose and vendor/docker.tgz.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:-}"
[ -n "$PKG" ] && [ -f "$PKG" ] || { echo "usage: $0 ardtt-server-*.tar.gz" >&2; exit 1; }
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

python3 "$ROOT/scripts/safe-extract-package.py" "$PKG" "$STAGE"
test -f "$STAGE/manifest.json"
if [ ! -f "$STAGE/images/layout.json" ] && [ ! -f "$STAGE/images/ardtt.tar" ]; then
  echo "package missing images/layout.json and images/ardtt.tar" >&2
  exit 1
fi
pkg_ver="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["deployVersion"])' "$STAGE/manifest.json")"
[ "$pkg_ver" = "$VER" ] || {
  echo "package version $pkg_ver != DEPLOY_VERSION $VER" >&2
  exit 1
}

cp -f "$ROOT/server/install.sh" "$STAGE/install.sh"
chmod 755 "$STAGE/install.sh"
cp -f "$ROOT/server/ready.sh" "$STAGE/ready.sh"
chmod 755 "$STAGE/ready.sh"
mkdir -p "$STAGE/install-lib" "$STAGE/scripts"
cp -a "$ROOT/server/install-lib/." "$STAGE/install-lib/"
cp -f "$ROOT/server/docker-compose.yml" "$STAGE/docker-compose.yml"
cp -f "$ROOT/server/docker-compose.exit.yml" "$STAGE/docker-compose.exit.yml"
cp -f "$ROOT/server/.env.example" "$STAGE/.env.example"
cp -f "$ROOT/scripts/safe-extract-package.py" "$STAGE/scripts/safe-extract-package.py"
cp -f "$ROOT/scripts/assemble-docker-save.py" "$STAGE/scripts/assemble-docker-save.py"
chmod 755 "$STAGE/scripts/assemble-docker-save.py"
# DEPLOY_VERSION, third-party.lock.json, images/, bin/ stay with the image.

grep -q 'SETGID' "$STAGE/docker-compose.yml" || {
  echo "repacked compose missing SETGID" >&2
  exit 1
}
grep -q 'overlay_ready_script' "$STAGE/install.sh" || {
  echo "repacked install.sh missing overlay_ready_script" >&2
  exit 1
}

COMMIT="${ARDTT_GIT_COMMIT:-$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
python3 - "$STAGE" "$COMMIT" <<'PY'
import hashlib, json, pathlib, sys
stage = pathlib.Path(sys.argv[1])
commit = sys.argv[2]

def sha(name: str) -> str:
    return hashlib.sha256((stage / name).read_bytes()).hexdigest()

path = stage / "manifest.json"
man = json.loads(path.read_text(encoding="utf-8"))
files = man.setdefault("files", {})
files["install.sh"] = sha("install.sh")
files["ready.sh"] = sha("ready.sh")
files["docker-compose.yml"] = sha("docker-compose.yml")
if (stage / "images/layout.json").is_file():
    files["images/layout.json"] = sha("images/layout.json")
if (stage / "images/ardtt.tar").is_file():
    files["images/ardtt.tar"] = sha("images/ardtt.tar")
if (stage / "vendor/docker.tgz").is_file():
    files["vendor/docker.tgz"] = sha("vendor/docker.tgz")
man["commit"] = commit
path.write_text(json.dumps(man, indent=2) + "\n", encoding="utf-8")
PY

(
  cd "$STAGE"
  sums=(install.sh ready.sh docker-compose.yml docker-compose.exit.yml
    bin/docker-compose manifest.json third-party.lock.json)
  [ -f images/layout.json ] && sums+=(images/layout.json)
  [ -f images/ardtt.tar ] && sums+=(images/ardtt.tar)
  [ -f vendor/docker.tgz ] && sums+=(vendor/docker.tgz)
  [ -f scripts/assemble-docker-save.py ] && sums+=(scripts/assemble-docker-save.py)
  sha256sum "${sums[@]}" > SHA256SUMS
)

tmp="${PKG}.repack.$$"
extra=()
[ -d "$STAGE/vendor" ] && extra+=(vendor)
tar -czf "$tmp" -C "$STAGE" \
  manifest.json SHA256SUMS README.md DEPLOY_VERSION third-party.lock.json \
  install.sh ready.sh install-lib scripts \
  docker-compose.yml docker-compose.exit.yml .env.example \
  images bin "${extra[@]}"
mv -f "$tmp" "$PKG"
sha256sum "$PKG" | awk '{print $1}' > "${PKG}.sha256"
echo "Repacked host files into $PKG (image unchanged, commit=$COMMIT)"
echo "outerSha256=$(cat "${PKG}.sha256")"
