#!/usr/bin/env bash
# Consistency checks for the VPS deploy bundle (no Docker required).
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
PACK_STACK="$ROOT/scripts/pack-stack.sh"
COMPOSE="$ROOT/server/docker-compose.yml"

[ -f "$INSTALLER" ] || err "missing $INSTALLER"
[ -f "$VERSION_FILE" ] || err "missing $VERSION_FILE"
[ -f "$ASSET_VERSION" ] || err "missing $ASSET_VERSION"
[ -f "$BUNDLE_KT" ] || err "missing $BUNDLE_KT"
[ -f "$STACK_SOURCE_KT" ] || err "missing $STACK_SOURCE_KT"
[ -f "$PACK_STACK" ] || err "missing $PACK_STACK"
[ -f "$COMPOSE" ] || err "missing $COMPOSE"

if [ -f "$INSTALLER" ]; then
  bash -n "$INSTALLER" || err "bash -n failed for server/install.sh"
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
    err "ARDTT_PROGRESS| and ARDTT_DONE| must appear in the first 400 chars of install.sh (APK ≤0.5.245 sniff)"
  fi
  grep -q 'find_server_tree' "$INSTALLER" || err "installer missing find_server_tree (GitHub archive layout)"
  grep -q 'extract_archive_to_staging' "$INSTALLER" || err "installer missing extract_archive_to_staging"
  grep -q 'fetch_stack_from_git' "$INSTALLER" || err "installer missing fetch_stack_from_git"
  grep -q 'ARDTT_GIT_REF' "$INSTALLER" || err "installer missing ARDTT_GIT_REF"
  grep -q 'ARDTT_GIT_REPO' "$INSTALLER" || err "installer missing ARDTT_GIT_REPO"
  grep -q 'github_source_tarball_url' "$INSTALLER" || err "installer missing github_source_tarball_url"
  grep -q 'уже распакованный стек' "$INSTALLER" || err "installer missing re-run-without-tar path"
  grep -q 'ARDTT_TELEMETRY_PORT' "$INSTALLER" || err "installer missing telemetry port"
  grep -q 'telemetry только внутри контейнера (nginx/socat)' "$INSTALLER" \
    || err "installer must keep telemetry inside the container when nginx and socat own 9200/9199"
  if grep -q 'внутри контейнера telemetry не стартуем' "$INSTALLER"; then
    err "installer must not skip gunicorn because host telemetry ports are busy"
  fi
  grep -q 'TELEMETRY_LISTEN=' "$INSTALLER" || err "installer missing TELEMETRY_LISTEN in .env"
  grep -q 'ARDTT_TELEMETRY_LISTEN=' "$INSTALLER" || err "installer missing ARDTT_TELEMETRY_LISTEN alias in .env"
  grep -q '127.0.0.1:\${TELEMETRY_PORT}/health' "$INSTALLER" || err "installer telemetry health must use TELEMETRY_PORT"
  if grep -q '127.0.0.1:9200/health' "$INSTALLER"; then
    err "installer hardcodes telemetry :9200 health check"
  fi
  grep -q 'ARDTT_AUTO_PORTS' "$INSTALLER" || err "installer missing ARDTT_AUTO_PORTS"
  grep -q 'find_free_udp_port' "$INSTALLER" || err "installer missing find_free_udp_port"
  grep -q 'resolve_udp_host_port' "$INSTALLER" || err "installer missing resolve_udp_host_port"
  grep -q 'direct_port=' "$INSTALLER" || err "installer ARDTT_DONE must report direct_port"
  grep -q 'envPort("ARDTT_DIRECT_PORT")' "$ROOT/server/provision/main.go" \
    || err "provision must sync DirectPort from ARDTT_DIRECT_PORT"
  grep -q 'ensure_cascade_keys' "$INSTALLER" || err "installer missing cascade key helper"
  grep -q 'prepare_docker_build' "$INSTALLER" || err "installer missing prepare_docker_build (dangling image prune)"
  grep -q 'foreign_docker_workloads' "$INSTALLER" || err "installer must detect other Docker workloads on a shared VPS"
  grep -q 'cleanup_host_dataplane' "$INSTALLER" || err "installer must strip leftover host TUN/iptables after the old host-net stack"
  grep -q 'пропускаем builder prune -af и restart dockerd' "$INSTALLER" || err "installer must not restart dockerd when other containers exist"
  grep -q 'COMPOSE_PROFILES' "$INSTALLER" || err "installer missing COMPOSE_PROFILES (isolated vs hostnet)"
  grep -q 'ARDTT_NETWORK_MODE' "$INSTALLER" || err "installer missing ARDTT_NETWORK_MODE"
  grep -q 'docker exec ardtt' "$INSTALLER" || err "installer health must exec the unified ardtt container"
  grep -q 'container_name: ardtt' "$COMPOSE" || err "compose missing unified container_name ardtt"
  grep -q 'profiles: \["isolated"\]' "$COMPOSE" || err "compose missing isolated profile"
  grep -q 'profiles: \["hostnet"\]' "$COMPOSE" || err "compose missing hostnet profile"
  grep -q 'network_mode: host' "$COMPOSE" || err "compose missing hostnet fallback"
  grep -q '/dev/net/tun' "$COMPOSE" || err "compose missing /dev/net/tun"
  grep -q 'NET_ADMIN' "$COMPOSE" || err "compose missing NET_ADMIN"
  [ -f "$ROOT/server/Dockerfile" ] || err "missing server/Dockerfile (unified image)"
  [ -f "$ROOT/server/entrypoint.sh" ] || err "missing server/entrypoint.sh"
  bash -n "$ROOT/server/entrypoint.sh" || err "bash -n failed for server/entrypoint.sh"
  if grep -qE 'build:[[:space:]]*\./provision' "$COMPOSE"; then
    err "compose still builds split provision image — use the unified Dockerfile"
  fi
  grep -q 'reset_docker_buildkit' "$INSTALLER" || err "installer missing reset_docker_buildkit (wipe /var/lib/docker/buildkit)"
  grep -q 'wipe_dir_best_effort /var/lib/docker/buildkit' "$INSTALLER" || err "installer must wipe BuildKit via wipe_dir_best_effort (busy overlay)"
  grep -q 'unmount_tree' "$INSTALLER" || err "installer must unmount BuildKit executor rootfs before rm"
  grep -q 'Подготовка: очистка кэша Docker' "$INSTALLER" || err "installer must clean Docker junk at the start of an update"
  grep -q 'docker buildx prune -af' "$INSTALLER" || err "installer must prune buildx cache, not only builder"
  grep -q 'Мало RAM — останавливаем стек и сбрасываем BuildKit' "$INSTALLER" || err "installer must stop the stack before golang rebuilds on tiny VPS"
  grep -q 'build --no-cache' "$INSTALLER" || err "installer must retry compose build --no-cache after a snapshot failure"
  grep -q 'поднимаем прежний стек' "$INSTALLER" || err "installer must restore the previous stack if a build fails after compose down"
  grep -q 'ARDTT_CASCADE_FORCE_DISABLE' "$INSTALLER" || err "installer missing cascade force-disable flag"
  grep -q 'cascade.peer.endpoint' "$INSTALLER" || err "installer must persist cascade peer endpoint"
  grep -q 'exit-hideip' "$INSTALLER" || err "installer must set WARP_MODE=exit-hideip on the cascade exit"
  grep -q 'WARP_MODE="passthrough"' "$INSTALLER" || err "cascade entry must use WARP passthrough"
  grep -q 'hide-ip-prefixes' "$ROOT/server/warp/entrypoint.sh" || err "exit warp must poll hide-ip-prefixes"
  grep -q '/v1/hide-ip-prefixes' "$ROOT/server/provision/main.go" || err "provision missing GET /v1/hide-ip-prefixes"
  grep -q 'cleanup_stale_deploy_files' "$INSTALLER" || err "installer missing leftover-file cleanup"
  grep -q 'clear_legacy_kernel_warp' "$ROOT/server/warp/entrypoint.sh" || err "warp entrypoint must drop leftover kernel-WG warp0"
  grep -q 'swap_target_mb' "$INSTALLER" || err "installer missing small-disk swap cap"
  grep -q 'disk_need_mb' "$INSTALLER" || err "installer missing scaled disk threshold"
  grep -q 'install-live.log' "$INSTALLER" || err "installer must remove legacy install-live.log"
  if awk '
    $0 ~ /^cleanup_stale_deploy_files\(\)/ { in_fn=1; next }
    in_fn && $0 ~ /^}/ { in_fn=0 }
    in_fn && $0 ~ /rm / && $0 ~ /stack\.staging/ { found=1 }
    END { exit found ? 0 : 1 }
  ' "$INSTALLER"; then
    err "cleanup_stale_deploy_files must not delete in-progress stack.staging"
  fi
  if grep -E '^[^#]*image prune -a' "$INSTALLER" >/dev/null; then
    err "install.sh must not docker image prune -a (drops unused tagged stack images)"
  fi
  if grep -q 'ARDTT_CASCADE_PASSWORD' "$INSTALLER"; then
    err "installer must not write cascade SSH password into .env"
  fi
