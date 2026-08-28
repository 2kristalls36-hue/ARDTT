#!/bin/bash
set -euo pipefail

DATA="${NVPN_DATA:-/data}"
USERS="${DATA}/users.json"
CFG_DIR="${NVPN_WDTT_CONFIG:-/etc/wdtt}"
PORT="${NVPN_BYPASS_PORT:-56003}"
# DNS pushed to Path B clients via RAWCONF — gateway dnsmasq (nvpn-dns).
DNS="${NVPN_BYPASS_DNS:-10.9.0.1}"
# Internal DTLS listen (required by upstream wdtt-server); Path B clients use -listen-raw only.
DTLS_PORT="${NVPN_WDTT_DTLS_PORT:-127.0.0.1:56000}"
WG_PORT="${NVPN_WDTT_WG_PORT:-56001}"

echo "[bypass] RAW/WRAP -listen-raw 0.0.0.0:${PORT} (wdtt-server, NoDTLS path) dns=${DNS}"

mkdir -p "${CFG_DIR}"

wait_for_users() {
  local i=0
  while [[ ! -f "${USERS}" ]]; do
    i=$((i + 1))
    if [[ $i -gt 60 ]]; then
      echo "[bypass] timeout waiting for ${USERS}" >&2
      exit 1
    fi
    echo "[bypass] waiting for provision data…"
    sleep 2
  done
}

ensure_main_password() {
  if [[ ! -s "${CFG_DIR}/main.password" ]]; then
    head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24 >"${CFG_DIR}/main.password"
    chmod 600 "${CFG_DIR}/main.password"
    echo "[bypass] generated ${CFG_DIR}/main.password"
  fi
}

sync_passwords() {
  /usr/local/bin/bypass-sync -users "${USERS}" -out "${CFG_DIR}/passwords.json"
}

wait_for_users
ensure_main_password
sync_passwords

# Host usually sets this; inside container sysctl is often RO.
if [ -w /proc/sys/net/ipv4/ip_forward ]; then
  echo 1 >/proc/sys/net/ipv4/ip_forward || true
fi

/usr/local/bin/wdtt-server \
  -listen "${DTLS_PORT}" \
  -wg-port "${WG_PORT}" \
  -listen-raw "0.0.0.0:${PORT}" \
  -config-dir "${CFG_DIR}" \
  -password-file "${CFG_DIR}/main.password" \
  -dns "${DNS}" &
SERVER_PID=$!

(
  last=$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)
  while kill -0 "${SERVER_PID}" 2>/dev/null; do
    sleep 5
    now=$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)
    if [[ "${now}" != "${last}" ]]; then
      echo "[bypass] users.json changed — sync + SIGHUP"
      sync_passwords
      kill -HUP "${SERVER_PID}" 2>/dev/null || true
      last="${now}"
    fi
  done
) &

wait "${SERVER_PID}"
