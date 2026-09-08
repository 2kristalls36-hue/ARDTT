# shellcheck shell=bash
# Host vs container listen ports. ss is not enough: Docker may publish without
# a visible docker-proxy listener.

who_owns_port() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lntup 2>/dev/null | grep -E "[.:]${port}[[:space:]]" | head -3 | tr '\n' ' ' | cut -c1-240
  fi
}

_ss_listen() {
  local proto="$1" port="$2" flag
  case "$proto" in
    udp) flag="-lun" ;;
    tcp) flag="-lnt" ;;
    *) return 1 ;;
  esac
  if command -v ss >/dev/null 2>&1; then
    ss $flag 2>/dev/null | awk -v p=":${port}" '
      $4 == p || $4 ~ (p "$") { found=1 }
      END { exit !found }
    '
  elif command -v netstat >/dev/null 2>&1; then
    if [ "$proto" = udp ]; then
      netstat -lun 2>/dev/null | grep -Eq "[.:]${port}[[:space:]]"
    else
      netstat -lnt 2>/dev/null | grep -Eq "[.:]${port}[[:space:]]"
    fi
  else
    return 1
  fi
}

udp_listen_port() { _ss_listen udp "$1"; }
tcp_listen_port() { _ss_listen tcp "$1"; }

# True when inspect JSON (one or two objects) publishes this host port/proto.
# Keys are container ports (e.g. 80/tcp); the host side is HostPort.
inspect_json_has_host_port() {
  local json="$1" port="$2" proto="$3"
  printf '%s' "$json" | python3 -c '
import json, sys
raw = sys.stdin.read()
want_port, want_proto = sys.argv[1], sys.argv[2]
dec = json.JSONDecoder()
idx = 0
objs = []
while idx < len(raw):
    while idx < len(raw) and raw[idx].isspace():
        idx += 1
    if idx >= len(raw):
        break
    obj, end = dec.raw_decode(raw, idx)
    objs.append(obj)
    idx = end
for obj in objs:
    if not isinstance(obj, dict):
        continue
    for spec, binds in obj.items():
        spec_proto = ""
        if isinstance(spec, str) and "/" in spec:
            spec_proto = spec.split("/")[-1]
        for b in binds or []:
            if not isinstance(b, dict):
                continue
            if str(b.get("HostPort") or "") != want_port:
                continue
            if not spec_proto or spec_proto == want_proto:
                raise SystemExit(0)
raise SystemExit(1)
' "$port" "$proto"
}

# Docker HostConfig.PortBindings / NetworkSettings.Ports — even when ss has no docker-proxy.
docker_published_port() {
  local proto="$1" port="$2"
  command -v docker >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1 || return 1
  local ids id bindings
  ids="$(docker ps -q 2>/dev/null || true)"
  [ -n "$ids" ] || return 1
  for id in $ids; do
    bindings="$(docker inspect -f '{{json .HostConfig.PortBindings}} {{json .NetworkSettings.Ports}}' "$id" 2>/dev/null || true)"
    [ -n "$bindings" ] || continue
    inspect_json_has_host_port "$bindings" "$port" "$proto" && return 0
  done
  return 1
}

port_busy() {
  local proto="$1" port="$2"
  _ss_listen "$proto" "$port" && return 0
  docker_published_port "$proto" "$port" && return 0
  return 1
}

port_in_use_by_us() {
  local want="$1"
  shift
  local p
  for p in "$@"; do
    [ -n "$p" ] || continue
    [ "$p" = "$want" ] && return 0
  done
  return 1
}

# Exempt ports this ARDTT instance already publishes (update must keep them).
OUR_HOST_PORTS="${OUR_HOST_PORTS:-}"

is_our_host_port() {
  local proto="$1" port="$2" item
  for item in $OUR_HOST_PORTS; do
    [ "$item" = "${proto}:${port}" ] && return 0
  done
  return 1
}

port_taken_foreign() {
  local proto="$1" port="$2"
  is_our_host_port "$proto" "$port" && return 1
  port_busy "$proto" "$port"
}

