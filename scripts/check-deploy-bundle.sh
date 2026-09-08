#!/usr/bin/env bash
# Consistency checks for the VPS deploy package (no Docker required).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
msg() { echo "$*" >&2; }
err() { msg "FAIL: $*"; fail=1; }

INSTALLER="$ROOT/server/install.sh"
VERSION_FILE="$ROOT/server/DEPLOY_VERSION"
ASSET_VERSION="$ROOT/android/app/src/main/assets/deploy/DEPLOY_VERSION"
BUNDLE_KT="$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployBundle.kt"
STACK_SOURCE_KT="$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployStackSource.kt"
PACK_SERVER="$ROOT/scripts/pack-server-package.sh"
COMPOSE="$ROOT/server/docker-compose.yml"
DOCKERFILE="$ROOT/server/Dockerfile"

[ -f "$INSTALLER" ] || err "missing $INSTALLER"
[ -f "$VERSION_FILE" ] || err "missing $VERSION_FILE"
[ -f "$ASSET_VERSION" ] || err "missing $ASSET_VERSION"
[ -f "$BUNDLE_KT" ] || err "missing $BUNDLE_KT"
[ -f "$STACK_SOURCE_KT" ] || err "missing $STACK_SOURCE_KT"
[ -f "$PACK_SERVER" ] || err "missing $PACK_SERVER"
[ -f "$COMPOSE" ] || err "missing $COMPOSE"
[ -f "$ROOT/server/third-party.lock.json" ] || err "missing third-party.lock.json"
[ -f "$ROOT/server/docker-compose.exit.yml" ] || err "missing docker-compose.exit.yml"
[ -f "$ROOT/server/docker-compose.dev.yml" ] || err "missing docker-compose.dev.yml"
[ -f "$ROOT/server/ready.sh" ] || err "missing ready.sh"
[ -f "$ROOT/server/netns-guard.sh" ] || err "missing netns-guard.sh"

if [ -f "$INSTALLER" ]; then
  bash -n "$INSTALLER" || err "bash -n failed for server/install.sh"
  bash -n "$ROOT/server/ready.sh" || err "bash -n ready.sh"
  bash -n "$ROOT/server/install-lib/network.sh" || err "bash -n network.sh"
  grep -q 'ARDTT_PROGRESS|' "$INSTALLER" || err "installer missing ARDTT_PROGRESS protocol"
  grep -q 'ARDTT_ERROR|' "$INSTALLER" || err "installer missing ARDTT_ERROR protocol"
  grep -q 'ARDTT_DONE|' "$INSTALLER" || err "installer missing ARDTT_DONE protocol"
  if ! python3 - "$INSTALLER" <<'PY'
import pathlib, sys
head = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")[:400]
if "ARDTT_PROGRESS|" not in head or "ARDTT_DONE|" not in head:
    raise SystemExit(1)
