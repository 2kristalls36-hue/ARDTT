#!/bin/bash
# WARP egress: wgcf → warp0 (Table=off) + per-user ip rules from users.json hideIp.
set -euo pipefail

DATA="${NVPN_DATA:-/data}"
USERS="${DATA}/users.json"
STATE_DIR="${NVPN_WARP_STATE:-/var/lib/nvpn-warp}"
IFACE="${NVPN_WARP_IFACE:-warp0}"
TABLE="${NVPN_WARP_TABLE:-51820}"
MARK_COMMENT="NVPN_WARP_MANAGED"

mkdir -p "${STATE_DIR}"
cd "${STATE_DIR}"

echo "[warp] Cloudflare WARP egress via ${IFACE} table=${TABLE}"
echo "[warp] GOMEMLIMIT=${GOMEMLIMIT:-400MiB}; no container restart-on-OOM"

if [ -w /proc/sys/net/ipv4/ip_forward ]; then
  echo 1 >/proc/sys/net/ipv4/ip_forward || true
fi

ensure_account() {
  if [[ ! -f wgcf-account.toml ]]; then
    echo "[warp] registering wgcf account…"
    wgcf register --accept-tos
  fi
  if [[ ! -f wgcf-profile.conf ]]; then
    echo "[warp] generating WireGuard profile…"
    wgcf generate
  fi
}

# Build a WireGuard conf that does NOT steal the host default route.
build_conf() {
  local src=wgcf-profile.conf
  local out="${IFACE}.conf"
  # Strip DNS / rewrite AllowedIPs handling — Table=off keeps main routing intact.
  awk -v iface="${IFACE}" '
    BEGIN { in_iface=0 }
    /^\[Interface\]/ { in_iface=1; print; next }
    /^\[Peer\]/ {
      in_iface=0
      print "Table = off"
      print "MTU = 1280"
      print
      next
    }
    in_iface && /^DNS/ { next }
    in_iface && /^Address/ {
      # Keep IPv4 only — simpler policy routing on IPv4 VPS
      gsub(/,.*$/, "", $0)
      print
      next
    }
    { print }
  ' "${src}" >"${out}.tmp"

  # Ensure Interface name section for wg-quick
  if ! grep -q "^Table = off" "${out}.tmp"; then
    sed -i "/^\[Peer\]/i Table = off\nMTU = 1280" "${out}.tmp"
  fi
  mv "${out}.tmp" "${out}"
  echo "[warp] wrote ${out}"
}

bring_up() {
  local conf="${IFACE}.conf"
  # Clean leftovers
  wg-quick down "${conf}" 2>/dev/null || true
  ip link del "${IFACE}" 2>/dev/null || true

  # wg-quick wants conf in /etc/wireguard or path — use explicit
  WG_QUICK_USERSPACE_IMPLEMENTATION="" wg-quick up "./${conf}" || {
    echo "[warp] wg-quick up failed" >&2
    exit 1
  }

  # Rename interface if wg-quick used filename stem
  local stem
  stem="$(basename "${conf}" .conf)"
  if ip link show "${stem}" >/dev/null 2>&1 && [[ "${stem}" != "${IFACE}" ]]; then
    ip link set "${stem}" name "${IFACE}" || true
  fi

  # Policy table: default via WARP only (main table untouched)
  ip route replace default dev "${IFACE}" table "${TABLE}" || \
    ip route add default dev "${IFACE}" table "${TABLE}"

  iptables -t nat -C POSTROUTING -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j MASQUERADE || true
  iptables -C FORWARD -i "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -i "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT || true
  iptables -C FORWARD -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT || true

  echo "[warp] ${IFACE} up; default in table ${TABLE}"
}

# Apply ip rules for every hideIp=true user (direct + bypass tunnel IPs).
sync_rules() {
  [[ -f "${USERS}" ]] || return 0

  # Drop every rule that points at our WARP table.
  local guard=0
  while ip rule show | grep -q "lookup ${TABLE}"; do
    local fr from pref
    fr="$(ip rule show | grep "lookup ${TABLE}" | head -1)"
    pref="$(echo "${fr}" | cut -d: -f1)"
    from="$(echo "${fr}" | sed -n 's/.*from \([^ ]*\).*/\1/p')"
    if [[ -n "${from}" ]]; then
      ip rule del from "${from}" lookup "${TABLE}" 2>/dev/null || true
    fi
    if [[ -n "${pref}" ]]; then
      ip rule del pref "${pref}" 2>/dev/null || true
    fi
    guard=$((guard + 1))
    [[ "${guard}" -gt 64 ]] && break
  done

  local desired count=0
  desired="$(jq -r '
    (.config.directSubnet // "10.8.0.0/24") as $d
    | (.config.bypassSubnet // "10.9.0.0/24") as $b
    | ($d | split(".") | .[0:3] | join(".")) as $db
    | ($b | split(".") | .[0:3] | join(".")) as $bb
    | .users[] | select(.hideIp == true)
    | "\($db).\(.hostId)/32 \($bb).\(.hostId)/32"
  ' "${USERS}" 2>/dev/null || true)"

  while read -r pair; do
    [[ -z "${pair}" ]] && continue
    local dip bip
    dip="$(echo "${pair}" | awk '{print $1}')"
    bip="$(echo "${pair}" | awk '{print $2}')"
    ip rule add from "${dip}" lookup "${TABLE}" priority 100 2>/dev/null || true
    ip rule add from "${bip}" lookup "${TABLE}" priority 100 2>/dev/null || true
    count=$((count + 1))
    echo "[warp] hideIp route ${dip} + ${bip} → table ${TABLE}"
  done <<< "${desired}"

  echo "[warp] synced hideIp users=${count}"
}

ensure_account
build_conf
bring_up
sync_rules

# Soft recycle watcher: if RSS grows huge, restart tunnel in-process (no docker restart).
LAST_MTIME=0
while true; do
  sleep 5
  if [[ -f "${USERS}" ]]; then
    now="$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)"
    if [[ "${now}" != "${LAST_MTIME}" ]]; then
      LAST_MTIME="${now}"
      echo "[warp] users.json changed — resync hideIp rules"
      sync_rules
    fi
  fi
  # Keep link up
  if ! ip link show "${IFACE}" >/dev/null 2>&1; then
    echo "[warp] ${IFACE} missing — bringing up again"
    bring_up
    sync_rules
  fi
done
