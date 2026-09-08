#!/usr/bin/env bash
# Compatibility wrapper. The VPS stack is not packed into the APK.
# Production payload: scripts/pack-server-package.sh
# This script only keeps assets/deploy/DEPLOY_VERSION in lockstep.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/pack-stack.sh" "$@"
