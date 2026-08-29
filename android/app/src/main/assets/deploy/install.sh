#!/bin/bash
# ARDTT VPS installer — run on the server after stack.tar.gz is uploaded.
# Expects: /opt/nonamevpn/stack.tar.gz and this script.
# Leaves only: stack/ (compose + data), running images/containers. No build cache / apt junk.
set -euo pipefail

INSTALL_DIR="${NVPN_INSTALL_DIR:-/opt/nonamevpn}"
PUBLIC_HOST="${NVPN_PUBLIC_HOST:-}"
DIRECT_PORT="${NVPN_DIRECT_PORT:-51820}"
BYPASS_PORT="${NVPN_BYPASS_PORT:-56003}"
PROVISION_LISTEN="${NVPN_PROVISION_LISTEN:-0.0.0.0:9100}"
KEEP_INSTALL_LOG="${NVPN_KEEP_INSTALL_LOG:-0}"

LOG_FILE="$(mktemp /tmp/nvpn-install.XXXXXX.log)"

cleanup_host_packages() {
  if command -v apt-get >/dev/null 2>&1; then
    apt-get clean >/dev/null 2>&1 || true
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb 2>/dev/null || true
  fi
  if command -v dnf >/dev/null 2>&1; then
    dnf clean all >/dev/null 2>&1 || true
  fi
}

cleanup_docker_build_junk() {
  docker builder prune -af >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true
  docker volume prune -f >/dev/null 2>&1 || true
}

cleanup_install_artifacts() {
  rm -f "$INSTALL_DIR/stack.tar.gz"
  rm -f /var/log/nvpn-build*.log /var/log/nvpn-install.log
  rm -rf /tmp/nvpn-data-bak
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
  if [ "$code" -eq 0 ]; then
    cleanup_install_artifacts
  else
    # Keep log on failure for debugging
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

prog 0.05 "Проверка прав"
if [ "$(id -u)" -ne 0 ]; then
  die "Нужен root (или запуск через sudo)"
fi

[ -n "$PUBLIC_HOST" ] || die "NVPN_PUBLIC_HOST не задан"

prog 0.10 "Подготовка каталога $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

if [ -f "$INSTALL_DIR/stack.tar.gz" ]; then
  prog 0.15 "Распаковка стека"
  if [ -d "$INSTALL_DIR/stack/data" ]; then
    rm -rf /tmp/nvpn-data-bak
    cp -a "$INSTALL_DIR/stack/data" /tmp/nvpn-data-bak
  fi
  rm -rf "$INSTALL_DIR/stack"
  mkdir -p "$INSTALL_DIR/stack"
  tar -xzf "$INSTALL_DIR/stack.tar.gz" -C "$INSTALL_DIR/stack"
  if [ -d /tmp/nvpn-data-bak ]; then
    mkdir -p "$INSTALL_DIR/stack/data"
    cp -a /tmp/nvpn-data-bak/. "$INSTALL_DIR/stack/data/"
    rm -rf /tmp/nvpn-data-bak
  fi
  rm -f "$INSTALL_DIR/stack.tar.gz"
else
  die "Не найден $INSTALL_DIR/stack.tar.gz"
fi

STACK="$INSTALL_DIR/stack"
[ -f "$STACK/docker-compose.yml" ] || die "В архиве нет docker-compose.yml"

prog 0.22 "Проверка состава стека"
missing_contexts=""
for context in provision direct bypass dns warp telemetry-upload; do
  if grep -Eq "build:[[:space:]]*(\\./)?${context}([[:space:]]|$)" "$STACK/docker-compose.yml" 2>/dev/null &&
     [ ! -d "$STACK/$context" ]; then
    missing_contexts="$missing_contexts $context"
  fi
done
if [ -n "$missing_contexts" ]; then
  die "Неполный архив деплоя, отсутствуют каталоги:${missing_contexts}. Обновите APK или пересоберите архив scripts/pack-deploy-assets.sh"
fi

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
  if docker-compose version >/dev/null 2>&1; then
    compose() { docker-compose "$@"; }
  else
    die "docker compose недоступен"
  fi
else
  compose() { docker compose "$@"; }
fi

prog 0.40 "Запись .env"
DEPLOY_VERSION="${NVPN_DEPLOY_VERSION:-}"
if [ -z "$DEPLOY_VERSION" ] && [ -f "$STACK/DEPLOY_VERSION" ]; then
  DEPLOY_VERSION="$(tr -d '[:space:]' < "$STACK/DEPLOY_VERSION")"
fi
if [ -z "$DEPLOY_VERSION" ] && [ -f "$INSTALL_DIR/DEPLOY_VERSION" ]; then
  DEPLOY_VERSION="$(tr -d '[:space:]' < "$INSTALL_DIR/DEPLOY_VERSION")"
fi
[ -n "$DEPLOY_VERSION" ] || DEPLOY_VERSION="unknown"
# Persist on host so provision can read even if env is missing after recreate.
printf '%s\n' "$DEPLOY_VERSION" > "$INSTALL_DIR/DEPLOY_VERSION"
printf '%s\n' "$DEPLOY_VERSION" > "$STACK/DEPLOY_VERSION"
cat > "$STACK/.env" <<EOF
NVPN_PUBLIC_HOST=$PUBLIC_HOST
NVPN_DIRECT_PORT=$DIRECT_PORT
NVPN_BYPASS_PORT=$BYPASS_PORT
NVPN_PROVISION_LISTEN=$PROVISION_LISTEN
NVPN_DEPLOY_VERSION=$DEPLOY_VERSION
NVPN_WARP_GOMEMLIMIT=400MiB
EOF

mkdir -p "$STACK/data"
chmod 700 "$STACK/data"

prog 0.45 "Очистка места перед сборкой"
cleanup_docker_build_junk
cleanup_host_packages
# Drop unused images from previous deploys (keep running containers).
docker image prune -af >/dev/null 2>&1 || true

avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
if [ -n "${avail_mb:-}" ] && [ "$avail_mb" -lt 1800 ] 2>/dev/null; then
  die "Мало места на диске VPS: свободно ${avail_mb} МБ (нужно ≥1800 МБ). Увеличьте диск или очистите: docker system prune -af && apt-get clean"
fi

prog 0.50 "Сборка и запуск Compose (может занять несколько минут)"
cd "$STACK"
export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-1}"
# Plain progress — avoid fancy TTY banners in the app log.
export BUILDKIT_PROGRESS=plain
export COMPOSE_ANSI=never
compose pull 2>/dev/null || true
BUILD_LOG="$(mktemp /tmp/nvpn-compose-build.XXXXXX.log)"
if ! compose build 2>&1 | tee "$BUILD_LOG"; then
  build_tail="$(tail -n 20 "$BUILD_LOG" | tr '\n' ' ' | cut -c1-1000)"
  rm -f "$BUILD_LOG"
  cleanup_docker_build_junk
  die "Сборка Docker не удалась: ${build_tail:-причина не определена}. Свободно: $(df -h / | awk 'NR==2{print $4}')"