PY
  then
    err "ARDTT_PROGRESS| and ARDTT_DONE| must appear in the first 400 chars of install.sh"
  fi
  grep -q 'ARDTT_PACKAGE_SHA256' "$INSTALLER" || err "installer must require outer package SHA-256"
  grep -q 'safe_extract_package' "$INSTALLER" || err "installer must extract safely"
  grep -q 'docker load' "$INSTALLER" || err "installer must docker load"
  grep -q 'load_package_image' "$INSTALLER" || err "installer must load_package_image (retag manifest ID)"
  grep -q 'loaded_image_matches_tar' "$ROOT/server/install-lib/package.sh" || err "package load must compare image tar layers"
  grep -q -- '--no-build --pull never' "$INSTALLER" || err "installer must up --no-build --pull never"
  grep -q 'wait_readiness' "$INSTALLER" || err "installer must wait readiness"
  grep -q 'restore_previous_release' "$INSTALLER" || err "installer must restore previous on failed up/readiness"
  if grep -q '\[ -d "$INSTALL_DIR/previous/docker-compose.yml" \]' "$INSTALLER"; then
    err "auto-rollback must not use [ -d ] on previous/docker-compose.yml (it is a file)"
  fi
  grep -q 'ARDTT_ACTION' "$INSTALLER" || err "installer missing uninstall/rollback actions"
  grep -q 'ARDTT_CASCADE_FORCE_DISABLE' "$ROOT/server/install-lib/migrate.sh" || err "cascade force-disable"
  grep -q 'exit-hideip' "$INSTALLER" || err "installer must set WARP_MODE=exit-hideip on the cascade exit"
  grep -q 'passthrough' "$INSTALLER" || err "cascade entry must use WARP passthrough"
  grep -q 'ensure_cascade_keys' "$INSTALLER" || err "installer missing cascade key helper"
  grep -q 'provision_port=' "$INSTALLER" || err "ARDTT_DONE must report provision_port"
  grep -q 'telemetry_port=' "$INSTALLER" || err "ARDTT_DONE must report telemetry_port"
  grep -q 'TELEMETRY_LISTEN=0.0.0.0:9200' "$INSTALLER" || err "inner telemetry listen stays 9200"
  if grep -q 'TELEMETRY_LISTEN=0.0.0.0:${TELEMETRY_PORT}' "$INSTALLER"; then
    err "host telemetry port must not rewrite container TELEMETRY_LISTEN"
  fi
  grep -q 'get.docker.com' "$INSTALLER" && err "installer must not call get.docker.com"
  grep -q 'fetch_stack_from_git' "$INSTALLER" && err "installer must not git clone / fetch sources"
  grep -q 'cleanup_host_dataplane' "$INSTALLER" && err "installer must not call cleanup_host_dataplane"
  grep -q 'reset_docker_buildkit' "$INSTALLER" && err "installer must not reset BuildKit / stop dockerd"
  grep -q 'ensure_swap' "$INSTALLER" && err "installer must not manage /swapfile"
  grep -q 'journalctl --vacuum' "$INSTALLER" && err "installer must not vacuum host journal"
  grep -q 'docker builder prune' "$INSTALLER" && err "installer must not prune Docker caches"
  grep -q 'drop_caches' "$INSTALLER" && err "installer must not drop host page cache"
  grep -q 'ufw allow' "$INSTALLER" && err "installer must not add ufw rules"
  grep -q 'hostnet' "$INSTALLER" && grep -q 'COMPOSE_PROFILES=hostnet' "$INSTALLER" && err "installer must not enable hostnet"
  grep -q 'ARDTT_UNINSTALLED' "$ROOT/server/install-lib/uninstall.sh" || err "uninstall marker"
  grep -q 'ARDTT_PURGE_DATA' "$ROOT/server/install-lib/uninstall.sh" || err "purge data flag"
  if grep -q 'apt-get purge' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/ServerUninstall.kt"; then
    err "ServerUninstall.kt must not purge Docker"
  fi
fi

if grep -qE '^[[:space:]]*build:' "$COMPOSE"; then
  err "production compose must not contain build:"
fi
if grep -q 'network_mode: host' "$COMPOSE"; then
  err "production compose must not use host netns"
fi
if grep -q 'profiles: \["hostnet"\]' "$COMPOSE"; then
  err "production compose must not ship a hostnet profile"
