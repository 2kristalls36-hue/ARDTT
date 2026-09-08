#!/bin/bash
# ARDTT VPS installer — canonical copy lives here (server/install.sh).
# Protocol (must stay in the first 400 chars: APK ≤0.5.245 sniffs only that window):
#   ARDTT_PROGRESS|<0..1>|<step>
#   ARDTT_ERROR|<message>
#   ARDTT_DONE|install_dir=…|public_host=…|direct_port=…|bypass_port=…
#   ARDTT_WARN|<message>
#
# Product path: one release archive ardtt-server-<ver>-linux-<arch>.tar.gz
#   (docker save image + this script + Compose). No git clone, no network
#   Docker installer, GHCR, or second install.sh fetch. Docker Engine must already be running.
set -euo pipefail

prog() { echo "ARDTT_PROGRESS|$1|$2"; }
die() { echo "ARDTT_ERROR|$*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_LIB_DIR="${SCRIPT_DIR}/install-lib"
if [ ! -d "$INSTALL_LIB_DIR" ]; then
  INSTALL_LIB_DIR="$(cd "$(dirname "$0")" && pwd)/install-lib"
fi
# shellcheck disable=SC1091
. "$INSTALL_LIB_DIR/common.sh"
# shellcheck disable=SC1091
. "$INSTALL_LIB_DIR/ports.sh"
# shellcheck disable=SC1091
. "$INSTALL_LIB_DIR/network.sh"
# shellcheck disable=SC1091
. "$INSTALL_LIB_DIR/package.sh"
# shellcheck disable=SC1091
. "$INSTALL_LIB_DIR/ownership.sh"
# shellcheck disable=SC1091
. "$INSTALL_LIB_DIR/migrate.sh"
# shellcheck disable=SC1091
. "$INSTALL_LIB_DIR/uninstall.sh"

SAFE_EXTRACT_PY="${SAFE_EXTRACT_PY:-}"
if [ -f "$SCRIPT_DIR/../scripts/safe-extract-package.py" ]; then
  SAFE_EXTRACT_PY="$SCRIPT_DIR/../scripts/safe-extract-package.py"
elif [ -f "$SCRIPT_DIR/scripts/safe-extract-package.py" ]; then
  SAFE_EXTRACT_PY="$SCRIPT_DIR/scripts/safe-extract-package.py"
fi

ACTION="${ARDTT_ACTION:-install}"
INSTALL_DIR="${ARDTT_INSTALL_DIR:-${NVPN_INSTALL_DIR:-/opt/ardtt}}"
PUBLIC_HOST="${ARDTT_PUBLIC_HOST:-${NVPN_PUBLIC_HOST:-}}"
DIRECT_PORT="${ARDTT_DIRECT_PORT:-${NVPN_DIRECT_PORT:-51820}}"
BYPASS_PORT="${ARDTT_BYPASS_PORT:-${NVPN_BYPASS_PORT:-56003}}"
AUTO_PORTS="${ARDTT_AUTO_PORTS:-${NVPN_AUTO_PORTS:-0}}"
PROVISION_PORT="${ARDTT_PROVISION_PORT:-9100}"
PROVISION_LISTEN="${ARDTT_PROVISION_LISTEN:-${NVPN_PROVISION_LISTEN:-0.0.0.0:9100}}"
TELEMETRY_PORT="${ARDTT_TELEMETRY_PORT:-${NVPN_TELEMETRY_PORT:-9200}}"
KEEP_INSTALL_LOG="${ARDTT_KEEP_INSTALL_LOG:-${NVPN_KEEP_INSTALL_LOG:-0}}"
ROLE="${ARDTT_ROLE:-${NVPN_ROLE:-entry}}"
CASCADE_ENABLED="${ARDTT_CASCADE_ENABLED:-${NVPN_CASCADE_ENABLED:-0}}"
CASCADE_LISTEN_PORT="${ARDTT_CASCADE_LISTEN_PORT:-${NVPN_CASCADE_LISTEN_PORT:-51820}}"
CASCADE_PEER_ENDPOINT="${ARDTT_CASCADE_PEER_ENDPOINT:-${NVPN_CASCADE_PEER_ENDPOINT:-}}"
CASCADE_PEER_PUBLIC_KEY="${ARDTT_CASCADE_PEER_PUBLIC_KEY:-${NVPN_CASCADE_PEER_PUBLIC_KEY:-}}"
CASCADE_PEER_PROVISION_PORT="${ARDTT_CASCADE_PEER_PROVISION_PORT:-9100}"
CASCADE_DNS="${ARDTT_CASCADE_DNS:-${NVPN_CASCADE_DNS:-10.10.0.2}}"
MIN_DISK_MB="${ARDTT_MIN_DISK_MB:-${NVPN_MIN_DISK_MB:-2500}}"
MIN_RAM_MB="${ARDTT_MIN_RAM_MB:-384}"
DIRECT_LISTEN_PORT=51820
BYPASS_LISTEN_PORT=56003
NETWORK_MODE=isolated
if [ "$ROLE" = "exit" ]; then
  CASCADE_ENABLED=1
fi

compose_up_cmd() {
  local bin extra=()
  bin="$(compose_bin)" || die "docker compose недоступен. Нужен Compose v2 на хосте или bin/docker-compose из пакета ARDTT."
  [ -f .env ] && extra+=(--env-file .env)
  if [ "$ROLE" = "exit" ] && [ -f docker-compose.exit.yml ]; then
    extra+=(-f docker-compose.yml -f docker-compose.exit.yml)
  else
    extra+=(-f docker-compose.yml)
  fi
  COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" ARDTT_NETWORK_MODE=isolated \
    $bin "${extra[@]}" "$@"
}

# Stock 1.0.45 image: ready.sh has a set -u `local name="$1" pidfile=...${name}`
# bug. docker cp writes the host uid (often 1000) and mode 644; with cap_drop
# ALL the container cannot chmod that file, and exec.Command("/opt/ardtt/ready.sh")
# fails. Stream the package copy as container root, then chmod 755.
overlay_ready_script() {
  local name="${ARDTT_CONTAINER_NAME:-}" src=""
  [ -n "$name" ] || return 1
  if [ -f "${INSTALL_DIR}/current/ready.sh" ]; then
    src="${INSTALL_DIR}/current/ready.sh"
  elif [ -f "${SCRIPT_DIR}/ready.sh" ]; then
    src="${SCRIPT_DIR}/ready.sh"
  elif [ -n "${PKG_DIR:-}" ] && [ -f "${PKG_DIR}/ready.sh" ]; then
    src="${PKG_DIR}/ready.sh"
  fi
  [ -n "$src" ] || return 0
  docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -qx true || return 1
  docker exec -i "$name" sh -c 'cat > /opt/ardtt/ready.sh && chmod 755 /opt/ardtt/ready.sh' < "$src" >/dev/null 2>&1 || return 1
  return 0
}

wait_readiness() {
  local name="${ARDTT_CONTAINER_NAME}" i
  for i in $(seq 1 40); do
    if docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -qx true; then
      overlay_ready_script || true
      if docker exec "$name" bash /opt/ardtt/ready.sh >/dev/null 2>&1; then
        return 0
      fi
    fi
    sleep 3
  done
  return 1
}

readiness_detail() {
  overlay_ready_script || true
  docker exec "${ARDTT_CONTAINER_NAME}" bash /opt/ardtt/ready.sh 2>&1 | tail -5 | tr '\n' ' ' | cut -c1-400
}

preflight_docker() {
  command -v docker >/dev/null 2>&1 || die "Docker Engine не найден. Установите Docker заранее. Этот пакет не ставит Docker и не качает установщик Engine из сети."
  docker info >/dev/null 2>&1 || die "Docker Engine не отвечает. Запустите службу docker и повторите. Установщик не перезапускает dockerd."
  command -v python3 >/dev/null 2>&1 || die "Нужен python3 на хосте (безопасная распаковка и instance.json). Пакет не ставит его из сети."
  local ver
  ver="$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)"
  [ -n "$ver" ] || die "Не удалось прочитать версию Docker"
  echo "ARDTT_INFO|Docker ${ver} $(host_arch)"
}

preflight_tun() {
  if [ ! -e /dev/net/tun ]; then
    modprobe tun 2>/dev/null || true
  fi
  [ -e /dev/net/tun ] || die "Нет /dev/net/tun. Загрузите модуль tun. Установщик не меняет sysctl хоста."
}

preflight_space() {
  local need="$MIN_DISK_MB" avail docker_root
  avail="$(disk_avail_mb "$INSTALL_DIR")"
  docker_root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)"
  local avail_docker
  avail_docker="$(disk_avail_mb "$docker_root")"
  echo "ARDTT_INFO|диск install=${avail:-?} МБ DockerRootDir=${avail_docker:-?} МБ (нужно ≥${need} с запасом на load+слои+резерв)"
  if [ -n "${avail:-}" ] && [ "$avail" -lt "$need" ] 2>/dev/null; then
    die "Мало места на ${INSTALL_DIR}: свободно ${avail} МБ (нужно ≥${need} МБ на распаковку, слои и резерв предыдущей версии). Глобальная очистка сервера не выполняется."
  fi
  if [ -n "${avail_docker:-}" ] && [ "$avail_docker" -lt "$need" ] 2>/dev/null; then
    die "Мало места в DockerRootDir ${docker_root}: свободно ${avail_docker} МБ (нужно ≥${need} МБ)."
  fi
  local ram
  ram="$(mem_avail_mb)"
  if [ "${ram:-0}" -lt "$MIN_RAM_MB" ] 2>/dev/null; then
    die "Мало RAM: Available ${ram} МБ (нужно ≥${MIN_RAM_MB} МБ). Swap хоста не создаём и не трогаем."
  fi
}

