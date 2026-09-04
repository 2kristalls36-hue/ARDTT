#!/bin/bash
# WARP egress: wgcf → wireproxy (SOCKS5) → tun2socks TUN + policy routing.
#
# Same layout as the long-lived production host (wireproxy 1.1.2 + tun2socks
# 2.5.2): userspace WG stays ~110 MiB; Hide-IP is ip rule add/del + conntrack
# flush — no kernel wg-quick, no client TUN restart.
#
# hideip (standalone entry): per-user ip rules from local users.json.
# passthrough / cascade (cascade entry): no client /32 — traffic takes the hop.
# exit-hideip (exit VPS): same /32 rules, prefixes from the entry provision
#   (http://10.10.0.1:9100/v1/hide-ip-prefixes). Hide-IP then leaves via
#   Cloudflare on the exit, not the first VPS.
#
# DNS must NOT go through WARP:
#   priority 100: iif <ingress> udp/tcp dport 53 → main
#   priority 300+: from <client> → table 51820 (WARP TUN)
set -euo pipefail

DATA="${ARDTT_DATA:-/data}"
USERS="${DATA}/users.json"
STATE_DIR="${ARDTT_WARP_STATE:-/var/lib/ardtt-warp}"
IFACE="${ARDTT_WARP_IFACE:-warp0}"
TABLE="${ARDTT_WARP_TABLE:-51820}"
MARK_COMMENT="ARDTT_WARP_MANAGED"
DNS_COMMENT="ARDTT_WARP_DNS_MAIN"
# Prefer DNS (main) over WARP table — lower pref number = higher priority.
# Keep DNS well below any accidental unprioritized WARP rules (~163xx / auto).
DNS_RULE_PRIO="${ARDTT_WARP_DNS_PRIO:-100}"
WARP_RULE_PRIO_BASE="${ARDTT_WARP_RULE_PRIO:-300}"
WARP_MODE="${ARDTT_WARP_MODE:-hideip}"
SOCKS_ADDR="${ARDTT_WARP_SOCKS:-127.0.0.1:40000}"
TUN_ADDR="${ARDTT_WARP_TUN_ADDR:-10.99.99.1/24}"
# Recycle wireproxy in-process if RSS exceeds this (kB). Production sits ~110 MiB.
WIREPROXY_RSS_LIMIT_KB="${ARDTT_WARP_RSS_LIMIT_KB:-350000}"

# VPN ingress ifaces whose client DNS must stay on main.
DNS_IIFACES="${ARDTT_WARP_DNS_IIFACES:-awg0 wdttraw0}"

WIREPROXY_PID=0
TUN2SOCKS_PID=0

mkdir -p "${STATE_DIR}"
cd "${STATE_DIR}"

echo "[warp] Cloudflare WARP via wireproxy+tun2socks ${IFACE} table=${TABLE} mode=${WARP_MODE}"
echo "[warp] DNS via main (prio ${DNS_RULE_PRIO}); WARP from/client (prio ${WARP_RULE_PRIO_BASE}+)"
echo "[warp] GOMEMLIMIT=${GOMEMLIMIT:-256MiB}; socks=${SOCKS_ADDR}; no docker restart-on-OOM"

if [ -w /proc/sys/net/ipv4/ip_forward ]; then
  echo 1 >/proc/sys/net/ipv4/ip_forward || true