fi
grep -q 'pull_policy: never' "$COMPOSE" || err "compose must set pull_policy: never"
grep -q 'cap_drop:' "$COMPOSE" || err "compose must cap_drop ALL"
grep -q 'NET_ADMIN' "$COMPOSE" || err "compose missing NET_ADMIN"
grep -q 'NET_RAW' "$COMPOSE" || err "compose missing NET_RAW"
grep -q 'SETUID' "$COMPOSE" || err "compose missing SETUID for dnsmasq"
grep -q 'SETGID' "$COMPOSE" || err "compose missing SETGID for dnsmasq"
grep -q '/dev/net/tun' "$COMPOSE" || err "compose missing /dev/net/tun"
grep -qE '^[[:space:]]*privileged:' "$COMPOSE" && err "compose must not be privileged"
grep -qE 'docker\.sock:' "$COMPOSE" && err "compose must not mount docker.sock"
grep -q 'mem_limit:' "$COMPOSE" || err "compose missing mem_limit"
grep -q '/opt/ardtt/ready.sh' "$COMPOSE" || err "compose healthcheck must use ready.sh"
if ! grep -q 'bash", "/opt/ardtt/ready.sh' "$COMPOSE"; then
  err "compose healthcheck must run ready.sh via bash (image copy may be mode 644)"
fi
grep -q 'overlay_ready_script' "$INSTALLER" || err "installer must overlay package ready.sh into the container"
grep -q 'cat > /opt/ardtt/ready.sh' "$INSTALLER" || err "overlay must write ready.sh as container root (docker cp uses host uid)"
grep -q 'bash /opt/ardtt/ready.sh' "$INSTALLER" || err "readiness must invoke ready.sh via bash"
if grep -E 'local[[:space:]]+name="\$1"[[:space:]]+pidfile=.*\$\{name\}' "$ROOT/server/ready.sh" >/dev/null; then
  err "ready.sh must not expand \${name} in the same local statement (set -u)"
fi
grep -Fq 'cp -f "$ROOT/server/ready.sh"' "$PACK_SERVER" || err "pack-server-package must include ready.sh"
grep -q 'ARDTT_TELEMETRY_PORT' "$COMPOSE" || err "compose must publish host telemetry port"
grep -q '9100:9100/tcp' "$COMPOSE" || grep -q '9100/tcp' "$COMPOSE" || err "compose provision target 9100"
grep -q '9200:9200/tcp' "$COMPOSE" || grep -q '9200/tcp' "$COMPOSE" || err "compose telemetry target 9200"
grep -q 'com.ardtt.owner' "$COMPOSE" || err "compose missing owner label"
grep -q 'ARDTT_DATA_DIR' "$COMPOSE" || err "compose must mount dedicated data dir"
grep -q 'ARDTT_LOG_DIR' "$COMPOSE" || err "compose must mount dedicated log dir"

grep -q 'AMNEZIAWG_GO_COMMIT' "$DOCKERFILE" || err "Dockerfile must pin amneziawg-go commit"
grep -q 'sha256sum -c' "$DOCKERFILE" || err "Dockerfile must verify upstream checksums"
grep -q 'refs/heads/master' "$DOCKERFILE" && err "Dockerfile must not fetch floating master"

grep -q 'hide-ip-prefixes' "$ROOT/server/warp/entrypoint.sh" || err "exit warp must poll hide-ip-prefixes"
grep -q 'TCPMSS --clamp-mss-to-pmtu' "$ROOT/server/direct/entrypoint.sh" || err "direct must clamp TCPMSS"
grep -q 'apply_direct_mtu' "$ROOT/server/direct/entrypoint.sh" || err "direct must set awg0 MTU"
grep -q 'ardtt_require_container_netns' "$ROOT/server/warp/entrypoint.sh" || err "warp must refuse host netns"
grep -q 'ardtt_require_container_netns' "$ROOT/server/direct/entrypoint.sh" || err "direct must refuse host netns"
grep -q 'ARDTT_CASCADE_ROLE:-${ARDTT_ROLE:-entry}' "$ROOT/server/direct/cascade-entrypoint.sh" \
  || err "cascade must inherit ARDTT_ROLE"
grep -q '/ready' "$ROOT/server/provision/main.go" || err "provision missing /ready"
grep -q 'publicProvisionPort' "$ROOT/server/provision/main.go" || err "health must expose provisionPort"
grep -q 'envPort("ARDTT_DIRECT_PORT")' "$ROOT/server/provision/main.go" || err "provision must sync DirectPort"

