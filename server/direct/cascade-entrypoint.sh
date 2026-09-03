#!/bin/bash
# Inter-VPS AmneziaWG hop (cascade0).
#
# entry: VPS1 — phone clients stay on awg0/wdttraw0; all their traffic is
#        policy-routed into cascade0 toward the exit. No WAN MASQ fallback.
#        If the hop handshake dies, awg0/wdttraw0 are taken down so the
#        phone tunnel falls.
# exit:  VPS2 — listens for the entry peer; DNS + WARP live on this host.
set -euo pipefail

DATA="${NVPN_DATA:-/data}"
ROLE="${NVPN_CASCADE_ROLE:-entry}"
IFACE="${NVPN_CASCADE_IFACE:-cascade0}"
TABLE="${NVPN_CASCADE_TABLE:-51821}"
LISTEN="${NVPN_CASCADE_LISTEN_PORT:-51820}"
PEER_ENDPOINT="${NVPN_CASCADE_PEER_ENDPOINT:-}"
PEER_PUB_ENV="${NVPN_CASCADE_PEER_PUBLIC_KEY:-}"
ENTRY_ADDR="${NVPN_CASCADE_ENTRY_ADDR:-10.10.0.1/30}"
EXIT_ADDR="${NVPN_CASCADE_EXIT_ADDR:-10.10.0.2/30}"
PRIV_FILE="${DATA}/cascade.priv"
PUB_FILE="${DATA}/cascade.pub"
PEER_FILE="${DATA}/cascade.peer.pub"
STATUS_FILE="${DATA}/cascade.status"
CONF_DIR="/etc/amneziawg"
CONF="${CONF_DIR}/${IFACE}.conf"
COMMENT="NVPN_CASCADE_MANAGED"
DNS_DST="${NVPN_CASCADE_DNS:-10.10.0.2}"
# WireGuard/AWG latest-handshake only moves on a full handshake (~120s rekey),
# not on keepalives. 45s was dropping a healthy hop.
STALE_SEC="${NVPN_CASCADE_STALE_SEC:-180}"
CLIENT_NETS="10.8.0.0/24 10.9.0.0/24"

mkdir -p "${CONF_DIR}" "${DATA}"
chmod 700 "${DATA}" 2>/dev/null || true

echo "[cascade] role=${ROLE} iface=${IFACE} listen=${LISTEN}"

if [ -w /proc/sys/net/ipv4/ip_forward ]; then
  echo 1 >/proc/sys/net/ipv4/ip_forward || true