install_bundled_compose() {
  if compose_bin >/dev/null 2>&1; then
    return 0
  fi
  local src=""
  if [ -x "$PKG_DIR/bin/docker-compose" ]; then
    src="$PKG_DIR/bin/docker-compose"
  fi
  [ -n "$src" ] || die "Нет Docker Compose v2. Положите плагин на хост или используйте bin/docker-compose из пакета ARDTT. Системный плагин не заменяем."
  mkdir -p "$INSTALL_DIR/bin"
  cp -f "$src" "$INSTALL_DIR/bin/docker-compose"
  chmod 755 "$INSTALL_DIR/bin/docker-compose"
  ARDTT_COMPOSE_BIN="$INSTALL_DIR/bin/docker-compose"
  echo "ARDTT_INFO|Compose CLI из пакета: $ARDTT_COMPOSE_BIN"
}

write_env_file() {
  local dest="$1"
  local bypass_dns="10.9.0.1" warp_mode="hideip" warp_ifaces="awg0 wdttraw0" warp_url=""
  if [ "$CASCADE_ENABLED" = "1" ]; then
    [ -n "$CASCADE_DNS" ] || CASCADE_DNS="10.10.0.2"
    bypass_dns="$CASCADE_DNS"
    warp_mode="passthrough"
  else
    CASCADE_DNS=""
  fi
  if [ "$ROLE" = "exit" ]; then
    warp_mode="exit-hideip"
    warp_ifaces="cascade0"
    warp_url="http://10.10.0.1:9100/v1/hide-ip-prefixes"
    DIRECT_PORT="$CASCADE_LISTEN_PORT"
  fi
  cat > "$dest" <<EOF
ARDTT_PUBLIC_HOST=$PUBLIC_HOST
ARDTT_IMAGE=$ARDTT_IMAGE
ARDTT_INSTANCE_ID=$INSTANCE_ID
ARDTT_CONTAINER_NAME=$ARDTT_CONTAINER_NAME
ARDTT_NETWORK_NAME=$ARDTT_NETWORK_NAME
ARDTT_BRIDGE_SUBNET=$ARDTT_BRIDGE_SUBNET
ARDTT_DATA_DIR=$INSTALL_DIR/data
ARDTT_LOG_DIR=$INSTALL_DIR/logs
COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT
ARDTT_DIRECT_PORT=$DIRECT_PORT
ARDTT_BYPASS_PORT=$BYPASS_PORT
ARDTT_PROVISION_PORT=$PROVISION_PORT
ARDTT_TELEMETRY_PORT=$TELEMETRY_PORT
ARDTT_DIRECT_LISTEN_PORT=$DIRECT_LISTEN_PORT
ARDTT_BYPASS_LISTEN_PORT=$BYPASS_LISTEN_PORT
ARDTT_PROVISION_LISTEN=0.0.0.0:9100
TELEMETRY_LISTEN=0.0.0.0:9200
ARDTT_TELEMETRY_LISTEN=0.0.0.0:9200
ARDTT_SKIP_TELEMETRY=0
ARDTT_DEPLOY_VERSION=$DEPLOY_VERSION
ARDTT_WARP_GOMEMLIMIT=256MiB
ARDTT_MEM_LIMIT=${ARDTT_MEM_LIMIT:-1g}
ARDTT_CPUS=${ARDTT_CPUS:-2.0}
ARDTT_PIDS_LIMIT=${ARDTT_PIDS_LIMIT:-512}
ARDTT_ROLE=$ROLE
ARDTT_CASCADE_ROLE=$ROLE
ARDTT_CASCADE_ENABLED=$CASCADE_ENABLED
ARDTT_CASCADE_LISTEN_PORT=$CASCADE_LISTEN_PORT
ARDTT_CASCADE_PEER_ENDPOINT=$CASCADE_PEER_ENDPOINT
ARDTT_CASCADE_PEER_PUBLIC_KEY=$CASCADE_PEER_PUBLIC_KEY
ARDTT_CASCADE_PEER_PROVISION_PORT=$CASCADE_PEER_PROVISION_PORT
ARDTT_CASCADE_DNS=$CASCADE_DNS
ARDTT_BYPASS_DNS=$bypass_dns
ARDTT_WARP_MODE=$warp_mode
ARDTT_WARP_DNS_IIFACES="$warp_ifaces"
ARDTT_WARP_HIDEIP_URL=$warp_url
ARDTT_NETWORK_MODE=isolated
EOF
}

