#!/bin/bash
set -euo pipefail

DATA="${ARDTT_DATA:-/data}"
USERS="${DATA}/users.json"
CFG_DIR="${ARDTT_WDTT_CONFIG:-/etc/wdtt}"
PORT="${ARDTT_BYPASS_PORT:-56003}"
# DNS pushed to Path B clients via RAWCONF — gateway dnsmasq (ardtt-dns).
DNS="${ARDTT_BYPASS_DNS:-10.9.0.1}"

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
  /usr/local/bin/bypass-sync \
    -users "${USERS}" \
    -out "${CFG_DIR}/passwords.json" \
    -traffic "${DATA}/bypass-traffic.json"
}

wait_for_users
ensure_main_password
sync_passwords

# Host usually sets this; inside container sysctl is often RO.
if [ -w /proc/sys/net/ipv4/ip_forward ]; then
  echo 1 >/proc/sys/net/ipv4/ip_forward || true
fi

setup_forwarding() {
  if ! command -v iptables >/dev/null 2>&1; then
    return 0
  fi
  local wan comment="ARDTT_BYPASS_MANAGED"
  local iface="${ARDTT_BYPASS_IFACE:-wdttraw0}"
  wan="$(ip route show default 0.0.0.0/0 2>/dev/null | awk '{print $5; exit}')"
  [[ -z "${wan}" ]] && wan="eth0"

  # Same as Direct: FORWARD policy is DROP; Docker inserts DOCKER-FORWARD
  # before qWDTT's appended WDTT_MANAGED rules. Insert at top so RAW
  # (10.9.0.0/24) is accepted like awg0.
  iptables -C FORWARD -i "${iface}" -m comment --comment "${comment}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -i "${iface}" -m comment --comment "${comment}" -j ACCEPT || true
  iptables -C FORWARD -o "${iface}" -m comment --comment "${comment}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -o "${iface}" -m comment --comment "${comment}" -j ACCEPT || true

  if [ "${ARDTT_CASCADE_ENABLED:-0}" = "1" ]; then
    echo "[bypass] cascade on — skip WAN MASQ for 10.9.0.0/24 (hop owns egress)"
  else
    iptables -t nat -C POSTROUTING -s 10.9.0.0/24 -o "${wan}" -m comment --comment "${comment}" -j MASQUERADE 2>/dev/null \
      || iptables -t nat -A POSTROUTING -s 10.9.0.0/24 -o "${wan}" -m comment --comment "${comment}" -j MASQUERADE || true
  fi

  # Inner TCP over RAW plus TURN overhead black-holes HTTPS without MSS
  # clamp — small keepalives work, pages stall (~0.09 MB). MTU matches
  # cascade0 / Direct (1280); 1300 did not fit the hop.
  iptables -t mangle -C FORWARD -o "${iface}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${comment}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null \
    || iptables -t mangle -A FORWARD -o "${iface}" -p tcp --tcp-flags SYN,RST SYN \
      -m comment --comment "${comment}" -j TCPMSS --clamp-mss-to-pmtu || true
  iptables -t mangle -C FORWARD -i "${iface}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${comment}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null \
    || iptables -t mangle -A FORWARD -i "${iface}" -p tcp --tcp-flags SYN,RST SYN \
      -m comment --comment "${comment}" -j TCPMSS --clamp-mss-to-pmtu || true
}

setup_forwarding

/usr/local/bin/wdtt-server \
  -listen-raw "0.0.0.0:${PORT}" \
  -config-dir "${CFG_DIR}" \
  -password-file "${CFG_DIR}/main.password" \
  -dns "${DNS}" &
SERVER_PID=$!

users_cred_fp() {
  jq -c '[.users[] | {p:.password,h:.hostId,d:(.deactivated==true),e:(.expiresAt//0),m:(.maxDevices//1),id:(.deviceId//""),ids:((.deviceIds//[])|sort)}] | sort_by(.p)' \
    "${USERS}" 2>/dev/null || echo "unreadable"
}

(
  last=$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)
  last_fp="$(users_cred_fp)"
  ticks=0
  while kill -0 "${SERVER_PID}" 2>/dev/null; do
    sleep 5
    ticks=$((ticks + 1))
    now=$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)
    if [[ "${now}" != "${last}" ]]; then
      new_fp="$(users_cred_fp)"
      sync_passwords
      if [[ "${new_fp}" != "${last_fp}" ]]; then
        echo "[bypass] users.json credentials changed — sync + SIGHUP"
        kill -HUP "${SERVER_PID}" 2>/dev/null || true
        last_fp="${new_fp}"
      else
        echo "[bypass] users.json mtime only (heartbeat) — skip SIGHUP"
      fi
      last="${now}"
    elif [[ $((ticks % 6)) -eq 0 ]]; then
      # Refresh traffic snapshot ~every 30s for provision admin UI.
      /usr/local/bin/bypass-sync \
        -users "${USERS}" \
        -out "${CFG_DIR}/passwords.json" \
        -traffic "${DATA}/bypass-traffic.json" \
        -traffic-only >/dev/null 2>&1 || true
    fi
  done
) &

wait "${SERVER_PID}"