fi
# Strict rp_filter drops WARP replies whose reverse path is cascade0, not warp0.
for rp in /proc/sys/net/ipv4/conf/*/rp_filter; do
  [ -w "${rp}" ] && echo 2 >"${rp}" || true
done

need_awg() {
  command -v awg >/dev/null 2>&1 || { echo "[cascade] awg missing" >&2; exit 1; }
}

ensure_keys() {
  if [ ! -s "${PRIV_FILE}" ]; then
    echo "[cascade] generating hop keypair"
    awg genkey >"${PRIV_FILE}"
    chmod 600 "${PRIV_FILE}"
  fi
  awg pubkey <"${PRIV_FILE}" >"${PUB_FILE}"
  chmod 644 "${PUB_FILE}" 2>/dev/null || true
  echo "[cascade] publicKey=$(tr -d '[:space:]' <"${PUB_FILE}")"
}

peer_public_key() {
  if [ -n "${PEER_PUB_ENV}" ]; then
    printf '%s' "${PEER_PUB_ENV}" | tr -d '[:space:]'
    return 0
  fi
  if [ -s "${PEER_FILE}" ]; then
    tr -d '[:space:]' <"${PEER_FILE}"
    return 0
  fi
  printf ''
}

write_conf() {
  local priv pub peer addr listen_line endpoint_line allowed
  priv="$(tr -d '[:space:]' <"${PRIV_FILE}")"
  pub="$(tr -d '[:space:]' <"${PUB_FILE}")"
  peer="$(peer_public_key)"
  if [ "${ROLE}" = "exit" ]; then
    addr="${EXIT_ADDR}"
    listen_line="ListenPort = ${LISTEN}"
    endpoint_line=""
    allowed="10.8.0.0/24, 10.9.0.0/24, 10.10.0.0/30"
  else
    addr="${ENTRY_ADDR}"
    listen_line=""
    if [ -n "${PEER_ENDPOINT}" ]; then
      endpoint_line="Endpoint = ${PEER_ENDPOINT}"
    else
      endpoint_line=""
    fi
    allowed="0.0.0.0/0"
  fi
  {
    echo "[Interface]"
    echo "PrivateKey = ${priv}"
    [ -n "${listen_line}" ] && echo "${listen_line}"
    echo "Jc = 4"
    echo "Jmin = 40"
    echo "Jmax = 70"
    echo "S1 = 0"
    echo "S2 = 0"
    echo "S3 = 0"
    echo "S4 = 0"
    echo "H1 = 1-100"
    echo "H2 = 101-200"
    echo "H3 = 201-300"
    echo "H4 = 301-400"
    if [ -n "${peer}" ]; then
      echo
      echo "[Peer]"
      echo "PublicKey = ${peer}"
      [ -n "${endpoint_line}" ] && echo "${endpoint_line}"
      echo "AllowedIPs = ${allowed}"
      echo "PersistentKeepalive = 25"
    fi
  } >"${CONF}"
  printf '%s\n' "${addr}" >"${CONF}.address"
  echo "[cascade] wrote ${CONF} peer=${peer:-none} addr=${addr}"
}

# iptables-nft -S quoting breaks "${spec/-A/-D}". Delete by line number instead.
# Only walk chains that exist in this table (nat has no FORWARD/INPUT).
delete_commented_chain() {
  local table="$1" chain="$2"
  local n
  command -v iptables >/dev/null 2>&1 || return 0
  iptables -t "${table}" -L "${chain}" >/dev/null 2>&1 || return 0
  while true; do
    n="$(iptables -t "${table}" -L "${chain}" --line-numbers -n 2>/dev/null \
      | awk -v c="${COMMENT}" '$0 ~ c {print $1}' | tail -1 || true)"
    [ -n "${n}" ] || break
    iptables -t "${table}" -D "${chain}" "${n}" 2>/dev/null || break
  done
}

delete_commented() {
  local table="$1"
  local chain
  case "${table}" in
    nat) set -- PREROUTING POSTROUTING OUTPUT ;;
    mangle) set -- PREROUTING INPUT FORWARD OUTPUT POSTROUTING ;;
    filter|*) set -- INPUT FORWARD OUTPUT ;;
  esac
  for chain in "$@"; do
    delete_commented_chain "${table}" "${chain}"
  done
}

wan_iface() {
  ip route show default 0.0.0.0/0 2>/dev/null | awk '{print $5; exit}'
}

# Leftover MASQ from a previous standalone stack would leak 10.8/10.9 to WAN.
strip_stale_wan_masq() {
  command -v iptables >/dev/null 2>&1 || return 0
  local wan net comment
  wan="$(wan_iface)"
  for net in ${CLIENT_NETS}; do
    while iptables -t nat -D POSTROUTING -s "${net}" -j MASQUERADE 2>/dev/null; do :; done
    if [ -n "${wan}" ]; then
      while iptables -t nat -D POSTROUTING -s "${net}" -o "${wan}" -j MASQUERADE 2>/dev/null; do :; done
    fi
    for comment in AWG_DIRECT_MANAGED WDTT_RAW_MANAGED NVPN_BYPASS_MANAGED; do
      if [ -n "${wan}" ]; then
        while iptables -t nat -D POSTROUTING -s "${net}" -o "${wan}" -m comment --comment "${comment}" -j MASQUERADE 2>/dev/null; do :; done
      fi
      while iptables -t nat -D POSTROUTING -s "${net}" -m comment --comment "${comment}" -j MASQUERADE 2>/dev/null; do :; done
    done
  done
}

# Entry must not NAT client subnets out of its own WAN — that would leak
# the first VPS IP instead of forwarding through the hop.
strip_entry_wan_masq() {
  [ "${ROLE}" = "entry" ] || return 0
  strip_stale_wan_masq
  command -v iptables >/dev/null 2>&1 || return 0
  local wan net
  wan="$(wan_iface)"
  for net in ${CLIENT_NETS}; do
    if [ -n "${wan}" ]; then
      iptables -t nat -D POSTROUTING -s "${net}" -o "${wan}" -p udp --dport 53 \
        -m comment --comment NVPN_WARP_DNS_MAIN -j MASQUERADE 2>/dev/null || true
      iptables -t nat -D POSTROUTING -s "${net}" -o "${wan}" -p tcp --dport 53 \
        -m comment --comment NVPN_WARP_DNS_MAIN -j MASQUERADE 2>/dev/null || true
    fi
  done
  # Standalone warp left iif awg0/wdttraw0 :53 → main. Drop so client DNS
  # follows the hop policy table (DNAT still rewrites dest to 10.10.0.2).
  local prio
  for prio in $(seq 100 110); do
    ip rule del pref "${prio}" 2>/dev/null || true
  done
}

setup_entry_policy() {
  local prio
  for prio in $(seq 320 335); do
    ip rule del pref "${prio}" 2>/dev/null || true
  done
  ip route replace default dev "${IFACE}" table "${TABLE}" 2>/dev/null || \
    ip route add default dev "${IFACE}" table "${TABLE}" 2>/dev/null || true
  ip rule add from 10.8.0.0/24 lookup "${TABLE}" priority 320 2>/dev/null || true
  ip rule add from 10.9.0.0/24 lookup "${TABLE}" priority 321 2>/dev/null || true
  # On-link hop DNS (10.10.0.2) uses cascade0 without stealing host default.
  ip route replace "${DNS_DST}" dev "${IFACE}" 2>/dev/null || \
    ip route add "${DNS_DST}" dev "${IFACE}" 2>/dev/null || true
}

# Userspace amneziawg-go does not install AllowedIPs into the kernel FIB.
# Return traffic from WARP to 10.8/10.9 must go back out cascade0.
setup_exit_client_routes() {
  [ "${ROLE}" = "exit" ] || return 0
  local net
  for net in ${CLIENT_NETS}; do
    ip route replace "${net}" dev "${IFACE}" 2>/dev/null || \
      ip route add "${net}" dev "${IFACE}" 2>/dev/null || true
  done
}

setup_entry_dns_dnat() {
  command -v iptables >/dev/null 2>&1 || return 0
  delete_commented nat
  local iface proto
  for iface in awg0 wdttraw0; do
    for proto in udp tcp; do
      iptables -t nat -C PREROUTING -i "${iface}" -p "${proto}" -m "${proto}" --dport 53 \
        -m comment --comment "${COMMENT}" -j DNAT --to-destination "${DNS_DST}:53" 2>/dev/null \
        || iptables -t nat -A PREROUTING -i "${iface}" -p "${proto}" -m "${proto}" --dport 53 \
          -m comment --comment "${COMMENT}" -j DNAT --to-destination "${DNS_DST}:53" || true
    done
  done
}

setup_forwarding() {
  command -v iptables >/dev/null 2>&1 || return 0
  iptables -C FORWARD -i "${IFACE}" -m comment --comment "${COMMENT}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -i "${IFACE}" -m comment --comment "${COMMENT}" -j ACCEPT || true
  iptables -C FORWARD -o "${IFACE}" -m comment --comment "${COMMENT}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -o "${IFACE}" -m comment --comment "${COMMENT}" -j ACCEPT || true
  iptables -t mangle -C FORWARD -o "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${COMMENT}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null \
    || iptables -t mangle -A FORWARD -o "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
      -m comment --comment "${COMMENT}" -j TCPMSS --clamp-mss-to-pmtu || true
  iptables -t mangle -C FORWARD -i "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${COMMENT}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null \
    || iptables -t mangle -A FORWARD -i "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
      -m comment --comment "${COMMENT}" -j TCPMSS --clamp-mss-to-pmtu || true
  if [ "${ROLE}" = "entry" ]; then
    strip_entry_wan_masq
    setup_entry_dns_dnat
    setup_entry_policy
  else
    strip_stale_wan_masq
    setup_exit_client_routes
  fi
}

bring_up_iface() {
  ip link del "${IFACE}" 2>/dev/null || true
  sleep 0.3
  echo "[cascade] starting amneziawg-go -f ${IFACE}"
  amneziawg-go -f "${IFACE}" &
  AWG_PID=$!
  local i
  for i in $(seq 1 40); do
    if [ -S "/var/run/amneziawg/${IFACE}.sock" ]; then
      break
    fi
    sleep 0.2
  done
  if ! awg setconf "${IFACE}" "${CONF}"; then
    echo "[cascade] awg setconf failed" >&2
    kill "${AWG_PID}" 2>/dev/null || true
    exit 1
  fi
  ip link set "${IFACE}" up
  ip link set dev "${IFACE}" mtu 1280 2>/dev/null || true
  local addr
  addr="$(tr -d '[:space:]' <"${CONF}.address" 2>/dev/null || true)"
  if [ -n "${addr}" ]; then
    ip addr replace "${addr}" dev "${IFACE}"
  fi
  echo "[cascade] ready addr=${addr:-?} peer=$(peer_public_key || true)"
}

sync_peer_if_changed() {
  local now prev
  now="$(peer_public_key)"
  prev="${LAST_PEER:-}"
  if [ "${now}" = "${prev}" ]; then
    return 0
  fi
  LAST_PEER="${now}"
  write_conf
  awg syncconf "${IFACE}" "${CONF}" 2>/dev/null || awg setconf "${IFACE}" "${CONF}" || true
  echo "[cascade] peer updated"
}

hop_handshake_ok() {
  local hs
  # `awg show <iface> latest-handshakes` → "<pubkey> <unix_ts>" (last field is seconds).
  hs="$(awg show "${IFACE}" latest-handshakes 2>/dev/null | awk '$NF ~ /^[0-9]+$/ {print $NF; exit}')"
  [ -n "${hs}" ] || return 1
  [ "${hs}" != "0" ] || return 1
  local now
  now="$(date +%s)"
  [ $((now - hs)) -le "${STALE_SEC}" ]
}

set_phone_ifaces() {
  local want="$1" iface
  for iface in awg0 wdttraw0; do
    ip link show "${iface}" >/dev/null 2>&1 || continue
    if [ "${want}" = "up" ]; then
      ip link set "${iface}" up 2>/dev/null || true
    else
      ip link set "${iface}" down 2>/dev/null || true
    fi
  done
}

write_status() {
  printf '%s\n' "$1" >"${STATUS_FILE}"
}

cleanup() {
  echo "[cascade] exit"
  if [ "${ROLE}" = "entry" ]; then
    local prio
    for prio in $(seq 320 335); do
      ip rule del pref "${prio}" 2>/dev/null || true
    done
    ip route flush table "${TABLE}" 2>/dev/null || true
    delete_commented nat
    delete_commented mangle
    delete_commented filter
  else
    local net
    for net in ${CLIENT_NETS}; do
      ip route del "${net}" dev "${IFACE}" 2>/dev/null || true
    done
    delete_commented filter
    delete_commented mangle
  fi
  ip link del "${IFACE}" 2>/dev/null || true
  write_status down
}

need_awg
ensure_keys
write_conf
trap cleanup EXIT INT TERM
bring_up_iface
setup_forwarding
LAST_PEER="$(peer_public_key)"
PHONE_DOWN=0
STARTED_AT="$(date +%s)"
write_status starting

while kill -0 "${AWG_PID}" 2>/dev/null; do
  sleep 5
  sync_peer_if_changed
  setup_forwarding
  if [ "${ROLE}" = "entry" ]; then
    local_ok=0
    if hop_handshake_ok; then
      local_ok=1
    fi
    now="$(date +%s)"
    # Grace after start so the phone can finish writing the exit peer.
    if [ "${local_ok}" -eq 0 ] && [ $((now - STARTED_AT)) -lt "${STALE_SEC}" ]; then
      write_status starting
      continue
    fi
    if [ "${local_ok}" -eq 1 ]; then
      write_status up
      if [ "${PHONE_DOWN}" -eq 1 ]; then
        echo "[cascade] hop handshake restored — raising phone tunnels"
        set_phone_ifaces up
        PHONE_DOWN=0
      fi
    else
      write_status down
      if [ "${PHONE_DOWN}" -eq 0 ]; then
        echo "[cascade] hop handshake stale — dropping phone tunnels"
        set_phone_ifaces down
        PHONE_DOWN=1
      fi
    fi
  else
    if hop_handshake_ok; then
      write_status up
    else
      write_status down
    fi
  fi
done

wait "${AWG_PID}" || true
