#!/bin/bash
# WARP egress: wgcf → warp0 (Table=off) + per-user ip rules from users.json hideIp.
#
# DNS must NOT go through WARP (breaks resolvers / looks like tun2socks DNS loops):
#   priority 100: iif awg0|wdttraw0|wdtt0 udp/tcp dport 53 → main
#   priority 300+: from <client>/32 → table 51820 (WARP)
# Existing MASQUERADE on VPN subnets → eth0 covers DNS upstream on main.
set -euo pipefail

DATA="${NVPN_DATA:-/data}"
USERS="${DATA}/users.json"
STATE_DIR="${NVPN_WARP_STATE:-/var/lib/nvpn-warp}"
IFACE="${NVPN_WARP_IFACE:-warp0}"
TABLE="${NVPN_WARP_TABLE:-51820}"
MARK_COMMENT="NVPN_WARP_MANAGED"
DNS_COMMENT="NVPN_WARP_DNS_MAIN"
# Prefer DNS (main) over WARP table — lower pref number = higher priority.
# Keep DNS well below any accidental unprioritized WARP rules (~163xx / auto).
DNS_RULE_PRIO="${NVPN_WARP_DNS_PRIO:-100}"
WARP_RULE_PRIO_BASE="${NVPN_WARP_RULE_PRIO:-300}"

# VPN ingress ifaces whose client DNS must stay on main.
DNS_IIFACES="${NVPN_WARP_DNS_IIFACES:-awg0 wdttraw0 wdtt0}"

mkdir -p "${STATE_DIR}"
cd "${STATE_DIR}"

echo "[warp] Cloudflare WARP egress via ${IFACE} table=${TABLE}"
echo "[warp] DNS via main (prio ${DNS_RULE_PRIO}); WARP from/client (prio ${WARP_RULE_PRIO_BASE}+)"
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

  ensure_dns_masquerade

  echo "[warp] ${IFACE} up; default in table ${TABLE}"
}

# Explicit DNS MASQUERADE on WAN for VPN client subnets (upstream 1.1.1.1 via main).
# Full-subnet MASQ usually already exists; these are idempotent belt-and-suspenders.
ensure_dns_masquerade() {
  local wan
  wan="$(ip route show default 0.0.0.0/0 2>/dev/null | awk '{print $5; exit}')"
  [[ -z "${wan}" ]] && wan="eth0"
  local nets=("10.8.0.0/24" "10.9.0.0/24" "10.66.0.0/16")
  local net proto
  for net in "${nets[@]}"; do
    for proto in udp tcp; do
      iptables -t nat -C POSTROUTING -s "${net}" -o "${wan}" -p "${proto}" --dport 53 \
        -m comment --comment "${DNS_COMMENT}" -j MASQUERADE 2>/dev/null \
        || iptables -t nat -I POSTROUTING 1 -s "${net}" -o "${wan}" -p "${proto}" --dport 53 \
          -m comment --comment "${DNS_COMMENT}" -j MASQUERADE || true
    done
  done
  echo "[warp] DNS MASQUERADE on ${wan} for VPN subnets (udp/tcp :53)"
}

# Remove every ip rule that lookups our WARP table (any priority / duplicate).
clear_rules_for_table() {
  local table="$1"
  local guard=0
  while ip rule show | grep -q "lookup ${table}"; do
    local fr pref
    fr="$(ip rule show | grep "lookup ${table}" | head -1)"
    pref="$(echo "${fr}" | cut -d: -f1 | tr -d '[:space:]')"
    if [[ -n "${pref}" ]]; then
      ip rule del pref "${pref}" 2>/dev/null || {
        # Fallback when pref parse fails across iproute2 versions.
        local from
        from="$(echo "${fr}" | sed -n 's/.*from \([^ ]*\).*/\1/p')"
        [[ -n "${from}" ]] && ip rule del from "${from}" lookup "${table}" 2>/dev/null || true
      }
    else
      break
    fi
    guard=$((guard + 1))
    [[ "${guard}" -gt 128 ]] && break
  done
}

