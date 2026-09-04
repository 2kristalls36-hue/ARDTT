#!/bin/bash
# ARDTT VPS installer — canonical copy lives here (server/install.sh).
# Android APK gets a copy via scripts/pack-deploy-assets.sh → assets/deploy/install.sh.
#
# Product path (from the app): SSH upload of stack.tar.gz + this script, then:
#   NVPN_PUBLIC_HOST=… bash /opt/nonamevpn/install.sh
# Cascade: phone SSHs to the exit VPS (NVPN_ROLE=exit) and the entry VPS
#   (NVPN_ROLE=entry NVPN_CASCADE_ENABLED=1) separately. Do not write SSH passwords
#   into .env.
# Ops path: same script; if the tarball is gone, re-run against already unpacked stack/.
#
# Protocol lines consumed by the Android DeployEngine:
#   NVPN_PROGRESS|<0..1>|<step>
#   NVPN_ERROR|<message>
#   NVPN_DONE|install_dir=…|public_host=…
#   NVPN_WARN|<message>
#
# On VPS with ≥1.8 GiB RAM, build new images BEFORE stopping the old stack so a
# mid-build SSH drop does not take the VPN down. On ~1 GiB hosts that order OOMs
# BuildKit (snapshot … does not exist). We stop first, wipe BuildKit, and bring
# the previous stack back if the build fails.
set -euo pipefail

INSTALL_DIR="${NVPN_INSTALL_DIR:-/opt/nonamevpn}"
PUBLIC_HOST="${NVPN_PUBLIC_HOST:-}"
DIRECT_PORT="${NVPN_DIRECT_PORT:-51820}"
BYPASS_PORT="${NVPN_BYPASS_PORT:-56003}"
PROVISION_LISTEN="${NVPN_PROVISION_LISTEN:-0.0.0.0:9100}"
TELEMETRY_PORT="${NVPN_TELEMETRY_PORT:-9200}"
KEEP_INSTALL_LOG="${NVPN_KEEP_INSTALL_LOG:-0}"
COMPOSE_PROJECT="${NVPN_COMPOSE_PROJECT:-stack}"
MIN_SWAP_MB="${NVPN_MIN_SWAP_MB:-2048}"
# Sequential one-image builds; do not demand 1.8G free on a 8–10G VPS.
MIN_DISK_MB="${NVPN_MIN_DISK_MB:-1100}"
MIN_DISK_UPDATE_MB="${NVPN_MIN_DISK_UPDATE_MB:-500}"
# entry = phone-facing stack. exit = hop egress (AWG + DNS + WARP).
ROLE="${NVPN_ROLE:-entry}"
CASCADE_ENABLED="${NVPN_CASCADE_ENABLED:-0}"
CASCADE_LISTEN_PORT="${NVPN_CASCADE_LISTEN_PORT:-51820}"
CASCADE_PEER_ENDPOINT="${NVPN_CASCADE_PEER_ENDPOINT:-}"
CASCADE_PEER_PUBLIC_KEY="${NVPN_CASCADE_PEER_PUBLIC_KEY:-}"
CASCADE_DNS="${NVPN_CASCADE_DNS:-10.10.0.2}"
if [ "$ROLE" = "exit" ]; then
  CASCADE_ENABLED=1
fi

env_file_val() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  grep -E "^${key}=" "$file" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\"' | tr -d "'" | tr -d '[:space:]'
}

# An in-app "update" of the entry hop often omits cascade flags. Dropping them
# tears down nvpn-cascade, leaves profiles on 10.10.0.2 DNS, and Hide-IP-off
# traffic can stick on Cloudflare while the app still shows the VPS WAN.
preserve_live_cascade() {
  [ "$ROLE" = "entry" ] || return 0
  [ "${NVPN_CASCADE_FORCE_DISABLE:-0}" = "1" ] && return 0
  local envf="$INSTALL_DIR/stack/.env"
  local data="$INSTALL_DIR/stack/data"
  local prev
  prev="$(env_file_val "$envf" NVPN_CASCADE_ENABLED)"
  if [ "$prev" = "1" ] && [ "$CASCADE_ENABLED" != "1" ]; then
    CASCADE_ENABLED=1
    echo "NVPN_WARN|каскад сохранён с прошлого деплоя (NVPN_CASCADE_FORCE_DISABLE=1 чтобы снять)"
  fi
  if [ "$CASCADE_ENABLED" != "1" ]; then
    if [ -s "$data/cascade.priv" ]; then
      echo "NVPN_WARN|каскадные ключи на диске, hop выключен — включите каскад в приложении чтобы снова связать вход с выходом"
    fi
    CASCADE_DNS=""
    return 0
  fi
  if [ -z "$CASCADE_PEER_ENDPOINT" ]; then
    CASCADE_PEER_ENDPOINT="$(env_file_val "$envf" NVPN_CASCADE_PEER_ENDPOINT)"
  fi
  if [ -z "$CASCADE_PEER_ENDPOINT" ] && [ -s "$data/cascade.peer.endpoint" ]; then
    CASCADE_PEER_ENDPOINT="$(tr -d '[:space:]' < "$data/cascade.peer.endpoint")"
  fi
  if [ -z "$CASCADE_PEER_PUBLIC_KEY" ]; then
    CASCADE_PEER_PUBLIC_KEY="$(env_file_val "$envf" NVPN_CASCADE_PEER_PUBLIC_KEY)"
  fi
  if [ -z "$CASCADE_PEER_PUBLIC_KEY" ] && [ -s "$data/cascade.peer.pub" ]; then
    CASCADE_PEER_PUBLIC_KEY="$(tr -d '[:space:]' < "$data/cascade.peer.pub")"
  fi
  [ -n "$CASCADE_DNS" ] || CASCADE_DNS="10.10.0.2"
}
preserve_live_cascade

