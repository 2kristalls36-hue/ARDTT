#!/bin/bash
set -euo pipefail
echo "[bypass] stub: RAW/WRAP listener will bind UDP \${NVPN_BYPASS_PORT:-56003}"
echo "[bypass] NoDTLS, host_id leases from /data/users.json"
exec sleep infinity