fi
# Strict rp_filter drops WARP replies whose reverse path is cascade0.
for rp in /proc/sys/net/ipv4/conf/*/rp_filter; do
  [ -w "${rp}" ] && echo 2 >"${rp}" || true
done

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

# INI values after the first "=" — WireGuard keys end with "=" padding, so
# awk -F'= *' was chopping the last character and wireproxy exited with
# "invalid base64 string".
wg_conf_field() {
  local key="$1" file="$2"
  awk -v k="$key" '
    $0 ~ "^" k "[[:space:]]*=" {
      sub("^" k "[[:space:]]*=[[:space:]]*", "")
      sub(/[[:space:]]+$/, "")
      print
      exit
    }
  ' "$file"
}

# Build a WireGuard conf that does NOT steal the host default route.
# Convert wgcf-profile.conf → wireproxy SOCKS config (IPv4 only).
build_conf() {
  local src=wgcf-profile.conf
  local out=proxy.conf
  [[ -f "${src}" ]] || { echo "[warp] missing ${src}" >&2; exit 1; }
  local priv pub endpoint addr
  priv="$(wg_conf_field PrivateKey "${src}")"
  pub="$(wg_conf_field PublicKey "${src}")"
  endpoint="$(wg_conf_field Endpoint "${src}")"
  addr="$(wg_conf_field Address "${src}" | cut -d, -f1 | tr -d ' ')"
  addr="${addr%%/*}/32"
  [[ -n "${priv}" && -n "${pub}" && -n "${endpoint}" && -n "${addr}" ]] || {
    echo "[warp] failed to parse ${src}" >&2
    exit 1
  }
  cat >"${out}.tmp" <<EOF
[Interface]
PrivateKey = ${priv}
Address = ${addr}
MTU = 1420

[Peer]
PublicKey = ${pub}
Endpoint = ${endpoint}

[Socks5]
BindAddress = ${SOCKS_ADDR}
EOF
  mv "${out}.tmp" "${out}"
  echo "[warp] wrote ${out} socks=${SOCKS_ADDR}"
}

pid_alive() {
  local pid="${1:-0}"
  [[ "${pid}" -gt 0 ]] && kill -0 "${pid}" 2>/dev/null
}

rss_kb() {
  local pid="${1:-0}"
  [[ "${pid}" -gt 0 && -r "/proc/${pid}/status" ]] || { echo 0; return; }
  awk '/^VmRSS:/{print $2; exit}' "/proc/${pid}/status" 2>/dev/null || echo 0
}

stop_pid() {
  local pid="${1:-0}"
  pid_alive "${pid}" || return 0
  kill "${pid}" 2>/dev/null || true
  local i
  for i in 1 2 3 4 5; do
    pid_alive "${pid}" || return 0
    sleep 0.2
  done
  kill -9 "${pid}" 2>/dev/null || true
}

wait_socks() {
  local host="${SOCKS_ADDR%:*}"
  local port="${SOCKS_ADDR##*:}"
  local i
  for i in $(seq 1 50); do
    if (echo >"/dev/tcp/${host}/${port}") 2>/dev/null; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

start_wireproxy() {
  stop_pid "${WIREPROXY_PID}"
  WIREPROXY_PID=0
  GOMEMLIMIT="${GOMEMLIMIT:-256MiB}" /usr/local/bin/wireproxy -c "${STATE_DIR}/proxy.conf" &
  WIREPROXY_PID=$!
  if wait_socks; then
    echo "[warp] wireproxy pid=${WIREPROXY_PID} socks=${SOCKS_ADDR}"
    return 0
  fi
  if ! pid_alive "${WIREPROXY_PID}"; then
    echo "[warp] wireproxy exited" >&2
    return 1
  fi
  echo "[warp] WARN: wireproxy socks not ready yet pid=${WIREPROXY_PID}" >&2
}

start_tun2socks() {
  stop_pid "${TUN2SOCKS_PID}"
  TUN2SOCKS_PID=0
  /usr/local/bin/tun2socks -device "${IFACE}" -proxy "socks5://${SOCKS_ADDR}" &
  TUN2SOCKS_PID=$!
  echo "[warp] tun2socks pid=${TUN2SOCKS_PID} dev=${IFACE}"
}

# Drop leftover kernel-WG warp0 from older stacks (junk iface + wg-quick conf).
clear_legacy_kernel_warp() {
  wg-quick down "${IFACE}" 2>/dev/null || true
  wg-quick down "./${IFACE}.conf" 2>/dev/null || true
  ip link del "${IFACE}" 2>/dev/null || true
  rm -f "/etc/wireguard/${IFACE}.conf" "${STATE_DIR}/${IFACE}.conf" "${STATE_DIR}/${IFACE}.conf.tmp"
}

bring_up() {
  stop_pid "${TUN2SOCKS_PID}"; TUN2SOCKS_PID=0
  stop_pid "${WIREPROXY_PID}"; WIREPROXY_PID=0
  clear_legacy_kernel_warp
  ip tuntap add mode tun dev "${IFACE}" 2>/dev/null || true
  ip addr replace "${TUN_ADDR}" dev "${IFACE}"
  ip link set dev "${IFACE}" up
  ip link set dev "${IFACE}" mtu 1420 2>/dev/null || true

  # rp_filter drops WARP replies whose reverse path is the TUN, not eth0.
  if [ -w /proc/sys/net/ipv4/conf/all/rp_filter ]; then
    echo 0 >/proc/sys/net/ipv4/conf/all/rp_filter || true
  fi
  if [ -w "/proc/sys/net/ipv4/conf/${IFACE}/rp_filter" ]; then
    echo 0 >"/proc/sys/net/ipv4/conf/${IFACE}/rp_filter" || true
  fi

  start_wireproxy || true
  start_tun2socks

  # Policy table: default via WARP TUN only (main table untouched).
  ip route replace default dev "${IFACE}" table "${TABLE}" || \
    ip route add default dev "${IFACE}" table "${TABLE}"

  # No MASQUERADE onto the TUN — tun2socks SNATs through wireproxy.
  # Drop leftover MASQ from kernel-WG stacks so Hide-IP-off is not double-NAT.
  iptables -t nat -D POSTROUTING -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j MASQUERADE 2>/dev/null || true

  iptables -C FORWARD -i "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -i "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT || true
  iptables -C FORWARD -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT || true

  # TUN MTU 1420; AWG ingress is often ~1420. Clamp so Hide-IP HTTPS does not blackhole.
  iptables -t mangle -C FORWARD -o "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${MARK_COMMENT}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null \
    || iptables -t mangle -A FORWARD -o "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
      -m comment --comment "${MARK_COMMENT}" -j TCPMSS --clamp-mss-to-pmtu || true
  iptables -t mangle -C FORWARD -i "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${MARK_COMMENT}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null \
    || iptables -t mangle -A FORWARD -i "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
      -m comment --comment "${MARK_COMMENT}" -j TCPMSS --clamp-mss-to-pmtu || true

  ensure_dns_masquerade

  echo "[warp] ${IFACE} tun2socks up; default in table ${TABLE}; TCPMSS clamp on FORWARD"
}

# Explicit DNS MASQUERADE on WAN for VPN client subnets (upstream 1.1.1.1 via main).
# Full-subnet MASQ usually already exists; these are idempotent belt-and-suspenders.
ensure_dns_masquerade() {
  local wan
  wan="$(ip route show default 0.0.0.0/0 2>/dev/null | awk '{print $5; exit}')"
  [[ -z "${wan}" ]] && wan="eth0"
  local nets=("10.8.0.0/24" "10.9.0.0/24" "10.10.0.0/30")
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
  local fr pref from
  # Prefer deleting by pref; also try from+lookup. Never abort the loop on del failure
  # (set -o pipefail + empty grep must not kill sync_rules).
  while true; do
    fr="$(ip rule show 2>/dev/null | grep "lookup ${table}" | head -1 || true)"
    [[ -z "${fr}" ]] && break
    pref="$(echo "${fr}" | cut -d: -f1 | tr -d '[:space:]')"
    from="$(echo "${fr}" | sed -n 's/.*from \([^ ]*\).*/\1/p')"
    if [[ -n "${pref}" ]]; then
      ip rule del pref "${pref}" 2>/dev/null || true
    fi
    if [[ -n "${from}" ]]; then
      ip rule del from "${from}" lookup "${table}" 2>/dev/null || true
      ip rule del from "${from}" table "${table}" 2>/dev/null || true
    fi
    # If the same line is still present, force-break to avoid infinite loop.
    if ip rule show 2>/dev/null | grep -F "${fr}" | grep -q .; then
      echo "[warp] WARN: could not delete rule: ${fr}" >&2
      break
    fi
    guard=$((guard + 1))
    [[ "${guard}" -gt 128 ]] && break
  done
  # Belt-and-suspenders: drop our usual hideIp priority band.
  local p
  for p in $(seq "${WARP_RULE_PRIO_BASE}" $((WARP_RULE_PRIO_BASE + 64))); do
    ip rule del pref "${p}" 2>/dev/null || true
  done
}

