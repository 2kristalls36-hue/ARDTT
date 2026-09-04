#!/usr/bin/env bash
# Consistency checks for the VPS deploy bundle (no Docker required).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
msg() { echo "$*" >&2; }
err() { msg "FAIL: $*"; fail=1; }

INSTALLER="$ROOT/server/install.sh"
ASSET_INSTALLER="$ROOT/android/app/src/main/assets/deploy/install.sh"
VERSION_FILE="$ROOT/server/DEPLOY_VERSION"
ASSET_VERSION="$ROOT/android/app/src/main/assets/deploy/DEPLOY_VERSION"
BUNDLE_KT="$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployBundle.kt"
COMPOSE="$ROOT/server/docker-compose.yml"

[ -f "$INSTALLER" ] || err "missing $INSTALLER"
[ -f "$ASSET_INSTALLER" ] || err "missing $ASSET_INSTALLER"
[ -f "$VERSION_FILE" ] || err "missing $VERSION_FILE"
[ -f "$ASSET_VERSION" ] || err "missing $ASSET_VERSION"
[ -f "$BUNDLE_KT" ] || err "missing $BUNDLE_KT"
[ -f "$COMPOSE" ] || err "missing $COMPOSE"

if [ -f "$INSTALLER" ]; then
  bash -n "$INSTALLER" || err "bash -n failed for server/install.sh"
  grep -q 'ARDTT_PROGRESS|' "$INSTALLER" || err "installer missing ARDTT_PROGRESS protocol"
  grep -q 'ARDTT_ERROR|' "$INSTALLER" || err "installer missing ARDTT_ERROR protocol"
  grep -q 'ARDTT_DONE|' "$INSTALLER" || err "installer missing ARDTT_DONE protocol"
  grep -q 'уже распакованный стек' "$INSTALLER" || err "installer missing re-run-without-tar path"
  grep -q 'ARDTT_TELEMETRY_PORT' "$INSTALLER" || err "installer missing telemetry port"
  grep -q 'TELEMETRY_LISTEN=' "$INSTALLER" || err "installer missing TELEMETRY_LISTEN in .env"
  grep -q 'ARDTT_TELEMETRY_LISTEN=' "$INSTALLER" || err "installer missing ARDTT_TELEMETRY_LISTEN alias in .env"
  grep -q '127.0.0.1:\${TELEMETRY_PORT}/health' "$INSTALLER" || err "installer telemetry health must use TELEMETRY_PORT"
  if grep -q '127.0.0.1:9200/health' "$INSTALLER"; then
    err "installer hardcodes telemetry :9200 health check"
  fi
  grep -q 'ARDTT_ROLE' "$INSTALLER" || err "installer missing ARDTT_ROLE"
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

if [ -f "$INSTALLER" ] && [ -f "$ASSET_INSTALLER" ]; then
  if ! cmp -s "$INSTALLER" "$ASSET_INSTALLER"; then
    err "assets/deploy/install.sh differs from server/install.sh — run scripts/pack-deploy-assets.sh"
  fi
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

for context in provision direct bypass dns warp telemetry-upload; do
  [ -d "$ROOT/server/$context" ] || err "missing server/$context (needed in the deploy tar)"
done
[ -f "$ROOT/server/Dockerfile" ] || err "missing unified server/Dockerfile"
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
if [ -f "$ROOT/scripts/test-warp-hideip-prefixes.sh" ]; then
  bash "$ROOT/scripts/test-warp-hideip-prefixes.sh" || err "warp hideIp prefixes"
fi
grep -q 'wireproxy' "$ROOT/server/Dockerfile" || err "unified Dockerfile missing wireproxy"
grep -q 'tun2socks' "$ROOT/server/Dockerfile" || err "unified Dockerfile missing tun2socks"
if grep -E '^[^#]*conf/all/rp_filter' "$ROOT/server/warp/entrypoint.sh" >/dev/null; then
  err "warp must not write net.ipv4.conf.all.rp_filter (breaks other host services)"
fi

if [ "$fail" -ne 0 ]; then
  msg "deploy bundle check failed"
  exit 1
fi
echo "OK deploy bundle (version ${VER:-unknown})"