ensure_cascade_keys() {
  local data="$1"
  mkdir -p "$data"
  if [ "$CASCADE_ENABLED" != "1" ] && [ "$ROLE" != "exit" ]; then
    return 0
  fi
  local img="$ARDTT_IMAGE"
  docker image inspect "$img" >/dev/null 2>&1 || return 0
  if [ ! -s "$data/cascade.priv" ]; then
    docker run --rm --network none --entrypoint awg "$img" genkey >"$data/cascade.priv"
    chmod 600 "$data/cascade.priv"
  fi
  docker run --rm --network none -i --entrypoint awg "$img" pubkey <"$data/cascade.priv" >"$data/cascade.pub"
  chmod 644 "$data/cascade.pub" 2>/dev/null || true
  if [ -n "$CASCADE_PEER_PUBLIC_KEY" ]; then
    printf '%s\n' "$CASCADE_PEER_PUBLIC_KEY" >"$data/cascade.peer.pub"
  fi
  if [ -n "$CASCADE_PEER_ENDPOINT" ]; then
    printf '%s\n' "$CASCADE_PEER_ENDPOINT" >"$data/cascade.peer.endpoint"
  fi
  local pub
  pub="$(tr -d '[:space:]' <"$data/cascade.pub" 2>/dev/null || true)"
  if [ -n "$pub" ]; then
    echo "ARDTT_CASCADE_PUBLIC_KEY|$pub"
  fi
}

