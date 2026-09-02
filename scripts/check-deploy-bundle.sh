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
  if [ -f "$COMPOSE" ] && ! grep -Eq "build:[[:space:]]*(\\./)?${context}([[:space:]]|$)" "$COMPOSE"; then
    err "docker-compose.yml has no build context for $context"
  fi
done

if [ "$fail" -ne 0 ]; then
  msg "deploy bundle check failed"
  exit 1
fi
echo "OK deploy bundle (version ${VER:-unknown})"
