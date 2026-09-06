#!/usr/bin/env bash
# Compatibility wrapper. The VPS stack is no longer packed into the APK;
# GitHub Releases (and source archives) are the source of deploy data.
# This script still builds dist/ardtt-stack-<version>.tar.gz and keeps
# assets/deploy/DEPLOY_VERSION in lockstep with server/DEPLOY_VERSION.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/pack-stack.sh" "$@"
