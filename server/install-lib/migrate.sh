# shellcheck shell=bash
# Targeted migration of a previous ARDTT install. Never call host-wide
# dataplane cleanup. If ownership cannot be proven, stop and report.

legacy_data_dir() {
  if [ -d "${INSTALL_DIR}/data" ] && [ -f "${INSTALL_DIR}/data/users.json" ]; then
    printf '%s' "${INSTALL_DIR}/data"
    return 0
  fi
  if [ -d "${INSTALL_DIR}/stack/data" ]; then
    printf '%s' "${INSTALL_DIR}/stack/data"
    return 0
  fi
  if [ -d /opt/nonamevpn/stack/data ]; then
    printf '%s' "/opt/nonamevpn/stack/data"
    return 0
  fi
  return 1
}

legacy_env_file() {
  for f in "${INSTALL_DIR}/.env" "${INSTALL_DIR}/stack/.env" /opt/nonamevpn/stack/.env; do
    [ -f "$f" ] && printf '%s' "$f" && return 0
  done
  return 1
}

legacy_was_hostnet() {
  local envf mode
  envf="$(legacy_env_file || true)"
  mode="$(env_file_val "${envf:-/dev/null}" ARDTT_NETWORK_MODE)"
  [ -z "$mode" ] && mode="$(env_file_val "${envf:-/dev/null}" NVPN_NETWORK_MODE)"
  case "$mode" in
    host|hostnet) return 0 ;;
  esac
  if docker inspect ardtt-host >/dev/null 2>&1; then
    local ns
    ns="$(docker inspect -f '{{.HostConfig.NetworkMode}}' ardtt-host 2>/dev/null || true)"
    [ "$ns" = "host" ] && return 0
  fi
  if docker inspect ardtt >/dev/null 2>&1; then
    local ns
    ns="$(docker inspect -f '{{.HostConfig.NetworkMode}}' ardtt 2>/dev/null || true)"
    [ "$ns" = "host" ] && return 0
  fi
  return 1
}

plan_legacy_resources() {
  LEGACY_CONTAINER=""
  LEGACY_NETNS=isolated
  LEGACY_IFACES=""
  local cand
  for cand in "${ARDTT_CONTAINER_NAME:-ardtt}" ardtt ardtt-host; do
    docker inspect "$cand" >/dev/null 2>&1 || continue
    if object_owned_by_us container "$cand" || container_looks_like_legacy_ardtt "$cand"; then
      LEGACY_CONTAINER="$cand"
      break
    fi
  done
  if [ -z "$LEGACY_CONTAINER" ]; then
    return 0
  fi
  local ns
  ns="$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$LEGACY_CONTAINER" 2>/dev/null || true)"
  if [ "$ns" = "host" ]; then
    LEGACY_NETNS=hostnet
    LEGACY_IFACES="$(docker exec "$LEGACY_CONTAINER" sh -c 'for i in awg0 wdttraw0 warp0 cascade0; do ip link show "$i" >/dev/null 2>&1 && echo "$i"; done' 2>/dev/null || true)"
  else
    LEGACY_NETNS=isolated
    LEGACY_IFACES="$(docker exec "$LEGACY_CONTAINER" sh -c 'for i in awg0 wdttraw0 warp0 cascade0; do ip link show "$i" >/dev/null 2>&1 && echo "$i"; done' 2>/dev/null || true)"
  fi
}

migrate_legacy_install_dir() {
  if [ -d /opt/nonamevpn ] && [ ! -e /opt/ardtt ]; then
    mv /opt/nonamevpn /opt/ardtt
    echo "ARDTT_WARN|каталог /opt/nonamevpn перенесён в /opt/ardtt"
  fi
  if [ "${INSTALL_DIR}" = "/opt/nonamevpn" ]; then
    INSTALL_DIR=/opt/ardtt
  fi
}

copy_data_tree() {
  local src="$1" dest="$2"
  mkdir -p "$dest"
  chmod 700 "$dest"
  cp -a "$src"/. "$dest"/
}

backup_data() {
  local src="$1" dest="$2"
  rm -rf "$dest"
  mkdir -p "$dest"
  cp -a "$src"/. "$dest"/
}

migrate_confirmed_logs() {
  local src=""
  local envf
  envf="$(legacy_env_file || true)"
  src="$(env_file_val "${envf:-/dev/null}" ARDTT_TELEMETRY_LOG_ROOT)"
  [ -n "$src" ] || src="/var/logs/app"
  [ -d "$src" ] || return 0
  [ "$src" = "${INSTALL_DIR}/logs" ] && return 0
  if [ -n "${LEGACY_CONTAINER:-}" ]; then
    local mounts
    mounts="$(docker inspect -f '{{range .Mounts}}{{.Source}}:{{.Destination}} {{end}}' "$LEGACY_CONTAINER" 2>/dev/null || true)"
    echo "$mounts" | grep -q "${src}:/var/logs/app" || return 0
    mkdir -p "${INSTALL_DIR}/logs"
    cp -a "$src"/. "${INSTALL_DIR}/logs"/ 2>/dev/null || true
    echo "ARDTT_INFO|логи ARDTT скопированы из ${src} в ${INSTALL_DIR}/logs"
  fi
}

