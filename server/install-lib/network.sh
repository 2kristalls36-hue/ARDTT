# shellcheck shell=bash
# Pick a Docker bridge subnet that does not overlap host routes, Docker
# networks, or well-known VPN ranges. Never apply a universal default blindly.

KNOWN_VPN_SUBNETS="10.8.0.0/24 10.9.0.0/24 10.10.0.0/30 10.99.99.0/24"

cidr_to_range() {
  python3 - "$1" <<'PY'
import ipaddress,sys
n=ipaddress.ip_network(sys.argv[1].strip(), strict=False)
print(int(n.network_address), int(n.broadcast_address))
PY
}

cidrs_overlap() {
  local a="$1" b="$2"
  python3 - "$a" "$b" <<'PY'
import ipaddress,sys
a=ipaddress.ip_network(sys.argv[1].strip(), strict=False)
b=ipaddress.ip_network(sys.argv[2].strip(), strict=False)
raise SystemExit(0 if a.overlaps(b) else 1)
PY
}

list_host_cidrs() {
  ip -o -4 route show 2>/dev/null | awk '{print $1}' | grep -E '^[0-9]' | grep -vE '^default$' || true
  ip -o -4 addr show 2>/dev/null | awk '{print $4}' || true
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    docker network ls -q 2>/dev/null | while read -r id; do
      [ -n "$id" ] || continue
      docker network inspect -f '{{range .IPAM.Config}}{{printf "%s\n" .Subnet}}{{end}}' "$id" 2>/dev/null || true
    done
  fi
}

# Subnet of this instance's own bridge network (proven by labels). On a
# redeploy it still exists while the subnet is chosen: its route, bridge address
# and IPAM entry must not count as «busy», or every update moves ARDTT to a new
# subnet (172.28.20 → 172.30.0 → …).
own_bridge_subnet() {
  [ -n "${ARDTT_NETWORK_NAME:-}" ] || return 0
  command -v docker >/dev/null 2>&1 || return 0
  docker network inspect "$ARDTT_NETWORK_NAME" >/dev/null 2>&1 || return 0
  object_owned_by_us network "$ARDTT_NETWORK_NAME" || return 0
  docker network inspect -f '{{range .IPAM.Config}}{{printf "%s\n" .Subnet}}{{end}}' \
    "$ARDTT_NETWORK_NAME" 2>/dev/null | head -1
}

same_network() {
  python3 - "$1" "$2" <<'PY'
import ipaddress,sys
a=ipaddress.ip_network(sys.argv[1].strip(), strict=False)
b=ipaddress.ip_network(sys.argv[2].strip(), strict=False)
raise SystemExit(0 if a == b else 1)
PY
}

cidr_conflicts() {
  local want="$1" other own
  for other in $KNOWN_VPN_SUBNETS; do
    cidrs_overlap "$want" "$other" && return 0
  done
  own="${OWN_BRIDGE_SUBNET-$(own_bridge_subnet)}"
  while IFS= read -r other; do
    [ -n "$other" ] || continue
    echo "$other" | grep -q '/' || continue
    if [ -n "$own" ] && same_network "$other" "$own"; then continue; fi
    cidrs_overlap "$want" "$other" && return 0
  done <<< "$(list_host_cidrs)"
  return 1
}

pick_from_family() {
  local prefix="$1" start="$2" end="$3" oct net
  # Skip a whole /16 when a host route already covers it (many clouds
  # advertise 172.16.0.0/12, which includes 172.28/172.30).
  cidr_conflicts "${prefix}.0.0/16" && return 1
  for oct in $(seq "$start" "$end"); do
    net="${prefix}.${oct}.0/24"
    if ! cidr_conflicts "$net"; then
      printf '%s' "$net"
      return 0
    fi
  done
  return 1
}

pick_bridge_subnet() {
  local preferred="${1:-}"
  # Resolved once: cidr_conflicts runs for every candidate.
  local OWN_BRIDGE_SUBNET
  OWN_BRIDGE_SUBNET="$(own_bridge_subnet)"
  if [ -n "$preferred" ] && ! cidr_conflicts "$preferred"; then
    printf '%s' "$preferred"
    return 0
  fi
  pick_from_family 172.28 20 250 && return 0
  pick_from_family 172.30 0 250 && return 0
  pick_from_family 10.112 0 250 && return 0
  pick_from_family 10.210 0 250 && return 0
  die "Не удалось подобрать свободную Docker bridge-подсеть (пересечение с маршрутами/сетями хоста)"
}
