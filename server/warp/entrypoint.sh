#!/bin/bash
set -euo pipefail
echo "[warp] stub: wireproxy → tun2socks → warp0 (hide-IP egress)"
echo "[warp] GOMEMLIMIT=\${GOMEMLIMIT:-400MiB}; no container restart-on-OOM"
exec sleep infinity