fi
rm -f "$BUILD_LOG"

# Remove only legacy/unmanaged nvpn containers. Compose-managed containers are
# left intact and will be recreated normally. This handles older manual
# telemetry installs that used the same fixed container_name without labels.
for managed_name in nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry; do
  if docker inspect "$managed_name" >/dev/null 2>&1; then
    compose_project="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$managed_name" 2>/dev/null || true)"
    if [ -z "$compose_project" ] || [ "$compose_project" = "<no value>" ]; then
      echo "NVPN_WARN|Удаляется устаревший unmanaged-контейнер $managed_name"
      docker rm -f "$managed_name" >/dev/null
    fi
  fi
done

UP_LOG="$(mktemp /tmp/nvpn-compose-up.XXXXXX.log)"
if ! compose up -d 2>&1 | tee "$UP_LOG"; then
  up_tail="$(tail -n 20 "$UP_LOG" | tr '\n' ' ' | cut -c1-1000)"
  rm -f "$UP_LOG"
  die "Запуск Compose не удался: ${up_tail:-причина не определена}"
fi
rm -f "$UP_LOG"

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

prog 0.96 "Открытие портов (best-effort)"
if command -v ufw >/dev/null 2>&1; then
  ufw allow "${DIRECT_PORT}/udp" || true
  ufw allow "${BYPASS_PORT}/udp" || true
  ufw allow 9100/tcp || true
fi
if command -v firewall-cmd >/dev/null 2>&1; then
  firewall-cmd --add-port="${DIRECT_PORT}/udp" --permanent || true
  firewall-cmd --add-port="${BYPASS_PORT}/udp" --permanent || true
  firewall-cmd --add-port=9100/tcp --permanent || true
  firewall-cmd --reload || true
fi

prog 1.00 "Готово"
echo "NVPN_DONE|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST"
echo "Создать пользователя: cd $STACK && docker compose exec provision provision -cmd create-user -name USER -data /data"