fi

if [ -f "$PACK_STACK" ]; then
  bash -n "$PACK_STACK" || err "bash -n failed for scripts/pack-stack.sh"
  grep -q 'ardtt-stack-' "$PACK_STACK" || err "pack-stack.sh must name GitHub release asset ardtt-stack-*.tar.gz"
  grep -q 'android/app/src/main/assets/deploy' "$PACK_STACK" || err "pack-stack.sh must sync assets/deploy/DEPLOY_VERSION"
  if grep -q 'stack.tar.gz.bin' "$PACK_STACK"; then
    err "pack-stack.sh must not copy the archive into the APK"
  fi
fi

if [ -f "$STACK_SOURCE_KT" ]; then
  grep -q 'ardtt-stack-' "$STACK_SOURCE_KT" || err "DeployStackSource must name ardtt-stack-*.tar.gz"
  grep -q 'raw.githubusercontent.com' "$STACK_SOURCE_KT" || err "DeployStackSource must fetch install.sh from GitHub"
  grep -q 'extractInstallScript' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployStackFetcher.kt" \
    || err "DeployStackFetcher must extract install.sh from the downloaded tarball"
  grep -q 'SNIFF_CHARS' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployStackFetcher.kt" \
    || err "DeployStackFetcher must sniff more than a 400-char install.sh prefix"
