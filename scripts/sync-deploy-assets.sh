#!/usr/bin/env bash
# The APK carries the deploy v2 installer and the stack version it targets.
# Copies them into android assets (tracked in git) and drops deploy 1.0.x files.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets/deploy"
mkdir -p "$ASSETS"
cp -f "$ROOT/server/deploy/ardtt-deploy.sh" "$ASSETS/ardtt-deploy.sh"
cp -f "$ROOT/server/DEPLOY_VERSION" "$ASSETS/DEPLOY_VERSION"
rm -f "$ASSETS/fetch-and-install.sh" "$ASSETS/ardtt-hostfiles-overlay.tar.gz"