# Remove our DNS→main exceptions (match by dport 53 → main and known pref band).
clear_dns_main_rules() {
  local guard=0
  while ip rule show | grep -E "dport 53.*lookup main|lookup main.*dport 53" | grep -q .; do
    local fr pref
    fr="$(ip rule show | grep -E "dport 53.*lookup main|lookup main.*dport 53" | head -1)"
    pref="$(echo "${fr}" | cut -d: -f1 | tr -d '[:space:]')"
    [[ -n "${pref}" ]] && ip rule del pref "${pref}" 2>/dev/null || true
    guard=$((guard + 1))
    [[ "${guard}" -gt 64 ]] && break
  done
  # Also drop known prefs in case grep wording differs across iproute2 versions.
  local p
  for p in $(seq "${DNS_RULE_PRIO}" $((DNS_RULE_PRIO + 20))); do
    ip rule del pref "${p}" 2>/dev/null || true
  done
  # Legacy band from older images (prio 200+).
  if [[ "${DNS_RULE_PRIO}" -ne 200 ]]; then
    for p in $(seq 200 220); do
      ip rule del pref "${p}" 2>/dev/null || true
    done
  fi
}

install_dns_main_rules() {
  clear_dns_main_rules
  local prio="${DNS_RULE_PRIO}"
  local iface proto added=0
  for iface in ${DNS_IIFACES}; do
    if ! ip link show "${iface}" >/dev/null 2>&1; then
      echo "[warp] skip DNS iif=${iface} (iface down)"
      continue
    fi
    for proto in udp tcp; do
      # Never fall back to an unprioritized rule — those land below WARP prefs and break DNS.
      if ip rule add iif "${iface}" ipproto "${proto}" dport 53 lookup main priority "${prio}" 2>/dev/null; then
        echo "[warp] DNS exception iif=${iface} ${proto}/53 → main prio=${prio}"
        added=$((added + 1))
      else
        echo "[warp] WARN: failed DNS rule iif=${iface} ${proto}/53 prio=${prio}" >&2
      fi
      prio=$((prio + 1))
    done
  done
  echo "[warp] DNS main rules installed=${added}"
}

add_hideip_rule() {
  local from="$1"
  local prio="$2"
  if ! ip rule add from "${from}" lookup "${TABLE}" priority "${prio}" 2>/dev/null; then
    # Rule may already exist at this pref; replace by deleting pref then retry once.
    ip rule del pref "${prio}" 2>/dev/null || true
    if ! ip rule add from "${from}" lookup "${TABLE}" priority "${prio}" 2>/dev/null; then
      echo "[warp] WARN: failed hideIp rule from=${from} prio=${prio}" >&2
      return 1
    fi
  fi
  return 0
}

# Apply ip rules for every hideIp=true user (direct + bypass tunnel IPs).
sync_rules() {
  [[ -f "${USERS}" ]] || return 0

  clear_rules_for_table "${TABLE}"
  install_dns_main_rules
  ensure_dns_masquerade

  local desired count=0 prio="${WARP_RULE_PRIO_BASE}"
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
    # WARP for everything except DNS (DNS already matched at lower prio via iif).
    add_hideip_rule "${dip}" "${prio}" || true
    prio=$((prio + 1))
    add_hideip_rule "${bip}" "${prio}" || true
    prio=$((prio + 1))
    count=$((count + 1))
    echo "[warp] hideIp route ${dip} + ${bip} → table ${TABLE} (prio≥${WARP_RULE_PRIO_BASE})"
  done <<< "${desired}"

  echo "[warp] synced hideIp users=${count}"
  ip rule show | head -40 || true
}

cleanup_on_exit() {
  echo "[warp] exit — clearing hideIp + DNS policy rules"
  clear_rules_for_table "${TABLE}"
  clear_dns_main_rules
  iptables -t nat -D POSTROUTING -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j MASQUERADE 2>/dev/null || true
  iptables -D FORWARD -i "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null || true
  iptables -D FORWARD -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null || true
}

trap cleanup_on_exit EXIT INT TERM

ensure_account
build_conf
bring_up
sync_rules

# Soft recycle watcher: if RSS grows huge, restart tunnel in-process (no docker restart).
LAST_MTIME=0
DNS_RETRY_TICK=0
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
  # Retry DNS iif rules when VPN ifaces appear late after warp start.
  DNS_RETRY_TICK=$((DNS_RETRY_TICK + 1))
  if [[ $((DNS_RETRY_TICK % 6)) -eq 0 ]]; then
    local_need=0
    for iface in ${DNS_IIFACES}; do
      if ip link show "${iface}" >/dev/null 2>&1; then
        if ! ip rule show | grep -q "iif ${iface}.*dport 53.*lookup main"; then
          local_need=1
        fi
      fi
    done
    if [[ "${local_need}" -eq 1 ]]; then
      echo "[warp] DNS iif rules incomplete — reinstall"
      install_dns_main_rules
    fi
  fi
done