find_package_file() {
  if [ -n "${ARDTT_PACKAGE:-}" ] && [ -f "$ARDTT_PACKAGE" ]; then
    printf '%s' "$ARDTT_PACKAGE"
    return 0
  fi
  local f
  for f in "$INSTALL_DIR"/incoming/ardtt-server-*.tar.gz "$INSTALL_DIR"/ardtt-server-*.tar.gz; do
    [ -f "$f" ] || continue
    printf '%s' "$f"
    return 0
  done
  return 1
}

acquire_lock() {
  mkdir -p "$INSTALL_DIR"
  exec 9>"$INSTALL_DIR/install.lock"
  if ! flock -n 9; then
    die "Другая установка этого экземпляра уже выполняется (${INSTALL_DIR}/install.lock)"
  fi
}

LOG_FILE=""
on_exit() {
  local code=$?
  if [ "$code" -ne 0 ]; then
    if [ -n "${LOG_FILE:-}" ] && [ -f "$LOG_FILE" ]; then
      mkdir -p "$INSTALL_DIR"
      cp -f "$LOG_FILE" "$INSTALL_DIR/install.log" 2>/dev/null || true
    fi
  elif [ "${KEEP_INSTALL_LOG:-0}" = "1" ] && [ -n "${LOG_FILE:-}" ]; then
    mkdir -p "$INSTALL_DIR"
    tail -c 200000 "$LOG_FILE" >"$INSTALL_DIR/install.log" 2>/dev/null || true
  fi
  [ -n "${LOG_FILE:-}" ] && rm -f "$LOG_FILE" 2>/dev/null || true
  exit "$code"
}

