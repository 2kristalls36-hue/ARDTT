#!/bin/bash
set -euo pipefail
echo "[direct] stub: AmneziaWG 2.0 will run here (awg0, UDP \${NVPN_DIRECT_PORT:-51820})"
echo "[direct] waiting for real binary packaging…"
# Keep container alive for compose bring-up tests
exec sleep infinity