require_host_port() {
  local proto="$1" port="$2" what="$3"
  port_taken_foreign "$proto" "$port" || return 0
  die "Порт ${port}/${proto} занят (${what}) — другой сервис на этом VPS. Освободите порт или задайте другой ARDTT_*_PORT. Сейчас: $(who_owns_port "$port")"
}

find_free_port() {
  local proto="$1" start="$2"
  shift 2
  local port="$start" tries=0
  if [ -z "$port" ] || [ "$port" -lt 1024 ] 2>/dev/null; then
    port=1024
  fi
  while [ "$tries" -lt 500 ]; do
    if ! port_in_use_by_us "$port" "$@" && ! port_taken_foreign "$proto" "$port"; then
      printf '%s' "$port"
      return 0
    fi
    port=$((port + 1))
    if [ "$port" -gt 65535 ]; then
      port=1024
    fi
    tries=$((tries + 1))
  done
  return 1
}

find_free_udp_port() { find_free_port udp "$@"; }
find_free_tcp_port() { find_free_port tcp "$@"; }

resolve_host_port() {
  local proto="$1" preferred="$2" what="$3"
  shift 3
  if ! port_taken_foreign "$proto" "$preferred" && ! port_in_use_by_us "$preferred" "$@"; then
    printf '%s' "$preferred"
    return 0
  fi
  if [ "${AUTO_PORTS:-0}" != "1" ]; then
    require_host_port "$proto" "$preferred" "$what"
    return 1
  fi
  local next
  next="$(find_free_port "$proto" "$preferred" "$@")" || \
    die "Не удалось подобрать свободный ${proto}-порт для ${what} (старт с ${preferred})"
  if [ "$next" != "$preferred" ]; then
    echo "ARDTT_WARN|${what}: порт ${preferred}/${proto} занят — выбран ${next}/${proto}. Было: $(who_owns_port "$preferred")" >&2
  fi
  printf '%s' "$next"
}

resolve_udp_host_port() { resolve_host_port udp "$@"; }
resolve_tcp_host_port() { resolve_host_port tcp "$@"; }

load_our_published_ports() {
  OUR_HOST_PORTS=""
  [ -n "${ARDTT_CONTAINER_NAME:-}" ] || return 0
  command -v docker >/dev/null 2>&1 || return 0
  docker inspect "$ARDTT_CONTAINER_NAME" >/dev/null 2>&1 || return 0
  local jsonf
  jsonf="$(mktemp)"
  docker inspect -f '{{json .HostConfig.PortBindings}}' "$ARDTT_CONTAINER_NAME" >"$jsonf" 2>/dev/null || { rm -f "$jsonf"; return 0; }
  OUR_HOST_PORTS="$(python3 - "$jsonf" <<'PY' 2>/dev/null || true
import json,sys
try:
    d=json.load(open(sys.argv[1],encoding="utf-8"))
except Exception:
    raise SystemExit(0)
out=[]
for spec, binds in (d or {}).items():
    proto="tcp"
    if "/" in spec:
        proto=spec.split("/")[-1]
    for b in binds or []:
        hp=(b or {}).get("HostPort") or ""
        if hp:
            out.append(f"{proto}:{hp}")
print(" ".join(out))
PY
)"
  rm -f "$jsonf"
}

preserve_previous_ports() {
  [ "${AUTO_PORTS:-0}" = "1" ] || return 0
  local envf="$1"
  local prev=""
  prev="$(env_file_val "$envf" ARDTT_DIRECT_PORT)"
  if [ -n "$prev" ]; then DIRECT_PORT="$prev"; fi
  prev="$(env_file_val "$envf" ARDTT_BYPASS_PORT)"
  if [ -n "$prev" ]; then BYPASS_PORT="$prev"; fi
  prev="$(env_file_val "$envf" ARDTT_CASCADE_LISTEN_PORT)"
  if [ -n "$prev" ]; then CASCADE_LISTEN_PORT="$prev"; fi
  prev="$(env_file_val "$envf" ARDTT_PROVISION_PORT)"
  if [ -n "$prev" ]; then PROVISION_PORT="$prev"; fi
  prev="$(env_file_val "$envf" ARDTT_TELEMETRY_PORT)"
  if [ -n "$prev" ]; then TELEMETRY_PORT="$prev"; fi
  return 0
}