if grep -E '^[^#]*conf/all/rp_filter' "$ROOT/server/warp/entrypoint.sh" >/dev/null; then
  err "warp must not write net.ipv4.conf.all.rp_filter"
fi
if grep -E '^[^#]*conf/\*/rp_filter' "$ROOT/server/direct/cascade-entrypoint.sh" >/dev/null; then
  err "cascade must not write every iface rp_filter"
fi
grep -q 'ARDTT_WARP_STATE:-/data/warp' "$ROOT/server/warp/entrypoint.sh" || err "warp default state dir"
if grep -q 'ARDTT_CASCADE_PASSWORD' "$INSTALLER"; then
  err "installer must not write cascade SSH password into .env"
fi
if grep -E '^[^#]*image prune -a' "$INSTALLER" >/dev/null; then
  err "install.sh must not docker image prune -a"
fi

bash -n "$ROOT/server/direct/cascade-entrypoint.sh" || err "bash -n cascade-entrypoint"
bash -n "$ROOT/server/warp/entrypoint.sh" || err "bash -n warp"
bash -n "$ROOT/server/entrypoint.sh" || err "bash -n entrypoint"
bash -n "$ROOT/scripts/pack-server-package.sh" || err "bash -n pack-server-package"
bash -n "$ROOT/scripts/repack-server-host-files.sh" || err "bash -n repack-server-host-files"
bash -n "$ROOT/scripts/attach-server-packages-to-release.sh" || err "bash -n attach-server-packages-to-release"
grep -q 'repack-server-host-files.sh' "$ROOT/scripts/attach-server-packages-to-release.sh" \
  || err "attach-to-release must refresh host files before upload"
bash -n "$ROOT/scripts/test-install-live-isolation.sh" || err "bash -n test-install-live-isolation"
bash -n "$ROOT/scripts/make-fake-server-package.sh" || err "bash -n make-fake-server-package"
python3 -m py_compile "$ROOT/scripts/safe-extract-package.py" || err "safe-extract-package.py"

if [ -f "$STACK_SOURCE_KT" ]; then
  grep -q 'ardtt-server-' "$STACK_SOURCE_KT" || err "DeployStackSource must name ardtt-server-*-linux-<arch>.tar.gz"
  grep -q 'preferredReleaseJson' "$STACK_SOURCE_KT" || err "DeployStackSource must search other releases if the version tag has no server asset"
  grep -q 'SHA256SUMS-server' "$STACK_SOURCE_KT" || err "DeployStackSource must read SHA256SUMS-server.txt"
  grep -q 'raw.githubusercontent.com' "$STACK_SOURCE_KT" && err "DeployStackSource must not fetch install.sh from raw GitHub"
  grep -q 'archive/refs/heads/main' "$STACK_SOURCE_KT" && err "DeployStackSource must not fall back to main"
fi
if grep -q 'docker builder prune -af' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployEngine.kt"; then
  err "DeployEngine must not prune Docker on the VPS"
fi
if grep -q 'uploadBytes(stackBytes' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployEngine.kt"; then
  err "DeployEngine must SFTP the package file, not uploadBytes of the image"
fi
if grep -q 'ByteArrayOutputStream' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployStackFetcher.kt"; then
  err "DeployStackFetcher must not buffer the whole package in a ByteArrayOutputStream"
fi

if [ -f "$ROOT/android/app/src/main/assets/deploy/install.sh" ]; then
  err "assets/deploy/install.sh must not be bundled"
fi
if ls "$ROOT"/android/app/src/main/assets/deploy/stack.tar.gz* >/dev/null 2>&1; then
  err "assets/deploy must not contain stack.tar.gz"
fi

VER=""
ASSET_VER=""
if [ -f "$VERSION_FILE" ]; then
  VER="$(tr -d '[:space:]' < "$VERSION_FILE")"
  [ -n "$VER" ] || err "empty server/DEPLOY_VERSION"
