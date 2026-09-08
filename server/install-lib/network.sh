# shellcheck shell=bash
# Pick a Docker bridge subnet that does not overlap host routes, Docker
# networks, or well-known VPN ranges. Never apply a universal default blindly.

KNOWN_VPN_SUBNETS="10.8.0.0/24 10.9.0.0/24 10.10.0.0/30 10.99.99.0/24"

cidr_to_range() {
  python3 - "$1" <<'PY'
import ipaddress,sys
n=ipaddress.ip_network(sys.argv[1], strict=False)
print(int(n.network_address), int(n.broadcast_address))
PY
}

cidrs_overlap() {
  local a="$1" b="$2"
  python3 - "$a" "$b" <<'PY'
import ipaddress,sys
a=ipaddress.ip_network(sys.argv[1], strict=False)
b=ipaddress.ip_network(sys.argv[2], strict=False)
raise SystemExit(0 if a.overlaps(b) else 1)
PY
}

list_host_cidrs() {
  ip -o -4 route show 2>/dev/null | awk '{print $1}' | grep -E '^[0-9]' | grep -vE '^default$' || true
  ip -o -4 addr show 2>/dev/null | awk '{print $4}' || true
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    docker network ls -q 2>/dev/null | while read -r id; do
      [ -n "$id" ] || continue
      docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}' "$id" 2>/dev/null || true
    done
  fi
}

cidr_conflicts() {
  local want="$1" other
  for other in $KNOWN_VPN_SUBNETS; do
    cidrs_overlap "$want" "$other" && return 0
  done
  while IFS= read -r other; do
    [ -n "$other" ] || continue
    echo "$other" | grep -q '/' || continue
    cidrs_overlap "$want" "$other" && return 0
  done <<< "$(list_host_cidrs)"
  return 1
}

pick_bridge_subnet() {
  local preferred="${1:-}"
  if [ -n "$preferred" ] && ! cidr_conflicts "$preferred"; then
    printf '%s' "$preferred"
    return 0
  fi
  local oct net
  for oct in $(seq 20 250); do
    net="172.28.${oct}.0/24"
    if ! cidr_conflicts "$net"; then
      printf '%s' "$net"
      return 0
    fi
  done
  for oct in $(seq 0 250); do
    net="172.30.${oct}.0/24"
    if ! cidr_conflicts "$net"; then
      printf '%s' "$net"
      return 0
    fi
  done
  die "Не удалось подобрать свободную Docker bridge-подсеть (пересечение с маршрутами/сетями хоста)"
}
