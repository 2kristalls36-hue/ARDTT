#!/bin/bash
set -euo pipefail

DATA="${NVPN_DATA:-/data}"
USERS="${DATA}/users.json"
CONF_DIR="/etc/amneziawg"
CONF="${CONF_DIR}/awg0.conf"
IFACE="${NVPN_DIRECT_IFACE:-awg0}"
PORT="${NVPN_DIRECT_PORT:-51820}"

echo "[direct] AmneziaWG userspace (amneziawg-go) on UDP ${PORT}"

mkdir -p "${CONF_DIR}"

wait_for_users() {
  local i=0
  while [[ ! -f "${USERS}" ]]; do
    i=$((i + 1))
    if [[ $i -gt 60 ]]; then
      echo "[direct] timeout waiting for ${USERS}" >&2
      exit 1
    fi
    echo "[direct] waiting for provision data…"
    sleep 2
  done
}

render_conf() {
  /usr/local/bin/direct-sync -users "${USERS}" -out "${CONF}"
}

setup_forwarding() {
  # Host usually sets this; inside container sysctl is often RO.
  if [ -w /proc/sys/net/ipv4/ip_forward ]; then
    echo 1 >/proc/sys/net/ipv4/ip_forward || true
  fi
  if ! command -v iptables >/dev/null 2>&1; then
    return 0
  fi

  local wan comment="AWG_DIRECT_MANAGED"
  wan="$(ip route show default 0.0.0.0/0 2>/dev/null | awk '{print $5; exit}')"
  [[ -z "${wan}" ]] && wan="eth0"

  # FORWARD accept for awg0 (FORWARD policy is often DROP on Docker hosts).
  iptables -C FORWARD -i "${IFACE}" -m comment --comment "${comment}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -i "${IFACE}" -m comment --comment "${comment}" -j ACCEPT || true
  iptables -C FORWARD -o "${IFACE}" -m comment --comment "${comment}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -o "${IFACE}" -m comment --comment "${comment}" -j ACCEPT || true

  if [ "${NVPN_CASCADE_ENABLED:-0}" = "1" ]; then
    echo "[direct] cascade on — skip WAN MASQ for 10.8.0.0/24 (hop owns egress)"
    return 0
  fi

  # Prefer WAN-scoped MASQ so we do not NAT into warp0 by accident.
  iptables -t nat -C POSTROUTING -s 10.8.0.0/24 -o "${wan}" -m comment --comment "${comment}" -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -o "${wan}" -m comment --comment "${comment}" -j MASQUERADE || true
  # Keep a broad fallback for unusual routing setups.
  iptables -t nat -C POSTROUTING -s 10.8.0.0/24 -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -j MASQUERADE || true
}

wait_for_users
render_conf
setup_forwarding

# Clean leftover iface from previous crash
ip link del "${IFACE}" 2>/dev/null || true

echo "[direct] starting amneziawg-go -f ${IFACE}"
amneziawg-go -f "${IFACE}" &
AWG_PID=$!

# Give UAPI socket a moment
for _ in $(seq 1 30); do
  if [[ -S "/var/run/amneziawg/${IFACE}.sock" ]]; then
    break
  fi
  sleep 0.2
done

if ! awg setconf "${IFACE}" "${CONF}"; then
  echo "[direct] awg setconf failed" >&2
  kill "${AWG_PID}" 2>/dev/null || true
  exit 1
fi

ip link set "${IFACE}" up 2>/dev/null || true
ADDR=$(tr -d '[:space:]' <"${CONF}.address" 2>/dev/null || true)
if [[ -n "${ADDR}" ]]; then
  ip addr replace "${ADDR}" dev "${IFACE}" 2>/dev/null || true
fi

echo "[direct] ready iface=${IFACE} port=${PORT} addr=${ADDR:-?} peers=$(grep -c '^\[Peer\]' "${CONF}" || true)"

# Hot-reload peers when users.json changes
(
  last=$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)
  while kill -0 "${AWG_PID}" 2>/dev/null; do
    sleep 5
    now=$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)
    if [[ "${now}" != "${last}" ]]; then
      echo "[direct] users.json changed — re-sync"
      render_conf
      awg syncconf "${IFACE}" "${CONF}" || awg setconf "${IFACE}" "${CONF}" || true
      ADDR=$(tr -d '[:space:]' <"${CONF}.address" 2>/dev/null || true)
      if [[ -n "${ADDR}" ]]; then
        ip addr replace "${ADDR}" dev "${IFACE}" 2>/dev/null || true
      fi
      last="${now}"
    fi
  done
) &

wait "${AWG_PID}"
