#!/bin/bash
# nonameVPN VPS installer — run on the server after stack.tar.gz is uploaded.
# Expects: /opt/nonamevpn/stack.tar.gz and this script.
set -euo pipefail

INSTALL_DIR="${NVPN_INSTALL_DIR:-/opt/nonamevpn}"
PUBLIC_HOST="${NVPN_PUBLIC_HOST:-}"
DIRECT_PORT="${NVPN_DIRECT_PORT:-51820}"
BYPASS_PORT="${NVPN_BYPASS_PORT:-56003}"
PROVISION_LISTEN="${NVPN_PROVISION_LISTEN:-0.0.0.0:9100}"
LOG_FILE="${NVPN_LOG:-/var/log/nvpn-install.log}"

mkdir -p "$(dirname "$LOG_FILE")"
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
  rm -rf "$INSTALL_DIR/stack"
  mkdir -p "$INSTALL_DIR/stack"
  tar -xzf "$INSTALL_DIR/stack.tar.gz" -C "$INSTALL_DIR/stack"
else
  die "Не найден $INSTALL_DIR/stack.tar.gz"
fi

STACK="$INSTALL_DIR/stack"
[ -f "$STACK/docker-compose.yml" ] || die "В архиве нет docker-compose.yml"

prog 0.25 "Установка Docker (если нужно)"
if ! command -v docker >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y ca-certificates curl gnupg
    curl -fsSL https://get.docker.com | sh
  elif command -v dnf >/dev/null 2>&1; then
    dnf -y install docker docker-compose-plugin || curl -fsSL https://get.docker.com | sh
  else
    curl -fsSL https://get.docker.com | sh
  fi
  systemctl enable --now docker || service docker start || true
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
cat > "$STACK/.env" <<EOF
NVPN_PUBLIC_HOST=$PUBLIC_HOST
NVPN_DIRECT_PORT=$DIRECT_PORT
NVPN_BYPASS_PORT=$BYPASS_PORT
NVPN_PROVISION_LISTEN=$PROVISION_LISTEN
NVPN_WARP_GOMEMLIMIT=400MiB
EOF

mkdir -p "$STACK/data"
chmod 700 "$STACK/data"

prog 0.50 "Сборка и запуск Compose (может занять несколько минут)"
cd "$STACK"
compose pull 2>/dev/null || true
compose up -d --build

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