# Remove our DNS→main exceptions (match by dport 53 → main and known pref band).
clear_dns_main_rules() {
  local guard=0
  local fr pref
  while true; do
    fr="$(ip rule show 2>/dev/null | grep -E "dport 53.*lookup main|lookup main.*dport 53" | head -1 || true)"
    [[ -z "${fr}" ]] && break
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

# True if a hideIp "from ADDR lookup TABLE" rule already exists.
has_hideip_from() {
  local from="$1"
  local bare="${from%/32}"
  ip rule show 2>/dev/null | grep -E "from ${bare}(/32)? .*lookup ${TABLE}|from ${bare}(/32)? lookup ${TABLE}" | grep -q .
}

# Keep VPN/LAN and VPS-public destinations on main even when hideIp from-rule is on.
# Otherwise DNS/gateway and provision (:9100) get sucked into warp0 → stalls on toggle.
# Do NOT exempt 172.16.0.0/12 — that is WARP's own CGNAT (warp0 = 172.16.0.2/32).
LOCAL_EXEMPT_PRIO="${ARDTT_WARP_LOCAL_PRIO:-280}"

install_local_exempt_rules() {
  local p="${LOCAL_EXEMPT_PRIO}"
  local net
  # Drop legacy 172.16/12 exempt if an older image left it behind.
  ip rule del to 172.16.0.0/12 lookup main 2>/dev/null || true
  # Clear our exempt priority band so pref numbers stay stable across edits.
  local clear_p
  for clear_p in $(seq "${LOCAL_EXEMPT_PRIO}" $((LOCAL_EXEMPT_PRIO + 16))); do
    ip rule del pref "${clear_p}" 2>/dev/null || true
  done
  for net in 10.8.0.0/24 10.9.0.0/24 10.10.0.0/30 10.99.99.0/24 127.0.0.0/8; do
    if ip rule add to "${net}" lookup main priority "${p}" 2>/dev/null; then
      echo "[warp] local exempt to ${net} → main prio=${p}"
    fi
    p=$((p + 1))
  done
  local pub=""
  if [[ -f "${USERS}" ]]; then
    pub="$(jq -r '.config.publicHost // empty' "${USERS}" 2>/dev/null || true)"
  fi
  if [[ -n "${pub}" ]]; then
    if ip rule add to "${pub}" lookup main priority "${p}" 2>/dev/null; then
      echo "[warp] local exempt to ${pub} (publicHost) → main prio=${p}"
    fi
  fi
}

# Drop conntrack for a client so old TCP sessions die fast after egress flip
# (otherwise phone apps hang until idle timeout). Quiet stdout — `-D` prints
# every deleted tuple and can stall the sync loop under load.
flush_client_conntrack() {
  local bare="$1"
  bare="${bare%/32}"
  [[ -z "${bare}" ]] && return 0
  if command -v conntrack >/dev/null 2>&1; then
    conntrack -D -s "${bare}" >/dev/null 2>&1 || true
    conntrack -D -d "${bare}" >/dev/null 2>&1 || true
    conntrack -D --reply-src "${bare}" >/dev/null 2>&1 || true
    conntrack -D --reply-dst "${bare}" >/dev/null 2>&1 || true
    # Catch races: packets in flight recreate entries during the first pass.
    sleep 0.05
    conntrack -D -s "${bare}" >/dev/null 2>&1 || true
    conntrack -D -d "${bare}" >/dev/null 2>&1 || true
    echo "[warp] conntrack flushed for ${bare}"
  fi
}

# Drop leftover /24 WARP from-rules (old blanket-WARP exit images).
clear_cascade_from_rules() {
  local net removed=0
  for net in 10.8.0.0/24 10.9.0.0/24 10.10.0.0/30; do
    while ip rule del from "${net}" lookup "${TABLE}" 2>/dev/null; do
      echo "[warp] cascade WAN — removed from ${net} lookup ${TABLE}"
      removed=1
    done
  done
  if [[ "${removed}" -eq 1 ]] && command -v conntrack >/dev/null 2>&1; then
    conntrack -D -s 10.8.0.0/24 >/dev/null 2>&1 || true
    conntrack -D -s 10.9.0.0/24 >/dev/null 2>&1 || true
    conntrack -D -s 10.10.0.0/30 >/dev/null 2>&1 || true
    echo "[warp] cascade WAN — flushed client conntrack"
  fi
}

warp_passthrough_mode() {
  case "${WARP_MODE}" in
    cascade|passthrough) return 0 ;;
    *) return 1 ;;
  esac
}