do_install() {
  migrate_legacy_install_dir
  [ -n "$PUBLIC_HOST" ] || die "ARDTT_PUBLIC_HOST не задан"
  if [ "$ROLE" != "entry" ] && [ "$ROLE" != "exit" ]; then
    die "ARDTT_ROLE должен быть entry или exit"
  fi
  if [ "${ARDTT_SKIP_ROOT_CHECK:-${NVPN_SKIP_ROOT_CHECK:-0}}" != "1" ] && [ "$(id -u)" -ne 0 ]; then
    die "Нужен root (или запуск через sudo)"
  fi

  PROG_ROLE="вход (клиенты)"
  [ "$ROLE" = "exit" ] && PROG_ROLE="выход (WARP)"
  [ "$CASCADE_ENABLED" = "1" ] && [ "$ROLE" = "entry" ] && PROG_ROLE="вход + каскад на ${CASCADE_PEER_ENDPOINT:-?}"
  prog 0.04 "Проверка прав · ${PROG_ROLE}"

  mkdir -p "$INSTALL_DIR/incoming" "$INSTALL_DIR/data" "$INSTALL_DIR/logs" "$INSTALL_DIR/backups" "$INSTALL_DIR/releases"
  acquire_lock

  local env_now
  env_now="$(legacy_env_file || true)"
  preserve_previous_ports "${env_now:-/dev/null}"
  preserve_live_cascade
  ensure_instance
  load_our_published_ports

  if [ "${NETWORK_MODE:-isolated}" != "isolated" ] || [ "${ARDTT_NETWORK_MODE:-isolated}" = "hostnet" ]; then
    echo "ARDTT_WARN|hostnet больше не поддерживается — ставим isolated netns"
  fi
  NETWORK_MODE=isolated

  prog 0.08 "Поиск установочного пакета"
  local pkg
  pkg="$(find_package_file)" || die "Нет пакета ardtt-server-*-linux-$(host_arch).tar.gz. Скачайте актив релиза — без git clone и без docker pull."
  [ -n "${ARDTT_PACKAGE_SHA256:-}" ] || die "Задайте ARDTT_PACKAGE_SHA256 (SHA-256 релиза GitHub или закреплённая сумма приложения). Суммы внутри архива не подтверждают источник."
  prog 0.10 "Проверка SHA-256 пакета"
  verify_outer_sha256 "$pkg" "$ARDTT_PACKAGE_SHA256"

  if [ -n "${ARDTT_PKG_DIR:-}" ] && [ -f "${ARDTT_PKG_DIR}/manifest.json" ] && [ -f "${ARDTT_PKG_DIR}/install.sh" ]; then
    PKG_DIR="$ARDTT_PKG_DIR"
    echo "ARDTT_INFO|используем уже распакованный пакет $PKG_DIR"
  else
    PKG_DIR="${INSTALL_DIR}/staging"
    rm -rf "$PKG_DIR"
    mkdir -p "$PKG_DIR"
    prog 0.14 "Безопасная распаковка пакета"
    if ! safe_extract_package "$pkg" "$PKG_DIR"; then
      die "Распаковка отклонена (опасные пути или повреждённый tar). Старый стек не остановлен."
    fi
  fi
  read_manifest "$PKG_DIR"
  require_manifest
  DEPLOY_VERSION="$PKG_DEPLOY_VERSION"
  ARDTT_IMAGE="${PKG_IMAGE_TAG:-ardtt/server:${DEPLOY_VERSION}}"

  if [ "${ARDTT_DRY_RUN:-0}" != "1" ]; then
    prog 0.18 "Preflight (Docker, TUN, место, порты, подсеть) — старый ARDTT ещё работает"
    preflight_docker
    preflight_tun
    preflight_space
    install_bundled_compose
  else
    echo "ARDTT_INFO|dry-run: Docker/TUN/диск не трогаем"
  fi

  if [ "$ROLE" = "exit" ]; then
    CASCADE_LISTEN_PORT="$(resolve_udp_host_port "$CASCADE_LISTEN_PORT" "каскад AmneziaWG")"
    DIRECT_PORT="$CASCADE_LISTEN_PORT"
  else
    DIRECT_PORT="$(resolve_udp_host_port "$DIRECT_PORT" "Direct AmneziaWG")"
    BYPASS_PORT="$(resolve_udp_host_port "$BYPASS_PORT" "Bypass RAW" "$DIRECT_PORT")"
  fi
  PROVISION_PORT="$(resolve_tcp_host_port "$PROVISION_PORT" "provision")"
  TELEMETRY_PORT="$(resolve_tcp_host_port "$TELEMETRY_PORT" "telemetry" "$PROVISION_PORT")"

  local prev_subnet=""
  if [ -f "$(instance_file)" ]; then
    prev_subnet="$(json_get "$(instance_file)" bridgeSubnet)"
  fi
  ARDTT_BRIDGE_SUBNET="$(pick_bridge_subnet "${ARDTT_BRIDGE_SUBNET:-$prev_subnet}")"
  echo "ARDTT_INFO|bridge ${ARDTT_BRIDGE_SUBNET} container=${ARDTT_CONTAINER_NAME} project=${COMPOSE_PROJECT}"

  if [ "${ARDTT_DRY_RUN:-0}" = "1" ]; then
    write_env_file "$PKG_DIR/.env"
    mkdir -p "$INSTALL_DIR/current"
    cp -a "$PKG_DIR/docker-compose.yml" "$INSTALL_DIR/current/" 2>/dev/null || true
    cp -f "$PKG_DIR/.env" "$INSTALL_DIR/current/.env" 2>/dev/null || cp -f "$PKG_DIR/.env" "$INSTALL_DIR/.env"
    local data_src
    data_src="$(legacy_data_dir || true)"
    if [ -n "$data_src" ] && [ "$data_src" != "$INSTALL_DIR/data" ]; then
      copy_data_tree "$data_src" "$INSTALL_DIR/data"
    fi
    printf '%s\n' "$DEPLOY_VERSION" > "$INSTALL_DIR/data/DEPLOY_VERSION"
    printf '%s\n' "$DEPLOY_VERSION" > "$INSTALL_DIR/DEPLOY_VERSION"
    write_instance
    prog 1.00 "dry-run: пакет проверен, стек не переключали"
    echo "ARDTT_DONE|dry_run=1|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$DEPLOY_VERSION|network_mode=isolated|direct_port=$DIRECT_PORT|bypass_port=$BYPASS_PORT|cascade_listen_port=$CASCADE_LISTEN_PORT|provision_port=$PROVISION_PORT|telemetry_port=$TELEMETRY_PORT|auto_ports=$AUTO_PORTS|instance=$INSTANCE_ID"
    return 0
  fi

  prog 0.30 "docker load образа (без build и без pull)"
  load_package_image "$IMAGE_TAR" "$ARDTT_IMAGE" "$PKG_IMAGE_ID"
  LOADED_IMAGE_ID="$(docker image inspect -f '{{.Id}}' "$ARDTT_IMAGE")"

  mkdir -p "$INSTALL_DIR/data" "$INSTALL_DIR/logs"
  chmod 700 "$INSTALL_DIR/data"
  # cap_drop ALL removes DAC_OVERRIDE. Container uid 0 can write 0700 data/
  # only if it owns the directory (root install). Non-root smoke installs
  # must allow other-write or mkdir /data/warp fails and the stack restarts.
  if [ "$(id -u)" -ne 0 ]; then
    chmod 0777 "$INSTALL_DIR/data" "$INSTALL_DIR/logs" || true
    echo "ARDTT_WARN|не root — каталоги data/logs 0777, иначе контейнер без DAC_OVERRIDE не пишет. Production: install от root."
  fi
  local data_src
  data_src="$(legacy_data_dir || true)"
  if [ -n "$data_src" ] && [ "$data_src" != "$INSTALL_DIR/data" ]; then
    backup_data "$data_src" "$INSTALL_DIR/backups/data-$(date +%s)"
    copy_data_tree "$data_src" "$INSTALL_DIR/data"
  fi
  migrate_confirmed_logs
  printf '%s\n' "$DEPLOY_VERSION" > "$INSTALL_DIR/data/DEPLOY_VERSION"

  local release="$INSTALL_DIR/releases/${DEPLOY_VERSION}"
  rm -rf "$release"
  mkdir -p "$release"
  cp -a "$PKG_DIR/docker-compose.yml" "$release/"
  [ -f "$PKG_DIR/docker-compose.exit.yml" ] && cp -a "$PKG_DIR/docker-compose.exit.yml" "$release/"
  [ -f "$PKG_DIR/manifest.json" ] && cp -a "$PKG_DIR/manifest.json" "$release/"
  cp -a "$PKG_DIR/install.sh" "$release/" 2>/dev/null || true
  [ -d "$PKG_DIR/install-lib" ] && cp -a "$PKG_DIR/install-lib" "$release/"
  if [ -f "$PKG_DIR/ready.sh" ]; then
    cp -a "$PKG_DIR/ready.sh" "$release/"
  elif [ -f "$SCRIPT_DIR/ready.sh" ]; then
    cp -a "$SCRIPT_DIR/ready.sh" "$release/"
  fi
  write_env_file "$release/.env"
  write_env_file "$INSTALL_DIR/.env"
  write_instance

  if [ "$CASCADE_ENABLED" = "1" ] || [ "$ROLE" = "exit" ]; then
    prog 0.40 "Ключи каскадного AWG"
    ensure_cascade_keys "$INSTALL_DIR/data"
  fi

  local prev_link="${INSTALL_DIR}/current"
  prog 0.50 "Остановка только этого экземпляра ARDTT"
  if [ -d "$prev_link" ] || [ -d "$INSTALL_DIR/stack" ]; then
    mkdir -p "$INSTALL_DIR/backups"
    if [ -f "${prev_link}/.env" ]; then
      rm -rf "$INSTALL_DIR/previous"
      cp -a "$prev_link" "$INSTALL_DIR/previous"
    fi
    stop_owned_stack "$prev_link"
    stop_legacy_owned
  fi

  rm -rf "$INSTALL_DIR/current"
  mkdir -p "$INSTALL_DIR/current"
  cp -a "$release"/. "$INSTALL_DIR/current/"
  ln -sfn "$release" "$INSTALL_DIR/current-release" 2>/dev/null || true

  prog 0.62 "Запуск compose --no-build --pull never"
  if ! (
    cd "$INSTALL_DIR/current"
    compose_up_cmd up -d --no-build --pull never
  ); then
    restore_previous_release || true
    die "docker compose up не удался. Код ≠ 0, ARDTT_DONE нет."
  fi

  prog 0.80 "Readiness (процессы, интерфейсы, /health)"
  if ! wait_readiness; then
    local detail
    detail="$(readiness_detail || true)"
    echo "ARDTT_WARN|readiness не прошла: ${detail}"
    if ! restore_previous_release; then
      stop_owned_stack "$INSTALL_DIR/current" || true
    fi
    die "Новая версия не прошла readiness. Код ≠ 0, ARDTT_DONE нет. ${detail}"
  fi

  printf '%s\n' "$DEPLOY_VERSION" > "$INSTALL_DIR/DEPLOY_VERSION"
  write_instance
  rm -rf "$PKG_DIR"
  # Keep previous until next successful install. Do not delete previous here.
  prog 1.00 "Готово"
  echo "ARDTT_DONE|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$DEPLOY_VERSION|telemetry_port=$TELEMETRY_PORT|provision_port=$PROVISION_PORT|role=$ROLE|cascade=$CASCADE_ENABLED|network_mode=isolated|direct_port=$DIRECT_PORT|bypass_port=$BYPASS_PORT|cascade_listen_port=$CASCADE_LISTEN_PORT|auto_ports=$AUTO_PORTS|instance=$INSTANCE_ID|container=$ARDTT_CONTAINER_NAME"
  echo "Создать пользователя: docker exec ${ARDTT_CONTAINER_NAME} provision -cmd create-user -name USER -data /data"
}

# --- main ---
if [ "${ARDTT_SKIP_ROOT_CHECK:-0}" = "1" ] && [ "${ARDTT_DRY_RUN:-0}" = "1" ]; then
  :
fi

LOG_FILE="$(mktemp /tmp/ardtt-install.XXXXXX.log)"
trap on_exit EXIT
exec > >(tee -a "$LOG_FILE") 2>&1

case "$ACTION" in
  uninstall)
    migrate_legacy_install_dir
    acquire_lock
    uninstall_this_instance
    ;;
  rollback)
    migrate_legacy_install_dir
    acquire_lock
    rollback_previous
    ;;
  install|update|"")
    do_install
    ;;
  *)
    die "Неизвестное ARDTT_ACTION=$ACTION (install|uninstall|rollback)"
    ;;
esac