LOG_FILE="$(mktemp /tmp/nvpn-install.XXXXXX.log)"
STAGING=""

cleanup_host_packages() {
  if command -v apt-get >/dev/null 2>&1; then
    apt-get clean >/dev/null 2>&1 || true
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb 2>/dev/null || true
  fi
  if command -v dnf >/dev/null 2>&1; then
    dnf clean all >/dev/null 2>&1 || true
  fi
}

# Drop dangling images only. Never `docker image prune -a`: tagged stack-*
# images must stay while the old stack is still running. Never prune volumes
# (bypass-config). Never `docker builder prune -af` between services: Docker 29
# overlayfs keeps BuildKit cache keys after prune, so the next golang image
# (direct and bypass share golang:1.25-bookworm) reports CACHED layers whose
# snapshots are gone → "snapshot … does not exist: not found".
prepare_docker_build() {
  command -v docker >/dev/null 2>&1 || return 0
  docker image prune -f >/dev/null 2>&1 || true
}

wait_for_docker() {
  local i
  for i in $(seq 1 45); do
    docker info >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# Stop dockerd, delete BuildKit metadata+snapshots, start dockerd.
# Callers on tiny VPS must compose-down the VPN stack first.
reset_docker_buildkit() {
  command -v docker >/dev/null 2>&1 || return 0
  docker builder prune -af >/dev/null 2>&1 || true
  docker buildx prune -af >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true
  echo "NVPN_INFO|сброс BuildKit: restart docker и удаление /var/lib/docker/buildkit"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl stop docker 2>/dev/null || true
    rm -rf /var/lib/docker/buildkit
    systemctl start docker 2>/dev/null || service docker start 2>/dev/null || true
    if ! wait_for_docker; then
      echo "NVPN_WARN|docker не ответил сразу после сброса BuildKit"
    fi
  else
    rm -rf /var/lib/docker/buildkit
  fi
}

cleanup_docker_build_junk() {
  prepare_docker_build
}

STACK_STOPPED_FOR_BUILD=0

restore_live_stack_if_needed() {
  [ "${STACK_STOPPED_FOR_BUILD:-0}" = "1" ] || return 0
  [ -f "$INSTALL_DIR/stack/docker-compose.yml" ] || return 0
  echo "NVPN_WARN|поднимаем прежний стек (сборка не закончена)"
  (
    cd "$INSTALL_DIR/stack" || exit 0
    if [ -f .env ]; then
      COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose --env-file .env up -d
    else
      COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose up -d
    fi
  ) >/dev/null 2>&1 || \
    docker start nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry nvpn-cascade >/dev/null 2>&1 || true
  STACK_STOPPED_FOR_BUILD=0
}

cleanup_stale_deploy_files() {
  # Leftovers from older installer names, failed SSH drops, and agent probes.
  # Do not delete stack.staging here: it is the in-progress unpack.
  # Do not glob /tmp/nvpn-install.*.log: that is the live tee for this run.
  rm -f "$INSTALL_DIR/install-live.log" "$INSTALL_DIR/install-run.log"
  rm -f /var/log/nvpn-build*.log /var/log/nvpn-install.log
  rm -f /tmp/nvpn-entry-* /tmp/nvpn-cascade-* /tmp/nvpn-cascade-probe-*.sh
  rm -rf /tmp/nvpn-provision /tmp/nvpn-data-bak /var/tmp/nvpn-*
  rm -rf "$INSTALL_DIR/stack.old"
  # Leftover wg-quick conf from kernel-WG WARP. Do not ip-link-del warp0 here:
  # the live nvpn-warp may still own it until compose replaces the container.
  # Do not delete stack/data/warp — that is the live wgcf account.
  rm -f /etc/wireguard/warp0.conf
  rm -f /tmp/nvpn-warp-* "$INSTALL_DIR"/stack/data/warp/*.conf.tmp 2>/dev/null || true
}

reclaim_disk() {
  cleanup_docker_build_junk
  cleanup_host_packages
  cleanup_stale_deploy_files
  docker container prune -f >/dev/null 2>&1 || true
  rm -f /var/log/*.gz /var/log/*.1 2>/dev/null || true
  if command -v journalctl >/dev/null 2>&1; then
    journalctl --vacuum-size=32M >/dev/null 2>&1 || true
  fi
}

stack_missing_image_count() {
  local missing=0
  local img
  local names="$1"
  [ -n "$names" ] || names="$(role_image_names)"
  for img in $names; do
    if ! docker image inspect "${img}:latest" >/dev/null 2>&1; then
      missing=$((missing + 1))
    fi
  done
  echo "$missing"
}

stack_images_ready() {
  [ "$(stack_missing_image_count "$1")" -eq 0 ]
}

# How much free disk the sequential build actually needs.
disk_need_mb() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "$MIN_DISK_MB"
    return 0
  fi
  local missing
  missing="$(stack_missing_image_count "$(role_image_names)")"
  if [ "${missing:-0}" -eq 0 ]; then
    echo "$MIN_DISK_UPDATE_MB"
  elif [ "${missing}" -le 2 ]; then
    echo 700
  else
    echo "$MIN_DISK_MB"
  fi
}

# 2G swap on an 8–10G VPS leaves too little room for image rebuilds.
swap_target_mb() {
  local disk_mb
  disk_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $2}')"
  if [ "${disk_mb:-0}" -gt 0 ] && [ "${disk_mb}" -lt 16384 ]; then
    echo 1024
  else
    echo "${MIN_SWAP_MB:-2048}"
  fi
}

role_build_services() {
  if [ "$ROLE" = "exit" ]; then
    echo "provision direct dns warp telemetry"
  elif [ "$CASCADE_ENABLED" = "1" ]; then
    echo "provision direct bypass warp telemetry"
  else
    echo "provision direct bypass dns warp telemetry"
  fi
}

role_up_services() {
  if [ "$ROLE" = "exit" ]; then
    echo "provision cascade dns warp telemetry"
  elif [ "$CASCADE_ENABLED" = "1" ]; then
    echo "provision direct bypass warp cascade telemetry"
  else
    echo "provision direct bypass dns warp telemetry"
  fi
}

role_image_names() {
  if [ "$ROLE" = "exit" ]; then
    echo "stack-provision stack-direct stack-dns stack-warp stack-telemetry"
  elif [ "$CASCADE_ENABLED" = "1" ]; then
    echo "stack-provision stack-direct stack-bypass stack-warp stack-telemetry"
  else
    echo "stack-provision stack-direct stack-bypass stack-dns stack-warp stack-telemetry"
  fi
}

ensure_cascade_keys() {
  local data="$1"
  mkdir -p "$data"
  if [ "$CASCADE_ENABLED" != "1" ] && [ "$ROLE" != "exit" ]; then
    return 0
  fi
  if ! docker image inspect stack-direct:latest >/dev/null 2>&1; then
    echo "NVPN_WARN|нет образа stack-direct — ключи каскада создаст контейнер"
    return 0
  fi
  if [ ! -s "$data/cascade.priv" ]; then
    docker run --rm --network none --entrypoint awg stack-direct genkey >"$data/cascade.priv"
    chmod 600 "$data/cascade.priv"
  fi
  docker run --rm --network none -i --entrypoint awg stack-direct pubkey <"$data/cascade.priv" >"$data/cascade.pub"
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
    echo "NVPN_CASCADE_PUBLIC_KEY|$pub"
  fi
}

cleanup_install_artifacts() {
  rm -f "$INSTALL_DIR/stack.tar.gz"
  rm -f /var/log/nvpn-build*.log /var/log/nvpn-install.log
  rm -rf /tmp/nvpn-data-bak "$INSTALL_DIR/stack.staging" "$INSTALL_DIR/stack.old"
  cleanup_stale_deploy_files
  if [ "$KEEP_INSTALL_LOG" = "1" ]; then
    mkdir -p "$INSTALL_DIR"
    tail -c 200000 "$LOG_FILE" >"$INSTALL_DIR/install.log" 2>/dev/null || true
  fi
  rm -f "$LOG_FILE"
}

on_exit() {
  local code=$?
  # Always try to drop temp archive / bak even on failure
  rm -f "$INSTALL_DIR/stack.tar.gz" 2>/dev/null || true
  rm -rf /tmp/nvpn-data-bak 2>/dev/null || true
  # Keep staging on failure for retry/debug; drop only on success via cleanup_install_artifacts.
  if [ "$code" -eq 0 ]; then
    cleanup_install_artifacts
  else
    restore_live_stack_if_needed
    if [ -f "$LOG_FILE" ]; then
      mkdir -p "$INSTALL_DIR"
      cp -f "$LOG_FILE" "$INSTALL_DIR/install.log" 2>/dev/null || true
    fi
    rm -f "$LOG_FILE" 2>/dev/null || true
  fi
  exit "$code"
}
trap on_exit EXIT

exec > >(tee -a "$LOG_FILE") 2>&1

prog() { echo "NVPN_PROGRESS|$1|$2"; }
die() { echo "NVPN_ERROR|$*"; exit 1; }

mem_total_mb() {
  awk '/MemTotal:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0
}

swap_total_mb() {
  awk '/SwapTotal:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0
}

# True if something is already listening on TCP $1 (IPv4/IPv6).
tcp_listen_port() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | awk -v p=":${port}" '
      $4 == p || $4 ~ (p "$") { found=1 }
      END { exit !found }
    '
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lnt 2>/dev/null | grep -Eq "[.:]${port}[[:space:]]"
  else
    return 1
  fi
}

load_stack_env() {
  local envf="$1"
  [ -f "$envf" ] || return 0
  set -a
  # shellcheck disable=SC1090
  . "$envf"
  set +a
}

ensure_swap() {
  local need_mb="$1"
  local have_mb file_mb avail_mb used_swap
  have_mb="$(swap_total_mb)"
  file_mb=0
  [ -f /swapfile ] && file_mb="$(du -m /swapfile 2>/dev/null | awk '{print $1}')"
  avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
  used_swap="$(awk '/SwapTotal:/{t=$2} /SwapFree:/{f=$2} END{printf "%d", (t-f)/1024}' /proc/meminfo 2>/dev/null || echo 0)"
  # A 2G swapfile on an 8G rootfs is leftover from the first install. Shrink it
  # when the disk is tight and almost none of that swap is actually in use.
  if [ -f /swapfile ] && [ "${file_mb:-0}" -gt $((need_mb + 96)) ] &&
     [ "${avail_mb:-0}" -lt 2200 ] && [ "${used_swap:-0}" -lt 400 ]; then
    echo "NVPN_INFO|сжимаем swapfile ${file_mb} → ${need_mb} МБ (свободно ${avail_mb} МБ)"
    swapoff /swapfile 2>/dev/null || true
    rm -f /swapfile
    have_mb="$(swap_total_mb)"
    avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
  fi
  # /proc reports ~2047 for a 2G file — allow a small slack so we don't recreate.
  local min_ok=$((need_mb - 64))
  if [ "$min_ok" -lt 512 ]; then min_ok=512; fi
  if [ "${have_mb:-0}" -ge "$min_ok" ] 2>/dev/null; then
    echo "NVPN_INFO|swap уже ${have_mb} МБ (цель ≥${need_mb})"
    return 0
  fi
  avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
  # Leave room for Docker images; never fill the rootfs with a swapfile.
  local reserve_mb="${MIN_DISK_UPDATE_MB:-500}"
  local max_swap=$(( ${avail_mb:-0} - reserve_mb ))
  if [ "$max_swap" -lt 512 ]; then
    echo "NVPN_WARN|мало места для swap (свободно ${avail_mb:-0} МБ, нужно оставить ≥${reserve_mb}) — без увеличения"
    return 0
  fi
  if [ "$need_mb" -gt "$max_swap" ]; then
    echo "NVPN_WARN|swap цель ${need_mb} МБ урезана до ${max_swap} МБ (диск ${avail_mb} МБ)"
    need_mb="$max_swap"
  fi
  prog 0.32 "Увеличение swap до ${need_mb} МБ (сейчас ${have_mb:-0})"
  local swapfile="/swapfile"
  if [ -f "$swapfile" ]; then
    swapoff "$swapfile" 2>/dev/null || true
  fi
  rm -f "$swapfile"
  if ! fallocate -l "${need_mb}M" "$swapfile" 2>/dev/null; then
    if ! dd if=/dev/zero of="$swapfile" bs=1M count="$need_mb" status=none; then
      echo "NVPN_WARN|не удалось создать swapfile — продолжаем без swap"
      rm -f "$swapfile"
      return 0
    fi
  fi
  chmod 600 "$swapfile"
  if ! mkswap "$swapfile" >/dev/null 2>&1 || ! swapon "$swapfile" 2>/dev/null; then
    echo "NVPN_WARN|не удалось включить swap — продолжаем без него"
    rm -f "$swapfile"
    return 0
  fi
  if ! grep -qE "^/swapfile[[:space:]]" /etc/fstab 2>/dev/null; then
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
  fi
  have_mb="$(swap_total_mb)"
  echo "NVPN_INFO|swap теперь ${have_mb} МБ"
}

stop_stack() {
  local dir="$1"
  if [ -f "$dir/docker-compose.yml" ]; then
    (cd "$dir" && COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose down) 2>/dev/null || \
      (cd "$dir" && COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker-compose down) 2>/dev/null || \
      docker rm -f nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry nvpn-cascade >/dev/null 2>&1 || true
  fi
}

PROG_ROLE="вход (клиенты)"
[ "$ROLE" = "exit" ] && PROG_ROLE="выход (WARP)"
[ "$CASCADE_ENABLED" = "1" ] && [ "$ROLE" = "entry" ] && PROG_ROLE="вход + каскад на ${CASCADE_PEER_ENDPOINT:-?}"
prog 0.05 "Проверка прав · ${PROG_ROLE}"
if [ "${NVPN_SKIP_ROOT_CHECK:-0}" != "1" ] && [ "$(id -u)" -ne 0 ]; then
  die "Нужен root (или запуск через sudo)"
fi

[ -n "$PUBLIC_HOST" ] || die "NVPN_PUBLIC_HOST не задан"
if [ "$ROLE" != "entry" ] && [ "$ROLE" != "exit" ]; then
  die "NVPN_ROLE должен быть entry или exit"
fi

prog 0.10 "Подготовка каталога $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"
cleanup_stale_deploy_files

STAGING="$INSTALL_DIR/stack.staging"
if [ -f "$INSTALL_DIR/stack.tar.gz" ]; then
  # Unpack into staging while the OLD stack (if any) keeps running.
  prog 0.15 "Распаковка стека в staging (старый стек не останавливаем)"
  rm -rf "$STAGING"
  mkdir -p "$STAGING"
  tar -xzf "$INSTALL_DIR/stack.tar.gz" -C "$STAGING"
  rm -f "$INSTALL_DIR/stack.tar.gz"
elif [ -f "$INSTALL_DIR/stack/docker-compose.yml" ]; then
  # Tar is deleted on every previous run. Allow a re-run against the already
  # unpacked tree so a failed compose build is recoverable without re-upload.
  prog 0.15 "Архив не найден — используем уже распакованный стек"
  rm -rf "$STAGING"
  cp -a "$INSTALL_DIR/stack" "$STAGING"
else
  die "Не найден $INSTALL_DIR/stack.tar.gz"
fi

[ -f "$STAGING/docker-compose.yml" ] || die "В архиве нет docker-compose.yml"

prog 0.22 "Проверка состава стека"
missing_contexts=""
for context in provision direct bypass dns warp telemetry-upload; do
  if grep -Eq "build:[[:space:]]*(\\./)?${context}([[:space:]]|$)" "$STAGING/docker-compose.yml" 2>/dev/null &&
     [ ! -d "$STAGING/$context" ]; then
    missing_contexts="$missing_contexts $context"
  fi
done
if [ -n "$missing_contexts" ]; then
  die "Неполный архив деплоя, отсутствуют каталоги:${missing_contexts}. Обновите APK или пересоберите архив scripts/pack-deploy-assets.sh"
fi

if [ "${NVPN_DRY_RUN:-0}" != "1" ]; then
  prog 0.25 "Установка Docker (если нужно)"
  if ! command -v docker >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -y
      apt-get install -y --no-install-recommends ca-certificates curl gnupg
      curl -fsSL https://get.docker.com | sh
    elif command -v dnf >/dev/null 2>&1; then
      dnf -y install docker docker-compose-plugin || curl -fsSL https://get.docker.com | sh
    else
      curl -fsSL https://get.docker.com | sh
    fi
    systemctl enable --now docker || service docker start || true
    cleanup_host_packages
  fi

  if ! docker compose version >/dev/null 2>&1; then
    prog 0.27 "Установка плагина docker compose v2"
    if command -v apt-get >/dev/null 2>&1; then
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -y || true
      apt-get install -y --no-install-recommends docker-compose-plugin || true
    elif command -v dnf >/dev/null 2>&1; then
      dnf -y install docker-compose-plugin || true
    fi
    if ! docker compose version >/dev/null 2>&1; then
      mkdir -p /usr/local/lib/docker/cli-plugins /usr/lib/docker/cli-plugins /root/.docker/cli-plugins
      arch="$(uname -m)"
      case "$arch" in
        x86_64|amd64) compose_arch="x86_64" ;;
        aarch64|arm64) compose_arch="aarch64" ;;
        *) compose_arch="$arch" ;;
      esac
      curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${compose_arch}" -o /usr/local/lib/docker/cli-plugins/docker-compose 2>/dev/null || true
      chmod +x /usr/local/lib/docker/cli-plugins/docker-compose 2>/dev/null || true
      cp -f /usr/local/lib/docker/cli-plugins/docker-compose /usr/lib/docker/cli-plugins/docker-compose 2>/dev/null || true
    fi
    cleanup_host_packages
  fi

  if ! docker compose version >/dev/null 2>&1; then
    if docker-compose version >/dev/null 2>&1; then
      compose() {
        local extra=()
        [ -f .env ] && extra+=(--env-file .env)
        COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker-compose "${extra[@]}" "$@"
      }
    else
      die "docker compose недоступен"
    fi
  else
    compose() {
      local extra=()
      [ -f .env ] && extra+=(--env-file .env)
      COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose "${extra[@]}" "$@"
    }
  fi

  prog 0.28 "Подготовка: очистка кэша Docker"
  reclaim_disk
  echo "NVPN_INFO|кэш сборки сброшен, свободно $(df -Pm / 2>/dev/null | awk 'NR==2{print $4}') МБ, RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo '?') МБ"
  mem_mb="$(mem_total_mb)"
  if [ "${mem_mb:-0}" -lt 1800 ] 2>/dev/null; then
    ensure_swap "$(swap_target_mb)"
  fi
fi

prog 0.40 "Запись .env (staging)"
DEPLOY_VERSION="${NVPN_DEPLOY_VERSION:-}"
if [ -z "$DEPLOY_VERSION" ] && [ -f "$STAGING/DEPLOY_VERSION" ]; then
  DEPLOY_VERSION="$(tr -d '[:space:]' < "$STAGING/DEPLOY_VERSION")"
fi
if [ -z "$DEPLOY_VERSION" ] && [ -f "$INSTALL_DIR/DEPLOY_VERSION" ]; then
  DEPLOY_VERSION="$(tr -d '[:space:]' < "$INSTALL_DIR/DEPLOY_VERSION")"
fi
[ -n "$DEPLOY_VERSION" ] || DEPLOY_VERSION="unknown"
printf '%s\n' "$DEPLOY_VERSION" > "$INSTALL_DIR/DEPLOY_VERSION"
printf '%s\n' "$DEPLOY_VERSION" > "$STAGING/DEPLOY_VERSION"
BYPASS_DNS="10.9.0.1"
WARP_MODE="hideip"
WARP_DNS_IFACES="awg0 wdttraw0"
WARP_HIDEIP_URL=""
if [ "$CASCADE_ENABLED" = "1" ]; then
  [ -n "$CASCADE_DNS" ] || CASCADE_DNS="10.10.0.2"
  BYPASS_DNS="$CASCADE_DNS"
  WARP_MODE="passthrough"
else
  # Provision treats a non-empty NVPN_CASCADE_DNS as hop DNS even when
  # NVPN_CASCADE_ENABLED=0. Keep it blank on a standalone entry.
  CASCADE_DNS=""
fi
if [ "$ROLE" = "exit" ]; then
  WARP_MODE="exit-hideip"
  WARP_DNS_IFACES="cascade0"
  WARP_HIDEIP_URL="http://10.10.0.1:9100/v1/hide-ip-prefixes"
fi
cat > "$STAGING/.env" <<EOF
NVPN_PUBLIC_HOST=$PUBLIC_HOST
NVPN_DIRECT_PORT=$DIRECT_PORT
NVPN_BYPASS_PORT=$BYPASS_PORT
NVPN_PROVISION_LISTEN=$PROVISION_LISTEN
NVPN_DEPLOY_VERSION=$DEPLOY_VERSION
NVPN_WARP_GOMEMLIMIT=256MiB
TELEMETRY_LISTEN=0.0.0.0:${TELEMETRY_PORT}
NVPN_TELEMETRY_LISTEN=0.0.0.0:${TELEMETRY_PORT}
NVPN_TELEMETRY_PORT=${TELEMETRY_PORT}
NVPN_ROLE=$ROLE
NVPN_CASCADE_ENABLED=$CASCADE_ENABLED
NVPN_CASCADE_LISTEN_PORT=$CASCADE_LISTEN_PORT
NVPN_CASCADE_PEER_ENDPOINT=$CASCADE_PEER_ENDPOINT
NVPN_CASCADE_PEER_PUBLIC_KEY=$CASCADE_PEER_PUBLIC_KEY
NVPN_CASCADE_DNS=$CASCADE_DNS
NVPN_BYPASS_DNS=$BYPASS_DNS
NVPN_WARP_MODE=$WARP_MODE
NVPN_WARP_DNS_IIFACES="$WARP_DNS_IFACES"
NVPN_WARP_HIDEIP_URL=$WARP_HIDEIP_URL
COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT
EOF
load_stack_env "$STAGING/.env"

mkdir -p "$STAGING/data"
printf '%s\n' "$DEPLOY_VERSION" > "$STAGING/data/DEPLOY_VERSION"
chmod 700 "$STAGING/data"

if [ "${NVPN_DRY_RUN:-0}" = "1" ]; then
  STACK="$INSTALL_DIR/stack"
  if [ -d "$STACK/data" ]; then
    mkdir -p "$STAGING/data"
    cp -a "$STACK/data/." "$STAGING/data/" || true
  fi
  rm -rf "$INSTALL_DIR/stack.old"
  if [ -d "$STACK" ]; then
    mv "$STACK" "$INSTALL_DIR/stack.old"
  fi
  mv "$STAGING" "$STACK"
  STAGING=""
  rm -rf "$INSTALL_DIR/stack.old"
  prog 1.00 "dry-run: стек подготовлен"
  echo "NVPN_DONE|dry_run=1|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$DEPLOY_VERSION"
  exit 0
fi

prog 0.45 "Проверка места перед сборкой образов"
reclaim_disk
echo "NVPN_INFO|перед сборкой свободно $(df -Pm / 2>/dev/null | awk 'NR==2{print $4}') МБ, RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo '?') МБ"

avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
need_mb="$(disk_need_mb)"
echo "NVPN_INFO|диск: свободно ${avail_mb:-?} МБ, порог обновления ${need_mb} МБ"
if [ -n "${avail_mb:-}" ] && [ "$avail_mb" -lt "$need_mb" ] 2>/dev/null; then
  die "Мало места на диске VPS: свободно ${avail_mb} МБ (нужно ≥${need_mb} МБ). Увеличьте диск или очистите: docker builder prune -af && apt-get clean"
fi
if [ -n "${avail_mb:-}" ] && [ "$avail_mb" -lt "$MIN_DISK_MB" ] 2>/dev/null; then
  echo "NVPN_WARN|на диске ${avail_mb} МБ — пропускаем compose pull, собираем поверх существующих образов"
fi

mem_mb="$(mem_total_mb)"
if [ "${mem_mb:-0}" -lt 1800 ] 2>/dev/null; then
  prog 0.48 "Мало RAM — останавливаем стек и сбрасываем BuildKit"
  if [ -d "$INSTALL_DIR/stack" ]; then
    stop_stack "$INSTALL_DIR/stack"
    STACK_STOPPED_FOR_BUILD=1
  fi
  reset_docker_buildkit
  sync
  echo 3 >/proc/sys/vm/drop_caches 2>/dev/null || true
  echo "NVPN_INFO|после сброса RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo '?') МБ"
  prog 0.50 "Сборка образов"
else
  prog 0.48 "Очистка кэша сборки Docker"
  docker builder prune -af >/dev/null 2>&1 || true
  docker buildx prune -af >/dev/null 2>&1 || true
  prog 0.50 "Сборка образов (старый стек ещё работает)"
fi
cd "$STAGING"
export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-1}"
export BUILDKIT_PROGRESS=plain
export COMPOSE_ANSI=never
# Cap parallel Go/cgo work on tiny VPS (~1 GiB RAM).
export GOMAXPROCS="${GOMAXPROCS:-1}"
export DOCKER_BUILDKIT=1

if [ -z "${avail_mb:-}" ] || [ "$avail_mb" -ge "$MIN_DISK_MB" ] 2>/dev/null; then
  compose pull 2>/dev/null || true
else
  echo "NVPN_INFO|compose pull пропущен (мало места)"
fi

# One service at a time. Do not builder-prune between images: that poisons the
# next golang Dockerfile (bypass after direct) on Docker 29 overlayfs.
# Never prune unused tagged images here: the new :latest is not used by a
# container yet, so a full prune would delete the image we just built.
export BUILDKIT_MAX_PARALLELISM="${BUILDKIT_MAX_PARALLELISM:-1}"

BUILD_SERVICES="$(role_build_services)"
BUILD_LOG="$(mktemp /tmp/nvpn-compose-build.XXXXXX.log)"
svc_i=0
svc_n=$(echo "$BUILD_SERVICES" | wc -w | tr -d ' ')
for svc in $BUILD_SERVICES; do
  svc_i=$((svc_i + 1))
  # Progress 0.50 → 0.72 across sequential builds
  frac="$(awk -v i="$svc_i" -v n="$svc_n" 'BEGIN { printf "%.2f", 0.50 + (0.22 * i / n) }')"
  prog "$frac" "Сборка $svc ($svc_i/$svc_n)"
  build_ok=0
  if compose -f "$STAGING/docker-compose.yml" --project-directory "$STAGING" build "$svc" 2>&1 | tee -a "$BUILD_LOG"; then
    build_ok=1
  else
    echo "NVPN_WARN|сборка $svc не удалась — сброс BuildKit и повтор без кэша"
    reset_docker_buildkit
    if compose -f "$STAGING/docker-compose.yml" --project-directory "$STAGING" build --no-cache "$svc" 2>&1 | tee -a "$BUILD_LOG"; then
      build_ok=1
    fi
  fi
  if [ "$build_ok" != 1 ]; then
    build_tail="$(tail -n 20 "$BUILD_LOG" | tr '\n' ' ' | cut -c1-1000)"
    rm -f "$BUILD_LOG"
    cleanup_docker_build_junk
    die "Сборка Docker ($svc) не удалась: ${build_tail:-причина не определена}. Свободно: $(df -h / | awk 'NR==2{print $4}'), RAM: $(free -h | awk '/Mem:/{print $7}') avail"
  fi
  prepare_docker_build
  echo "NVPN_INFO|после $svc свободно $(df -Pm / | awk 'NR==2{print $4}') МБ, RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo) МБ"
done
rm -f "$BUILD_LOG"

# Switchover: stop the old stack if it is still up, then promote staging → stack.
STACK="$INSTALL_DIR/stack"
prog 0.74 "Остановка старого стека и переключение"
# Bind mounts pin the data directory inode. Never rm -rf stack while containers
# are up (hide-ip then returns 404: users.json.tmp: no such file or directory).
if [ -d "$STACK" ]; then
  stop_stack "$STACK"
fi

# Preserve live data from the previous stack.
if [ -d "$STACK/data" ]; then
  rm -rf /tmp/nvpn-data-bak
  cp -a "$STACK/data" /tmp/nvpn-data-bak
fi

rm -rf "$INSTALL_DIR/stack.old"
if [ -d "$STACK" ]; then
  mv "$STACK" "$INSTALL_DIR/stack.old"
fi
mv "$STAGING" "$STACK"
STAGING=""

if [ -d /tmp/nvpn-data-bak ]; then
  mkdir -p "$STACK/data"
  cp -a /tmp/nvpn-data-bak/. "$STACK/data/"
  rm -rf /tmp/nvpn-data-bak
fi
# Carry .env we wrote in staging (already inside $STACK after mv).
chmod 700 "$STACK/data" 2>/dev/null || true

if [ "$CASCADE_ENABLED" = "1" ] || [ "$ROLE" = "exit" ]; then
  prog 0.76 "Ключи каскадного AWG"
  ensure_cascade_keys "$STACK/data"
fi

# Remove only legacy/unmanaged nvpn containers. Compose-managed containers are
# left intact and will be recreated normally.
for managed_name in nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry nvpn-cascade; do
  if docker inspect "$managed_name" >/dev/null 2>&1; then
    compose_project="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$managed_name" 2>/dev/null || true)"
    if [ -z "$compose_project" ] || [ "$compose_project" = "<no value>" ]; then
      echo "NVPN_WARN|Удаляется устаревший unmanaged-контейнер $managed_name"
      docker rm -f "$managed_name" >/dev/null
    fi
  fi
done

prog 0.78 "Запуск Compose"
cd "$STACK"
load_stack_env "$STACK/.env"
UP_SERVICES="$(role_up_services)"
if tcp_listen_port "$TELEMETRY_PORT"; then
  echo "NVPN_WARN|порт telemetry :${TELEMETRY_PORT} уже занят — nvpn-telemetry не запускаем. Освободите порт или задайте NVPN_TELEMETRY_PORT"
  UP_SERVICES="$(echo "$UP_SERVICES" | sed 's/ telemetry//')"
fi
UP_LOG="$(mktemp /tmp/nvpn-compose-up.XXXXXX.log)"
if ! compose -f "$STACK/docker-compose.yml" --project-directory "$STACK" up -d $UP_SERVICES 2>&1 | tee "$UP_LOG"; then
  up_tail="$(tail -n 20 "$UP_LOG" | tr '\n' ' ' | cut -c1-1000)"
  rm -f "$UP_LOG"
  die "Запуск Compose не удался: ${up_tail:-причина не определена}"
fi
rm -f "$UP_LOG"
rm -rf "$INSTALL_DIR/stack.old"
STACK_STOPPED_FOR_BUILD=0

prog 0.80 "Очистка build-кэша и временных файлов"
cleanup_docker_build_junk
cleanup_host_packages

prog 0.85 "Проверка health"
sleep 3
if curl -fsS "http://127.0.0.1:9100/health" >/dev/null 2>&1; then
  prog 0.92 "provision /health OK"
else
  echo "NVPN_WARN|provision /health пока не ответил — проверьте: docker compose -f $STACK/docker-compose.yml logs"
fi
if echo "$UP_SERVICES" | grep -qw telemetry; then
  if curl -fsS --max-time 3 "http://127.0.0.1:${TELEMETRY_PORT}/health" >/dev/null 2>&1; then
    prog 0.93 "telemetry /health OK"
  else
    echo "NVPN_WARN|telemetry :${TELEMETRY_PORT} не отвечает — логи тестирования не примут. cd $STACK && docker compose --env-file .env up -d --no-deps telemetry"
  fi
fi
if echo "$UP_SERVICES" | grep -qw warp; then
  if docker inspect -f '{{.State.Running}}' nvpn-warp 2>/dev/null | grep -qx true; then
    prog 0.935 "nvpn-warp running"
  else
    echo "NVPN_WARN|nvpn-warp не запущен — Hide-IP на этом хосте не применится. cd $STACK && docker compose --env-file .env up -d --no-deps --build warp"
  fi
fi
if docker exec nvpn-provision test -s /data/users.json 2>/dev/null; then
  prog 0.94 "provision видит /data/users.json"
else
  echo "NVPN_WARN|provision не видит /data/users.json — контейнер, скорее всего, на старом inode. Выполните: cd $STACK && docker compose up -d --force-recreate"
fi

prog 0.96 "Открытие портов (best-effort)"
if [ "$ROLE" = "exit" ]; then
  if command -v ufw >/dev/null 2>&1; then
    ufw allow "${CASCADE_LISTEN_PORT}/udp" || true
    ufw allow 9100/tcp || true
    ufw allow "${TELEMETRY_PORT}/tcp" || true
  fi
  if command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --add-port="${CASCADE_LISTEN_PORT}/udp" --permanent || true
    firewall-cmd --add-port=9100/tcp --permanent || true
    firewall-cmd --add-port="${TELEMETRY_PORT}/tcp" --permanent || true
    firewall-cmd --reload || true
  fi
  if command -v iptables >/dev/null 2>&1; then
    iptables -C INPUT -p udp --dport "$CASCADE_LISTEN_PORT" -j ACCEPT 2>/dev/null || \
      iptables -I INPUT -p udp --dport "$CASCADE_LISTEN_PORT" -j ACCEPT || true
    iptables -C INPUT -p tcp --dport 9100 -j ACCEPT 2>/dev/null || \
      iptables -I INPUT -p tcp --dport 9100 -j ACCEPT || true
    iptables -C INPUT -p tcp --dport "$TELEMETRY_PORT" -j ACCEPT 2>/dev/null || \
      iptables -I INPUT -p tcp --dport "$TELEMETRY_PORT" -j ACCEPT || true
  fi
else
if command -v ufw >/dev/null 2>&1; then
  ufw allow "${DIRECT_PORT}/udp" || true
  ufw allow "${BYPASS_PORT}/udp" || true
  ufw allow 9100/tcp || true
  ufw allow "${TELEMETRY_PORT}/tcp" || true
fi
if command -v firewall-cmd >/dev/null 2>&1; then
  firewall-cmd --add-port="${DIRECT_PORT}/udp" --permanent || true
  firewall-cmd --add-port="${BYPASS_PORT}/udp" --permanent || true
  firewall-cmd --add-port=9100/tcp --permanent || true
  firewall-cmd --add-port="${TELEMETRY_PORT}/tcp" --permanent || true
  firewall-cmd --reload || true
fi
if command -v iptables >/dev/null 2>&1; then
  iptables -C INPUT -p udp --dport "$DIRECT_PORT" -j ACCEPT 2>/dev/null || \
    iptables -I INPUT -p udp --dport "$DIRECT_PORT" -j ACCEPT || true
  iptables -C INPUT -p udp --dport "$BYPASS_PORT" -j ACCEPT 2>/dev/null || \
    iptables -I INPUT -p udp --dport "$BYPASS_PORT" -j ACCEPT || true
  iptables -C INPUT -p tcp --dport 9100 -j ACCEPT 2>/dev/null || \
    iptables -I INPUT -p tcp --dport 9100 -j ACCEPT || true
  iptables -C INPUT -p tcp --dport "$TELEMETRY_PORT" -j ACCEPT 2>/dev/null || \
    iptables -I INPUT -p tcp --dport "$TELEMETRY_PORT" -j ACCEPT || true
fi
fi

prog 1.00 "Готово"
echo "NVPN_DONE|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$DEPLOY_VERSION|telemetry_port=$TELEMETRY_PORT|role=$ROLE|cascade=$CASCADE_ENABLED"
echo "Создать пользователя: cd $STACK && docker compose exec provision provision -cmd create-user -name USER -data /data"