# Prefixes currently hiding via WARP. Empty list is valid (all Hide-IP off).
# Return 2 if the peer/file cannot be read — caller must keep the last rules.
hideip_desired_prefixes() {
  if [ "${WARP_MODE}" = "exit-hideip" ]; then
    local url raw
    url="${ARDTT_WARP_HIDEIP_URL:-http://10.10.0.1:9100/v1/hide-ip-prefixes}"
    raw="$(curl -fsS --max-time 3 "${url}" 2>/dev/null)" || return 2
    echo "${raw}" | jq -e '.ok == true' >/dev/null 2>&1 || return 2
    echo "${raw}" | jq -r '.prefixes[]? // empty'
    return 0
  fi
  [[ -f "${USERS}" ]] || return 2
  jq -r '
    (.config.directSubnet // "10.8.0.0/24") as $d
    | (.config.bypassSubnet // "10.9.0.0/24") as $b
    | ($d | split(".") | .[0:3] | join(".")) as $db
    | ($b | split(".") | .[0:3] | join(".")) as $bb
    | .users[] | select(.hideIp == true and (.deactivated != true) and (.hostId > 0))
    | "\($db).\(.hostId)/32\n\($bb).\(.hostId)/32"
  ' "${USERS}" 2>/dev/null || true
  return 0
}

LAST_HIDEIP_DESIRED="__unset__"

# Diff-based hideIp sync: add/remove only changed client prefixes.
# Does NOT tear down DNS→main rules (that caused internet blips on rapid toggles).
sync_rules() {
  if warp_passthrough_mode; then
    # Drop /32 leftover from when this host was a standalone entry.
    clear_rules_for_table "${TABLE}"
    clear_cascade_from_rules
    LAST_HIDEIP_DESIRED="__unset__"
    return 0
  fi

  local desired="" fetch_st=0
  set +e
  desired="$(hideip_desired_prefixes)"
  fetch_st=$?
  set -e
  if [ "${fetch_st}" -eq 2 ]; then
    echo "[warp] WARN: hideIp prefix source unavailable — keeping current rules"
    return 0
  fi
  if [ "${desired}" = "${LAST_HIDEIP_DESIRED}" ]; then
    return 0
  fi
  LAST_HIDEIP_DESIRED="${desired}"

  local count=0

  # Remove WARP-table rules whose "from" is no longer desired.
  local fr pref from
  while IFS= read -r fr; do
    [[ -z "${fr}" ]] && continue
    from="$(echo "${fr}" | sed -n 's/.*from \([^ ]*\).*/\1/p')"
    pref="$(echo "${fr}" | cut -d: -f1 | tr -d '[:space:]')"
    [[ -z "${from}" ]] && continue
    local from_slash="${from}"
    [[ "${from}" != */* ]] && from_slash="${from}/32"
    if ! echo "${desired}" | grep -qxF "${from}" && ! echo "${desired}" | grep -qxF "${from_slash}" \
      && ! echo "${desired}" | grep -qxF "${from%/32}"; then
      if [[ -n "${pref}" ]]; then
        ip rule del pref "${pref}" 2>/dev/null || true
      fi
      ip rule del from "${from}" lookup "${TABLE}" 2>/dev/null || true
      echo "[warp] hideIp remove from=${from}"
      flush_client_conntrack "${from}"
    fi
  done <<< "$(ip rule show 2>/dev/null | grep "lookup ${TABLE}" || true)"

  # Add missing desired prefixes.
  local prio="${WARP_RULE_PRIO_BASE}"
  local addr
  while IFS= read -r addr; do
    [[ -z "${addr}" ]] && continue
    local bare="${addr%/32}"
    if has_hideip_from "${addr}" || has_hideip_from "${bare}"; then
      count=$((count + 1))
      prio=$((prio + 1))
      continue
    fi
    while ip rule show 2>/dev/null | grep -q "^${prio}:"; do
      prio=$((prio + 1))
      [[ "${prio}" -gt $((WARP_RULE_PRIO_BASE + 200)) ]] && break
    done
    add_hideip_rule "${addr}" "${prio}" || true
    echo "[warp] hideIp add ${addr} → table ${TABLE} prio=${prio}"
    flush_client_conntrack "${bare}"
    count=$((count + 1))
    prio=$((prio + 1))
  done <<< "${desired}"

  echo "[warp] synced hideIp prefixes≈${count}"
}

cleanup_on_exit() {
  echo "[warp] exit — stopping wireproxy/tun2socks and clearing policy rules"
  stop_pid "${TUN2SOCKS_PID}"; TUN2SOCKS_PID=0
  stop_pid "${WIREPROXY_PID}"; WIREPROXY_PID=0
  clear_rules_for_table "${TABLE}"
  clear_dns_main_rules
  iptables -t nat -D POSTROUTING -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j MASQUERADE 2>/dev/null || true
  iptables -D FORWARD -i "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null || true
  iptables -D FORWARD -o "${IFACE}" -m comment --comment "${MARK_COMMENT}" -j ACCEPT 2>/dev/null || true
  iptables -t mangle -D FORWARD -o "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${MARK_COMMENT}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
  iptables -t mangle -D FORWARD -i "${IFACE}" -p tcp --tcp-flags SYN,RST SYN \
    -m comment --comment "${MARK_COMMENT}" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
  ip link del "${IFACE}" 2>/dev/null || true
}

trap cleanup_on_exit EXIT INT TERM

ensure_account
build_conf
bring_up
install_dns_main_rules
install_local_exempt_rules
sync_rules

# Keep children alive; recycle wireproxy in-process if RSS grows (no docker restart).
LAST_MTIME=0
PENDING_SYNC=0
PENDING_SINCE=0
DNS_RETRY_TICK=0
# Coalesce rapid hideIp toggles: apply only after users.json stable ~1s.
SYNC_DEBOUNCE_SEC="${ARDTT_WARP_SYNC_DEBOUNCE:-1}"
while true; do
  sleep 1
  if ! pid_alive "${WIREPROXY_PID}"; then
    echo "[warp] wireproxy dead — restart"
    start_wireproxy || true
  else
    rss="$(rss_kb "${WIREPROXY_PID}")"
    if [[ "${rss}" -gt "${WIREPROXY_RSS_LIMIT_KB}" ]]; then
      echo "[warp] wireproxy RSS ${rss}kB > ${WIREPROXY_RSS_LIMIT_KB}kB — in-process recycle"
      start_wireproxy || true
    fi
  fi
  if ! pid_alive "${TUN2SOCKS_PID}"; then
    echo "[warp] tun2socks dead — restart"
    start_tun2socks
  fi
  if [ "${WARP_MODE}" = "exit-hideip" ]; then
    sync_rules
  elif [[ -f "${USERS}" ]]; then
    now="$(stat -c %Y "${USERS}" 2>/dev/null || echo 0)"
    if [[ "${now}" != "${LAST_MTIME}" ]]; then
      LAST_MTIME="${now}"
      PENDING_SYNC=1
      PENDING_SINCE="$(date +%s)"
      echo "[warp] users.json changed — debounce ${SYNC_DEBOUNCE_SEC}s before hideIp sync"
    fi
    if [[ "${PENDING_SYNC}" -eq 1 ]]; then
      now_s="$(date +%s)"
      if [[ $((now_s - PENDING_SINCE)) -ge "${SYNC_DEBOUNCE_SEC}" ]]; then
        PENDING_SYNC=0
        echo "[warp] debounce done — resync hideIp rules"
        # Exempts are stable; only repair if the 10.8 → main rule vanished.
        if ! ip rule show 2>/dev/null | grep -q "to 10.8.0.0/24 lookup main"; then
          install_local_exempt_rules
        fi
        sync_rules
      fi
    fi
  fi
  # Keep link up
  if ! ip link show "${IFACE}" >/dev/null 2>&1; then
    echo "[warp] ${IFACE} missing — bringing up again"
    bring_up
    install_dns_main_rules
    install_local_exempt_rules
    sync_rules
  fi
  # Retry DNS iif rules when VPN ifaces appear late after warp start (~every 10s).
  # Match iproute2 wording: "iif awg0 ipproto udp dport 53 lookup main"
  DNS_RETRY_TICK=$((DNS_RETRY_TICK + 1))
  if [[ $((DNS_RETRY_TICK % 10)) -eq 0 ]]; then
    local_need=0
    for iface in ${DNS_IIFACES}; do
      if ip link show "${iface}" >/dev/null 2>&1; then
        udp_ok=0
        tcp_ok=0
        ip rule show 2>/dev/null | grep -E "iif ${iface}.*dport 53.*lookup main" | grep -q "udp" && udp_ok=1
        ip rule show 2>/dev/null | grep -E "iif ${iface}.*dport 53.*lookup main" | grep -q "tcp" && tcp_ok=1
        # iproute2 prints "ipproto udp" — accept either token.
        if [[ "${udp_ok}" -eq 0 ]] || [[ "${tcp_ok}" -eq 0 ]]; then
          # Fallback: any dport 53 rule for this iif counts as present pair if we have 2 lines.
          n="$(ip rule show 2>/dev/null | grep -c "iif ${iface}.*dport 53.*lookup main" || true)"
          if [[ "${n}" -lt 2 ]]; then
            local_need=1
          fi
        fi
      fi
    done
    if [[ "${local_need}" -eq 1 ]]; then
      echo "[warp] DNS iif rules incomplete — reinstall"
      install_dns_main_rules
    fi
  fi
done
