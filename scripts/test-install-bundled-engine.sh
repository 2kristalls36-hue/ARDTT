#!/usr/bin/env bash
# Bundled Docker Engine: tarball in the package, never a network Engine installer on the VPS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

if grep -q 'get.docker.com' "$ROOT/server/install.sh" "$ROOT/server/install-lib/"*.sh; then
  err "installer must not call get.docker.com"
fi
if grep -q 'apt-get install' "$ROOT/server/install-lib/engine.sh"; then
  err "engine.sh must not apt-get"
fi
if grep -q 'systemctl stop docker' "$ROOT/server/install-lib/engine.sh"; then
  err "engine.sh must not stop docker"
fi
if grep -q 'cat > /lib/systemd/system/docker.service' "$ROOT/server/install-lib/engine.sh"; then
  err "must not overwrite distro docker.service"
fi
grep -q 'vendor/docker.tgz' "$ROOT/server/install-lib/engine.sh" || err "engine.sh must read vendor/docker.tgz"
grep -q '/usr/local/lib/ardtt-docker' "$ROOT/server/install-lib/engine.sh" || err "Engine lives outside /opt/ardtt"
grep -q 'ARDTT-bundled-docker' "$ROOT/server/install-lib/engine.sh" || err "systemd unit marker"
grep -q 'ensure_docker_engine' "$ROOT/server/install.sh" || err "install.sh must call ensure_docker_engine"
grep -q 'vendor/docker.tgz' "$ROOT/scripts/pack-server-package.sh" || err "pack must ship vendor/docker.tgz"
if grep -E 'rm -.*/usr/local/lib/ardtt-docker' "$ROOT/server/install-lib/uninstall.sh"; then
  err "uninstall must not remove bundled Engine"
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$WORKDIR/pkg/vendor" "$WORKDIR/docker" "$WORKDIR/lib" "$WORKDIR/bin"

cat > "$WORKDIR/docker/dockerd" <<'EOF'
#!/bin/sh
echo fake-dockerd
EOF
chmod +x "$WORKDIR/docker/dockerd"
echo '#!/bin/sh' > "$WORKDIR/docker/docker"
chmod +x "$WORKDIR/docker/docker"
tar -czf "$WORKDIR/pkg/vendor/docker.tgz" -C "$WORKDIR" docker
SHA="$(sha256sum "$WORKDIR/pkg/vendor/docker.tgz" | awk '{print $1}')"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
esac
python3 - "$WORKDIR/pkg/third-party.lock.json" "$ARCH" "$SHA" <<'PY'
import json, pathlib, sys
arch, sha = sys.argv[2], sys.argv[3]
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({
  "dockerEngineStatic": {"sha256": {arch: sha, "amd64": sha, "arm64": sha}}
}, indent=2) + "\n", encoding="utf-8")
PY

die() {
  if [ "${1:-}" = "--code" ]; then
    shift 2
  fi
  echo "ARDTT_ERROR|$*" >&2
  exit 1
}
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/engine.sh"

PKG_DIR="$WORKDIR/pkg"
export ARDTT_ENGINE_LIB="$WORKDIR/lib/ardtt-docker"
export ARDTT_ENGINE_BINDIR="$WORKDIR/bin"

got_tar="$(engine_tarball)" || err "engine_tarball should find vendor/docker.tgz"
[ "$got_tar" = "$WORKDIR/pkg/vendor/docker.tgz" ] || err "engine_tarball path"

install_engine_binaries "$WORKDIR/pkg/vendor/docker.tgz" || err "install_engine_binaries failed"
[ -x "$WORKDIR/lib/ardtt-docker/dockerd" ] || err "dockerd not unpacked"
[ -L "$WORKDIR/bin/dockerd" ] || err "dockerd symlink missing"

set +e
out="$(install_engine_binaries "$WORKDIR/missing.tgz" 2>&1)"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "missing tarball must fail"
echo "$out" | grep -q 'В архиве нет vendor/docker.tgz' || err "missing tarball message"

python3 - "$WORKDIR/pkg/third-party.lock.json" "$ARCH" <<'PY'
import json, pathlib, sys
arch = sys.argv[2]
path = pathlib.Path(sys.argv[1])
d = json.loads(path.read_text(encoding="utf-8"))
d["dockerEngineStatic"]["sha256"][arch] = "0" * 64
path.write_text(json.dumps(d) + "\n", encoding="utf-8")
PY
set +e
out="$(install_engine_binaries "$WORKDIR/pkg/vendor/docker.tgz" 2>&1)"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "bad sha must fail"
echo "$out" | grep -q 'SHA-256 vendor/docker.tgz не совпала' || err "bad sha message"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ensure_docker_engine || err "ensure_docker_engine must no-op when docker info works"
  [ -x "$WORKDIR/lib/ardtt-docker/dockerd" ] || err "no-op must not remove unpacked binaries"
fi

if [ "$fail" -ne 0 ]; then
  echo "bundled engine tests failed" >&2
  exit 1
fi
ok "bundled Docker Engine from vendor/docker.tgz"