fi
if [ -f "$ASSET_VERSION" ]; then
  ASSET_VER="$(tr -d '[:space:]' < "$ASSET_VERSION")"
  [ -n "$ASSET_VER" ] || err "empty assets/deploy/DEPLOY_VERSION"
fi
if [ -n "$VER" ] && [ -n "$ASSET_VER" ] && [ "$VER" != "$ASSET_VER" ]; then
  err "DEPLOY_VERSION mismatch: server=$VER assets=$ASSET_VER"
fi
if [ -f "$BUNDLE_KT" ]; then
  FALLBACK="$(sed -n 's/.*FALLBACK_VERSION = "\(.*\)".*/\1/p' "$BUNDLE_KT" | head -1)"
  [ -n "$FALLBACK" ] || err "could not parse DeployBundle.FALLBACK_VERSION"
  if [ -n "$VER" ] && [ -n "$FALLBACK" ] && [ "$FALLBACK" != "$VER" ]; then
    err "DeployBundle.FALLBACK_VERSION=$FALLBACK but server/DEPLOY_VERSION=$VER"
  fi
fi

python3 - "$ROOT/server/third-party.lock.json" <<'PY' || err "third-party.lock.json invalid"
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
for k in ("amneziawgGo","amneziawgTools","wgcf","wireproxy","tun2socks","dockerComposeCli"):
    assert k in d, k
assert d["amneziawgGo"]["commit"]
assert len(d["wgcf"]["sha256"]["amd64"])==64
PY

if [ -f "$ROOT/scripts/test-warp-wgcf-parse.sh" ]; then
  bash "$ROOT/scripts/test-warp-wgcf-parse.sh" || err "warp wgcf parse"
fi
if [ -f "$ROOT/scripts/test-cascade-warp-prefs.sh" ]; then
  bash "$ROOT/scripts/test-cascade-warp-prefs.sh" || err "cascade/warp prefs"
fi
if [ -f "$ROOT/scripts/test-install-auto-ports.sh" ]; then
  bash "$ROOT/scripts/test-install-auto-ports.sh" || err "install auto-ports helpers"
fi
if [ -f "$ROOT/scripts/test-install-bridge-subnet.sh" ]; then
  bash "$ROOT/scripts/test-install-bridge-subnet.sh" || err "bridge subnet picker"
fi
if [ -f "$ROOT/scripts/test-install-isolation.sh" ]; then
  bash "$ROOT/scripts/test-install-isolation.sh" || err "install isolation contract"
fi
if [ -f "$ROOT/scripts/test-install-unpack.sh" ]; then
  bash "$ROOT/scripts/test-install-unpack.sh" || err "install unpack"
fi
if [ -f "$ROOT/scripts/test-install-rollback.sh" ]; then
  bash "$ROOT/scripts/test-install-rollback.sh" || err "install rollback restore"
fi
if [ -f "$ROOT/scripts/test-package-extract.sh" ]; then
  bash "$ROOT/scripts/test-package-extract.sh" || err "package extract safety"
fi

if [ -f "$ROOT/scripts/test-install-disk-guard.sh" ]; then
  bash "$ROOT/scripts/test-install-disk-guard.sh" || err "disk guard"
fi
if [ -f "$ROOT/scripts/test-install-buildkit-wipe.sh" ]; then
  bash "$ROOT/scripts/test-install-buildkit-wipe.sh" || err "buildkit wipe contract"
fi
if [ -f "$ROOT/scripts/test-repack-server-host-files.sh" ]; then
  bash "$ROOT/scripts/test-repack-server-host-files.sh" || err "repack host files"
fi

if [ "$fail" -ne 0 ]; then
  msg "deploy bundle check failed"
  exit 1
fi
echo "OK deploy bundle (version ${VER:-unknown})"