fi

if [ -f "$ROOT/android/app/src/main/assets/deploy/install.sh" ]; then
  err "assets/deploy/install.sh must not be bundled; the phone downloads it from GitHub"
fi
if ls "$ROOT"/android/app/src/main/assets/deploy/stack.tar.gz* >/dev/null 2>&1; then
  err "assets/deploy must not contain stack.tar.gz*; publish via scripts/pack-stack.sh"
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

if [ -f "$PACK_STACK" ] && [ -n "$VER" ]; then
  packdir="$(mktemp -d)"
  packed="$packdir/ardtt-stack-${VER}.tar.gz"
  if bash "$PACK_STACK" "$packed"; then
    members="$(tar -tzf "$packed" 2>/dev/null || true)"
    printf '%s\n' "$members" | grep -Fxq 'docker-compose.yml' \
      || err "packed stack missing docker-compose.yml at archive root"
    printf '%s\n' "$members" | grep -Fxq 'install.sh' \
      || err "packed stack missing install.sh"
    printf '%s\n' "$members" | grep -q '^provision/' \
      || err "packed stack missing provision/"
    if printf '%s\n' "$members" | grep -q '^data/'; then
      err "packed stack must not include server/data/"
    fi
  else
    err "scripts/pack-stack.sh failed"
  fi
  rm -rf "$packdir"
fi

for context in provision direct bypass dns warp telemetry-upload; do
  [ -d "$ROOT/server/$context" ] || err "missing server/$context (needed in the deploy tar)"
done
[ -f "$ROOT/server/Dockerfile" ] || err "missing unified server/Dockerfile"
grep -q 'ARG TARGETARCH=amd64' "$ROOT/server/Dockerfile" \
  || err "Dockerfile TARGETARCH must default to amd64"