stop_legacy_owned() {
  plan_legacy_resources
  if legacy_was_hostnet && [ -z "${LEGACY_CONTAINER:-}" ]; then
    die "Старая установка, похоже, была hostnet, но контейнер ARDTT не найден. Снимите leftover awg0/warp0/ip rule вручную, если они ваши, и повторите. Универсальная очистка хоста отключена."
  fi
  local stack="${INSTALL_DIR}/stack"
  if [ -f "$stack/docker-compose.yml" ]; then
    local proj
    proj="$(env_file_val "$stack/.env" COMPOSE_PROJECT_NAME)"
    [ -n "$proj" ] || proj=stack
    (
      cd "$stack" || exit 0
      COMPOSE_PROJECT_NAME="$proj" docker compose --profile isolated down --remove-orphans 2>/dev/null || true
      COMPOSE_PROJECT_NAME="$proj" docker compose --profile hostnet down --remove-orphans 2>/dev/null || true
    ) || true
  fi
  if [ -n "${LEGACY_CONTAINER:-}" ]; then
    # compose down above (or stop_owned_stack earlier) may already have removed it.
    # A missing container is success — do not die with a misleading ownership error.
    if ! docker inspect "$LEGACY_CONTAINER" >/dev/null 2>&1; then
      echo "ARDTT_INFO|контейнер ${LEGACY_CONTAINER} уже снят"
    elif object_owned_by_us container "$LEGACY_CONTAINER" || container_looks_like_legacy_ardtt "$LEGACY_CONTAINER"; then
      docker stop "$LEGACY_CONTAINER" >/dev/null 2>&1 || true
      docker rm -f "$LEGACY_CONTAINER" >/dev/null 2>&1 || true
    else
      die "Контейнер ${LEGACY_CONTAINER} не подтверждён как ARDTT — не останавливаем"
    fi
  fi
  # Hostnet leftovers: only interfaces we listed while the container was ours
  # AND that still exist with ARDTT-looking addresses after stop.
  if [ "${LEGACY_NETNS:-}" = "hostnet" ] && [ -n "${LEGACY_IFACES:-}" ]; then
    local iface addr
    for iface in $LEGACY_IFACES; do
      ip link show "$iface" >/dev/null 2>&1 || continue
      addr="$(ip -4 -o addr show "$iface" 2>/dev/null | awk '{print $4}')"
      echo "$addr" | grep -Eq '10\.(8|9|10|99)\.' || {
        echo "ARDTT_WARN|интерфейс ${iface} на хосте не похож на ARDTT (${addr}) — не трогаем"
        continue
      }
      ip link del "$iface" 2>/dev/null || true
      echo "ARDTT_INFO|снят подтверждённый leftover ${iface} после hostnet"
    done
  fi
}

preserve_live_cascade() {
  [ "$ROLE" = "entry" ] || return 0
  [ "${ARDTT_CASCADE_FORCE_DISABLE:-${NVPN_CASCADE_FORCE_DISABLE:-0}}" = "1" ] && return 0
  local envf data prev
  envf="$(legacy_env_file || true)"
  data="$(legacy_data_dir || true)"
  prev="$(env_file_val "${envf:-/dev/null}" ARDTT_CASCADE_ENABLED)"
  if [ "$prev" = "1" ] && [ "$CASCADE_ENABLED" != "1" ]; then
    CASCADE_ENABLED=1
    echo "ARDTT_WARN|каскад сохранён с прошлого деплоя (ARDTT_CASCADE_FORCE_DISABLE=1 чтобы снять)"
  fi
  if [ "$CASCADE_ENABLED" != "1" ]; then
    if [ -n "$data" ] && [ -s "$data/cascade.priv" ]; then
      echo "ARDTT_WARN|каскадные ключи на диске, hop выключен — включите каскад в приложении чтобы снова связать вход с выходом"
    fi
    CASCADE_DNS=""
    return 0
  fi
  if [ -z "$CASCADE_PEER_ENDPOINT" ]; then
    CASCADE_PEER_ENDPOINT="$(env_file_val "${envf:-/dev/null}" ARDTT_CASCADE_PEER_ENDPOINT)"
  fi
  if [ -z "$CASCADE_PEER_ENDPOINT" ] && [ -n "$data" ] && [ -s "$data/cascade.peer.endpoint" ]; then
    CASCADE_PEER_ENDPOINT="$(tr -d '[:space:]' < "$data/cascade.peer.endpoint")"
  fi
  if [ -z "$CASCADE_PEER_PUBLIC_KEY" ]; then
    CASCADE_PEER_PUBLIC_KEY="$(env_file_val "${envf:-/dev/null}" ARDTT_CASCADE_PEER_PUBLIC_KEY)"
  fi
  if [ -z "$CASCADE_PEER_PUBLIC_KEY" ] && [ -n "$data" ] && [ -s "$data/cascade.peer.pub" ]; then
    CASCADE_PEER_PUBLIC_KEY="$(tr -d '[:space:]' < "$data/cascade.peer.pub")"
  fi
  if [ -z "${CASCADE_PEER_PROVISION_PORT:-}" ] || [ "$CASCADE_PEER_PROVISION_PORT" = "9100" ]; then
    local prev_prov
    prev_prov="$(env_file_val "${envf:-/dev/null}" ARDTT_CASCADE_PEER_PROVISION_PORT)"
    [ -n "$prev_prov" ] && CASCADE_PEER_PROVISION_PORT="$prev_prov"
  fi
  [ -n "$CASCADE_DNS" ] || CASCADE_DNS="10.10.0.2"
}
