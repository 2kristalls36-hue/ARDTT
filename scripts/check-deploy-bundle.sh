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
BUNDLE_KT="$ROOT/android/app/src/main/java/com/nonamevpn/app/deploy/DeployBundle.kt"
COMPOSE="$ROOT/server/docker-compose.yml"

[ -f "$INSTALLER" ] || err "missing $INSTALLER"
[ -f "$ASSET_INSTALLER" ] || err "missing $ASSET_INSTALLER"
[ -f "$VERSION_FILE" ] || err "missing $VERSION_FILE"
[ -f "$ASSET_VERSION" ] || err "missing $ASSET_VERSION"
[ -f "$BUNDLE_KT" ] || err "missing $BUNDLE_KT"
[ -f "$COMPOSE" ] || err "missing $COMPOSE"

if [ -f "$INSTALLER" ]; then
  bash -n "$INSTALLER" || err "bash -n failed for server/install.sh"
  grep -q 'NVPN_PROGRESS|' "$INSTALLER" || err "installer missing NVPN_PROGRESS protocol"
  grep -q 'NVPN_ERROR|' "$INSTALLER" || err "installer missing NVPN_ERROR protocol"
  grep -q 'NVPN_DONE|' "$INSTALLER" || err "installer missing NVPN_DONE protocol"
  grep -q 'уже распакованный стек' "$INSTALLER" || err "installer missing re-run-without-tar path"
  grep -q 'NVPN_TELEMETRY_PORT' "$INSTALLER" || err "installer missing telemetry port"
  grep -q 'TELEMETRY_LISTEN=' "$INSTALLER" || err "installer missing TELEMETRY_LISTEN in .env"
  grep -q 'NVPN_TELEMETRY_LISTEN=' "$INSTALLER" || err "installer missing NVPN_TELEMETRY_LISTEN alias in .env"
  grep -q '127.0.0.1:\${TELEMETRY_PORT}/health' "$INSTALLER" || err "installer telemetry health must use TELEMETRY_PORT"
  if grep -q '127.0.0.1:9200/health' "$INSTALLER"; then
    err "installer hardcodes telemetry :9200 health check"
  fi
  grep -q 'NVPN_ROLE' "$INSTALLER" || err "installer missing NVPN_ROLE"
  grep -q 'ensure_cascade_keys' "$INSTALLER" || err "installer missing cascade key helper"
  grep -q 'preserve_live_cascade' "$INSTALLER" || err "installer missing live-cascade preserve"
  grep -q 'NVPN_CASCADE_FORCE_DISABLE' "$INSTALLER" || err "installer missing cascade force-disable flag"
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
  if grep -q 'NVPN_CASCADE_PASSWORD' "$INSTALLER"; then
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

if [ -f "$COMPOSE" ]; then
  grep -q 'TELEMETRY_LISTEN: \${TELEMETRY_LISTEN' "$COMPOSE" || err "compose must interpolate TELEMETRY_LISTEN from .env"
  if grep -q 'NVPN_TELEMETRY_LISTEN:-0.0.0.0:9200' "$COMPOSE"; then
    err "compose still defaults telemetry from NVPN_TELEMETRY_LISTEN (install.sh writes TELEMETRY_LISTEN)"
  fi
fi

for context in provision direct bypass dns warp telemetry-upload; do
  [ -d "$ROOT/server/$context" ] || err "missing server/$context (needed in the deploy tar)"
  if [ -f "$COMPOSE" ] && ! grep -Eq "build:[[:space:]]*(\\./)?${context}([[:space:]]|$)" "$COMPOSE"; then
    err "docker-compose.yml has no build context for $context"
  fi
done
[ -f "$ROOT/server/direct/cascade-entrypoint.sh" ] || err "missing cascade-entrypoint.sh"
if [ -f "$COMPOSE" ]; then
  grep -q 'container_name: nvpn-cascade' "$COMPOSE" || err "compose missing nvpn-cascade"
  if awk '
    $0 ~ /^  warp:/ { in_warp=1; next }
    in_warp && $0 ~ /^  [a-z]/ { in_warp=0 }
    in_warp && $0 ~ /^[[:space:]]+- dns[[:space:]]*$/ { found=1 }
    END { exit found ? 0 : 1 }
  ' "$COMPOSE"; then
    err "warp must not depend_on dns (cascade entry does not start dns)"
  fi
fi
bash -n "$ROOT/server/direct/cascade-entrypoint.sh" || err "bash -n failed for cascade-entrypoint.sh"
bash -n "$ROOT/server/warp/entrypoint.sh" || err "bash -n failed for warp/entrypoint.sh"
grep -q 'wireproxy' "$ROOT/server/warp/Dockerfile" || err "warp Dockerfile missing wireproxy"
grep -q 'tun2socks' "$ROOT/server/warp/Dockerfile" || err "warp Dockerfile missing tun2socks"

if [ "$fail" -ne 0 ]; then
  msg "deploy bundle check failed"
  exit 1
fi
echo "OK deploy bundle (version ${VER:-unknown})"