[ -f "$ROOT/server/entrypoint.sh" ] || err "missing unified server/entrypoint.sh"
[ -f "$ROOT/server/direct/cascade-entrypoint.sh" ] || err "missing cascade-entrypoint.sh"
if [ -f "$COMPOSE" ]; then
  grep -q 'container_name: ardtt' "$COMPOSE" || err "compose missing ardtt"
  grep -q 'TELEMETRY_LISTEN: \${TELEMETRY_LISTEN' "$COMPOSE" || err "compose must interpolate TELEMETRY_LISTEN from .env"
  if grep -q 'ARDTT_TELEMETRY_LISTEN:-0.0.0.0:9200' "$COMPOSE"; then
    err "compose still defaults telemetry from ARDTT_TELEMETRY_LISTEN (install.sh writes TELEMETRY_LISTEN)"
  fi
fi
bash -n "$ROOT/server/direct/cascade-entrypoint.sh" || err "bash -n failed for cascade-entrypoint.sh"
bash -n "$ROOT/server/warp/entrypoint.sh" || err "bash -n failed for warp/entrypoint.sh"
if [ -f "$ROOT/scripts/test-warp-wgcf-parse.sh" ]; then
  bash "$ROOT/scripts/test-warp-wgcf-parse.sh" || err "warp wgcf parse"
fi
if [ -f "$ROOT/scripts/test-cascade-warp-prefs.sh" ]; then
  bash "$ROOT/scripts/test-cascade-warp-prefs.sh" || err "cascade/warp prefs"
fi
if [ -f "$ROOT/scripts/test-install-buildkit-wipe.sh" ]; then
  bash "$ROOT/scripts/test-install-buildkit-wipe.sh" || err "install buildkit busy wipe"
fi
if [ -f "$ROOT/scripts/test-install-auto-ports.sh" ]; then
  bash "$ROOT/scripts/test-install-auto-ports.sh" || err "install auto-ports helpers"
fi
grep -q 'wireproxy' "$ROOT/server/Dockerfile" || err "unified Dockerfile missing wireproxy"
grep -q 'tun2socks' "$ROOT/server/Dockerfile" || err "unified Dockerfile missing tun2socks"
if grep -E '^[^#]*conf/all/rp_filter' "$ROOT/server/warp/entrypoint.sh" >/dev/null; then
  err "warp must not write net.ipv4.conf.all.rp_filter (breaks other host services)"
fi
if grep -E '^[^#]*conf/\*/rp_filter' "$ROOT/server/direct/cascade-entrypoint.sh" >/dev/null; then
  err "cascade must not write every iface rp_filter (breaks other host services)"
fi
if grep -E '^[^#]*conf/all/rp_filter' "$ROOT/server/direct/cascade-entrypoint.sh" >/dev/null; then
  err "cascade must not write net.ipv4.conf.all.rp_filter (breaks other host services)"
fi
grep -q 'ARDTT_CASCADE_ROLE:-${ARDTT_ROLE:-entry}' "$ROOT/server/direct/cascade-entrypoint.sh" \
  || err "cascade must inherit ARDTT_ROLE so the exit hop listens on UDP"
grep -q 'ARDTT_CASCADE_ROLE: ${ARDTT_ROLE:-entry}' "$COMPOSE" \
  || err "compose must pass ARDTT_CASCADE_ROLE from ARDTT_ROLE"
grep -q 'ARDTT_WARP_STATE:-/data/warp' "$ROOT/server/warp/entrypoint.sh" \
  || err "warp default state dir must be /data/warp"
grep -q 'ARDTT_WARP_STATE: /data/warp' "$COMPOSE" || err "compose must persist WARP on /data/warp"
if grep -q '/var/lib/ardtt-warp' "$COMPOSE"; then
  err "compose must not use ephemeral /var/lib/ardtt-warp for WARP state"
fi
grep -q 'ARDTT_CASCADE_ROLE=$ROLE' "$INSTALLER" || err "installer must write ARDTT_CASCADE_ROLE"
grep -q 'DIRECT_PORT="$CASCADE_LISTEN_PORT"' "$INSTALLER" \
  || err "exit install must publish cascade UDP via DIRECT_PORT"
grep -Fq 'iif (awg0|wdttraw0|warp0|cascade0)' "$INSTALLER" \
  || err "host dataplane cleanup must not delete foreign lookup 51820 rules"

if [ "$fail" -ne 0 ]; then
  msg "deploy bundle check failed"
  exit 1
fi
echo "OK deploy bundle (version ${VER:-unknown})"
