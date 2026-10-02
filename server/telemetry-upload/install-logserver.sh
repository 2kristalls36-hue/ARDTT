#!/usr/bin/env bash
# ARDTT: установщик центрального сервера логов (приём логов вкладки «Тест»).
#
# Ставит на пустой Debian 11+ / Ubuntu 20.04+ приёмник server/telemetry-upload:
# gunicorn на 127.0.0.1:9199 за nginx с самоподписанным TLS-сертификатом,
# который приложение закрепляет у себя. Повторный запуск обновляет код и
# конфиги, но не трогает сертификат, токен разбора и накопленные логи.
#
# Запуск от root:
#   curl -fsSL https://raw.githubusercontent.com/2kristalls36-hue/ARDTT/refs/heads/main/server/telemetry-upload/install-logserver.sh \
#     | ARDTT_LOG_HOST=203.0.113.10 bash
#
# Переменные окружения:
#   ARDTT_REPO      owner/repo на GitHub (по умолчанию 2kristalls36-hue/ARDTT)
#   ARDTT_REF       ветка, тег или SHA: main | refs/heads/a/b | v0.5.271 | <sha> (по умолчанию main)
#   ARDTT_LOG_HOST  публичный адрес для сертификата (IPv4 или имя). Не задан —
#                   берётся из уже выпущенного сертификата, иначе определяется сам
#   ARDTT_LOG_PORT  порт HTTPS (по умолчанию 443)
#   ARDTT_ACTION    install (по умолчанию) | uninstall
#   ARDTT_PURGE=1   при uninstall удалить ещё логи, сертификат и токен
#   ARDTT_RAW_BASE  откуда качать файлы (по умолчанию https://raw.githubusercontent.com); для зеркал и тестов
#
# Если рядом лежат app.py и requirements.txt (запуск из клона репозитория),
# берутся они, а не GitHub.
set -Eeuo pipefail

readonly SERVICE_NAME="ardtt-logs"
readonly SERVICE_USER="ardtt-logs"
readonly APP_ROOT="/opt/ardtt-logs"
readonly APP_DIR="$APP_ROOT/app"
readonly VENV_DIR="$APP_ROOT/venv"
readonly LOG_ROOT="/var/logs/app"
readonly STATE_DIR="/var/lib/ardtt-logs"
readonly DATA_DIR="$STATE_DIR/data"
readonly CONF_DIR="/etc/ardtt-logs"
readonly TLS_DIR="$CONF_DIR/tls"
readonly TOKEN_FILE="$CONF_DIR/review.token"
readonly ENV_FILE="$CONF_DIR/env"
readonly UNIT_FILE="/etc/systemd/system/$SERVICE_NAME.service"
readonly BACKEND_PORT=9199
readonly PACKAGES=(python3 python3-venv nginx openssl curl ca-certificates iproute2)

# Значения по умолчанию лимитов; правки в /etc/ardtt-logs/env переживают повторный запуск.
readonly DEFAULT_MAX_UPLOAD_MB=20
readonly DEFAULT_QUOTA_MB=200
readonly DEFAULT_TOTAL_QUOTA_MB=2000

ARDTT_REPO="${ARDTT_REPO:-2kristalls36-hue/ARDTT}"
ARDTT_REF="${ARDTT_REF:-main}"
ARDTT_RAW_BASE="${ARDTT_RAW_BASE:-https://raw.githubusercontent.com}"
TMP_DIR=""
LOG_HOST="${ARDTT_LOG_HOST:-}"
LOG_PORT="${ARDTT_LOG_PORT:-443}"
REVIEW_TOKEN=""

# --- общие помощники ---------------------------------------------------------

log() { printf '[ardtt-logs] %s\n' "$*"; }
warn() { printf '[ardtt-logs] ВНИМАНИЕ: %s\n' "$*" >&2; }
die() {
  printf '[ardtt-logs] ОШИБКА: %s\n' "$*" >&2
  exit 1
}

on_error() {
  printf '[ardtt-logs] ОШИБКА: «%s» завершилась с кодом %s (строка %s). Повторный запуск безопасен.\n' "$3" "$1" "$2" >&2
}

cleanup() {
  if [[ -n "$TMP_DIR" ]]; then
    rm -rf "$TMP_DIR"
  fi
}

is_ipv4() {
  local octet='(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])'
  [[ "$1" =~ ^$octet\.$octet\.$octet\.$octet$ ]]
}

is_private_ipv4() {
  local a b
  IFS=. read -r a b _ _ <<<"$1"
  ((a == 0 || a == 10 || a == 127 || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)))
}

is_hostname() {
  local label='[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?'
  [[ ${#1} -le 64 && "$1" =~ ^$label(\.$label)*$ ]]
}

# --- проверки окружения -----------------------------------------------------

require_root() {
  if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    die "Нужны права root. Откройте root-shell (sudo -i) и повторите команду."
  fi
}

check_platform() {
  local os_id="" os_like="" os_ver="" os_name="" major minor
  if [[ -r /etc/os-release ]]; then
    eval "$(
      # shellcheck disable=SC1091
      . /etc/os-release
      printf 'os_id=%q os_like=%q os_ver=%q os_name=%q' "${ID:-}" "${ID_LIKE:-}" "${VERSION_ID:-}" "${PRETTY_NAME:-}"
    )"
  fi
  local unsupported="Установщик рассчитан на Debian 11+ / Ubuntu 20.04+ с apt и systemd под root.
Обнаружено: ${os_name:-неизвестная система}.
Нужно: apt-get, systemd, python3 (3.8+) с модулем venv, nginx, openssl, curl, ca-certificates
и исходящий доступ к raw.githubusercontent.com и pypi.org.
На другом дистрибутиве поставьте эти пакеты вручную и повторите то, что делает установщик
(раздел «Центральный сервер логов» в docs/TELEMETRY.md), либо возьмите чистый Debian/Ubuntu."
  case "$os_id" in
    debian)
      if [[ -n "$os_ver" ]]; then
        [[ "$os_ver" =~ ^[0-9]+ ]] || die "$unsupported"
        major="${BASH_REMATCH[0]}"
        ((major >= 11)) || die "$unsupported"
      fi
      ;;
    ubuntu)
      [[ "$os_ver" =~ ^([0-9]+)\.([0-9]+) ]] || die "$unsupported"
      major="${BASH_REMATCH[1]}"
      minor="${BASH_REMATCH[2]}"
      ((10#$major * 100 + 10#$minor >= 2004)) || die "$unsupported"
      ;;
    *)
      if [[ " $os_like " == *" debian "* || " $os_like " == *" ubuntu "* ]]; then
        warn "${os_name:-$os_id} основан на Debian/Ubuntu — продолжаю без гарантий."
      else
        die "$unsupported"
      fi
      ;;
  esac
  command -v apt-get >/dev/null 2>&1 || die "$unsupported"
  command -v systemctl >/dev/null 2>&1 && [[ -d /run/systemd/system ]] \
    || die "systemd не запущен (контейнер без init?). Нужна обычная VPS с systemd."
}

validate_params() {
  [[ "$ARDTT_REPO" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] \
    || die "ARDTT_REPO должен быть вида owner/repo, получено: $ARDTT_REPO"
  [[ "$ARDTT_RAW_BASE" =~ ^https?://[A-Za-z0-9._:/-]+$ ]] \
    || die "ARDTT_RAW_BASE должен быть вида https://хост[/путь], получено: $ARDTT_RAW_BASE"
  [[ "$ARDTT_REF" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ && "$ARDTT_REF" != *..* ]] \
    || die "ARDTT_REF: допустимы ветка, тег или SHA (main, refs/heads/a/b, v1.2.3), получено: $ARDTT_REF"
  if [[ ! "$LOG_PORT" =~ ^[0-9]{1,5}$ ]] || ((10#$LOG_PORT < 1 || 10#$LOG_PORT > 65535)); then
    die "ARDTT_LOG_PORT должен быть числом 1-65535, получено: $LOG_PORT"
  fi
  LOG_PORT=$((10#$LOG_PORT))
  ((LOG_PORT != BACKEND_PORT)) || die "Порт $BACKEND_PORT занят под внутренний gunicorn, выберите другой ARDTT_LOG_PORT."
}

validate_host() {
  LOG_HOST="${LOG_HOST,,}"
  if [[ "$LOG_HOST" == *:* || "$LOG_HOST" == */* ]]; then
    die "ARDTT_LOG_HOST — только адрес (IPv4 или имя), без схемы, порта и IPv6: $LOG_HOST"
  fi
  if is_ipv4 "$LOG_HOST"; then
    if is_private_ipv4 "$LOG_HOST"; then
      warn "$LOG_HOST — приватный адрес: телефоны из интернета до него не дойдут."
    fi
  elif ! is_hostname "$LOG_HOST"; then
    die "ARDTT_LOG_HOST не похож на IPv4 или имя хоста: $LOG_HOST"
  fi
}

# Публичный IP: src маршрута до 1.1.1.1; если он приватный (NAT) — спрашиваем api.ipify.org.
detect_host() {
  local local_ip="" ext=""
  if command -v ip >/dev/null 2>&1; then
    local_ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit }}' || true)"
  fi
  if ! is_ipv4 "$local_ip"; then
    local_ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  fi
  if is_ipv4 "$local_ip" && ! is_private_ipv4 "$local_ip"; then
    LOG_HOST="$local_ip"
    log "Адрес сервера: $LOG_HOST (определён по маршруту по умолчанию)"
    return 0
  fi
  ext="$(curl -4fsS --max-time 10 https://api.ipify.org 2>/dev/null || true)"
  if is_ipv4 "$ext"; then
    LOG_HOST="$ext"
    log "Адрес сервера: $LOG_HOST (локальный адрес ${local_ip:-не найден} приватный, внешний узнан через api.ipify.org)"
    return 0
  fi
  die "Не удалось определить публичный IP (локальный: ${local_ip:-нет}). Задайте его явно: ARDTT_LOG_HOST=<IP>."
}

cert_cn() {
  openssl x509 -in "$1" -noout -subject -nameopt RFC2253 2>/dev/null | sed -n 's/^subject=.*CN=\([^,]*\).*/\1/p' | head -n 1
}

resolve_host() {
  local existing=""
  if [[ -n "$LOG_HOST" ]]; then
    log "Адрес сервера: $LOG_HOST (задан ARDTT_LOG_HOST)"
  else
    if [[ -s "$TLS_DIR/cert.pem" ]]; then
      existing="$(cert_cn "$TLS_DIR/cert.pem" || true)"
    fi
    if [[ -n "$existing" ]]; then
      LOG_HOST="$existing"
      log "Адрес сервера: $LOG_HOST (из уже выпущенного сертификата)"
    else
      detect_host
    fi
  fi
  validate_host
}

# --- исходники ---------------------------------------------------------------

local_checkout_dir() {
  local src="${BASH_SOURCE[0]:-}" dir
  # При `curl | bash` bash подставляет в функциях BASH_SOURCE=main, а $0=bash: это не файл.
  [[ -n "$src" && "$src" == "$0" && -f "$src" ]] || return 1
  dir="$(cd "$(dirname "$src")" && pwd)"
  [[ -f "$dir/app.py" && -f "$dir/requirements.txt" ]] || return 1
  printf '%s\n' "$dir"
}

fetch_sources() {
  local dir name url
  TMP_DIR="$(mktemp -d)"
  if dir="$(local_checkout_dir)"; then
    log "Код приёмника: локальные файлы из $dir"
    for name in app.py requirements.txt; do
      cp "$dir/$name" "$TMP_DIR/$name"
    done
  else
    for name in app.py requirements.txt; do
      url="${ARDTT_RAW_BASE%/}/${ARDTT_REPO}/${ARDTT_REF}/server/telemetry-upload/${name}"
      log "Скачиваю $url"
      curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 15 --max-time 120 -o "$TMP_DIR/$name" "$url" \
        || die "Не удалось скачать $url. Проверьте ARDTT_REPO, ARDTT_REF и доступ к raw.githubusercontent.com."
    done
  fi
  grep -q 'TELEMETRY_UPLOAD_PUBLIC' "$TMP_DIR/app.py" \
    || die "app.py из ${ARDTT_REPO}@${ARDTT_REF} не знает TELEMETRY_UPLOAD_PUBLIC (загрузка без токена). Укажите ARDTT_REF со свежей веткой или тегом."
  grep -q '"/health"' "$TMP_DIR/app.py" \
    || die "Скачанный app.py не похож на приёмник логов ARDTT."
  grep -Eq '^flask==[0-9]' "$TMP_DIR/requirements.txt" \
    || die "В requirements.txt нет закреплённого flask."
  grep -Eq '^gunicorn==[0-9]' "$TMP_DIR/requirements.txt" \
    || die "В requirements.txt нет закреплённого gunicorn."
}

# --- система: пакеты, пользователь, каталоги, venv ---------------------------

ensure_packages() {
  local pkg missing=()
  for pkg in "${PACKAGES[@]}"; do
    # shellcheck disable=SC2016
    if [[ "$(dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null || true)" != "install ok installed" ]]; then
      missing+=("$pkg")
    fi
  done
  if ((${#missing[@]} == 0)); then
    log "Нужные пакеты уже установлены"
    return 0
  fi
  log "Ставлю пакеты: ${missing[*]}"
  export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a
  apt-get -o DPkg::Lock::Timeout=300 update -q || warn "apt-get update не удался, пробую со старым кэшем."
  apt-get -o DPkg::Lock::Timeout=300 install -y -q --no-install-recommends "${missing[@]}"
}

ensure_user() {
  local shell
  if id -u "$SERVICE_USER" >/dev/null 2>&1; then
    return 0
  fi
  shell="$(command -v nologin || true)"
  useradd --system --user-group --no-create-home --home-dir "$APP_ROOT" --shell "${shell:-/usr/sbin/nologin}" "$SERVICE_USER"
  log "Создан системный пользователь $SERVICE_USER"
}

ensure_dirs() {
  install -d -m 0755 -o root -g root "$APP_ROOT" "$APP_DIR" "$STATE_DIR" "$CONF_DIR" /var/logs
  install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$LOG_ROOT"
  install -d -m 0750 -o root -g "$SERVICE_USER" "$DATA_DIR"
  install -d -m 0750 -o root -g root "$TLS_DIR"
  # Логи, скопированные руками от root, сервис иначе не смог бы дописать.
  find "$LOG_ROOT" -xdev \( ! -user "$SERVICE_USER" -o ! -group "$SERVICE_USER" \) -exec chown "$SERVICE_USER:$SERVICE_USER" {} +
}

ensure_venv() {
  local py="$VENV_DIR/bin/python"
  if [[ -x "$py" ]] && "$py" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' 2>/dev/null; then
    log "Виртуальное окружение уже есть: $VENV_DIR"
  else
    log "Создаю виртуальное окружение: $VENV_DIR"
    rm -rf "$VENV_DIR"
    python3 -m venv "$VENV_DIR"
    "$py" -m pip install --disable-pip-version-check -q --upgrade pip
  fi
  log "Ставлю зависимости из requirements.txt (версии закреплены)"
  "$py" -m pip install --disable-pip-version-check -q -r "$TMP_DIR/requirements.txt"
  "$py" -m py_compile "$TMP_DIR/app.py" || die "app.py не компилируется этим Python."
}

install_app() {
  local sum
  install -m 0644 -o root -g root "$TMP_DIR/app.py" "$APP_DIR/app.py"
  install -m 0644 -o root -g root "$TMP_DIR/requirements.txt" "$APP_DIR/requirements.txt"
  sum="$(sha256sum "$APP_DIR/app.py" | cut -d' ' -f1)"
  printf 'repo=%s\nref=%s\napp_sha256=%s\ninstalled_at=%s\n' \
    "$ARDTT_REPO" "$ARDTT_REF" "$sum" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$APP_DIR/INSTALLED_FROM"
  chmod 0644 "$APP_DIR/INSTALLED_FROM"
  # Код и venv принадлежат root и читаются сервисом; при чужом umask (0, 077) это иначе не так.
  chmod -R u+rwX,go+rX,go-w "$APP_ROOT"
  log "Код приёмника обновлён (app.py sha256 ${sum:0:12}…)"
}

# --- секреты и конфигурация сервиса -----------------------------------------

ensure_token() {
  if [[ ! -s "$TOKEN_FILE" ]]; then
    (
      umask 077
      openssl rand -hex 32 >"$TOKEN_FILE.new"
    )
    mv -f "$TOKEN_FILE.new" "$TOKEN_FILE"
    log "Создан токен разбора: $TOKEN_FILE"
  fi
  chown root:root "$TOKEN_FILE"
  chmod 0600 "$TOKEN_FILE"
  REVIEW_TOKEN="$(tr -d '[:space:]' <"$TOKEN_FILE")"
  [[ "$REVIEW_TOKEN" =~ ^[A-Za-z0-9._~-]{16,}$ ]] \
    || die "$TOKEN_FILE повреждён: токен должен состоять из 16+ символов A-Z a-z 0-9 . _ ~ -"
}

# Значение числового лимита из текущего env-файла (чтобы повторный запуск не сбрасывал правки).
env_number() {
  local key="$1" default="$2" value=""
  if [[ -f "$ENV_FILE" ]]; then
    value="$(sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1)"
  fi
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '%s' "$value"
  else
    printf '%s' "$default"
  fi
}

write_env_file() {
  local tmp max quota total
  max="$(env_number TELEMETRY_MAX_UPLOAD_MB "$DEFAULT_MAX_UPLOAD_MB")"
  quota="$(env_number TELEMETRY_QUOTA_MB "$DEFAULT_QUOTA_MB")"
  total="$(env_number TELEMETRY_TOTAL_QUOTA_MB "$DEFAULT_TOTAL_QUOTA_MB")"
  tmp="$(mktemp "$CONF_DIR/env.XXXXXX")"
  cat >"$tmp" <<EOF
# Создано install-logserver.sh. Лимиты (*_MB) можно править: при повторном запуске они сохраняются.
# После правки: systemctl restart $SERVICE_NAME
TELEMETRY_REVIEW_TOKEN=$REVIEW_TOKEN
TELEMETRY_UPLOAD_PUBLIC=1
TELEMETRY_LOG_ROOT=$LOG_ROOT
ARDTT_DATA=$DATA_DIR
TELEMETRY_MAX_UPLOAD_MB=$max
TELEMETRY_QUOTA_MB=$quota
TELEMETRY_TOTAL_QUOTA_MB=$total
EOF
  chown "root:$SERVICE_USER" "$tmp"
  chmod 0640 "$tmp"
  mv -f "$tmp" "$ENV_FILE"
}

# --- TLS ---------------------------------------------------------------------

cert_san() { # host -> строка subjectAltName
  local host="$1" san="IP:127.0.0.1,DNS:localhost"
  if [[ "$host" == "127.0.0.1" ]]; then
    printf '%s' "$san"
  elif is_ipv4 "$host"; then
    printf 'IP:%s,%s' "$host" "$san"
  else
    printf 'DNS:%s,%s' "$host" "$san"
  fi
}

# Профиль прежнего сертификата: RSA 2048, самоподписанный, CA:TRUE, 10 лет.
render_openssl_cnf() { # host
  cat <<EOF
[req]
distinguished_name = dn
x509_extensions = v3
prompt = no
default_md = sha256

[dn]
CN = $1

[v3]
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
basicConstraints = critical,CA:TRUE
subjectAltName = $(cert_san "$1")
EOF
}

generate_cert() { # host dir: пишет dir/key.pem и dir/cert.pem
  local host="$1" dir="$2" cnf out
  cnf="$(mktemp)"
  render_openssl_cnf "$host" >"$cnf"
  if ! out="$(
    umask 077
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$cnf" \
      -keyout "$dir/key.pem" -out "$dir/cert.pem" 2>&1
  )"; then
    rm -f "$cnf"
    die "openssl не смог выпустить сертификат: $out"
  fi
  rm -f "$cnf"
}

cert_has_host() { # cert host
  local san
  san="$(openssl x509 -in "$1" -noout -text 2>/dev/null | sed -n '/Subject Alternative Name/{n;p;}' | tr -d ' ')"
  [[ ",$san," == *",IPAddress:$2,"* || ",$san," == *",DNS:$2,"* ]]
}

cert_key_match() { # cert key
  [[ "$(openssl x509 -in "$1" -noout -pubkey 2>/dev/null)" == "$(openssl pkey -in "$2" -pubout 2>/dev/null)" ]]
}

ensure_tls() {
  local cert="$TLS_DIR/cert.pem" key="$TLS_DIR/key.pem" stage
  if [[ -s "$cert" && ! -s "$key" ]]; then
    die "В $TLS_DIR есть cert.pem, но нет key.pem. Сертификат уже закреплён в приложении, поэтому
автоматически я его не перевыпускаю. Восстановите key.pem или удалите оба файла и запустите снова
(тогда новый сертификат придётся заново зашить в приложение)."
  fi
  if [[ -s "$cert" && -s "$key" ]]; then
    cert_key_match "$cert" "$key" || die "cert.pem и key.pem в $TLS_DIR не составляют пару."
    log "Сертификат уже выпущен — оставляю как есть (приложение закрепляет именно его)"
    if ! cert_has_host "$cert" "$LOG_HOST"; then
      warn "В сертификате нет адреса $LOG_HOST (есть: $(cert_cn "$cert")). Приложение к $LOG_HOST не подключится, пока сертификат не перевыпущен."
    fi
    if ! openssl x509 -in "$cert" -noout -checkend 7776000 >/dev/null; then
      warn "Сертификат истекает меньше чем через 90 дней."
    fi
  else
    log "Выпускаю самоподписанный сертификат на 10 лет для $LOG_HOST"
    stage="$(mktemp -d "$TLS_DIR/.new.XXXXXX")"
    generate_cert "$LOG_HOST" "$stage"
    chown root:root "$stage/key.pem" "$stage/cert.pem"
    chmod 0600 "$stage/key.pem"
    chmod 0644 "$stage/cert.pem"
    # Сначала ключ, потом сертификат: cert.pem без key.pem означает «уже опубликован».
    mv -f "$stage/key.pem" "$key"
    mv -f "$stage/cert.pem" "$cert"
    rmdir "$stage"
  fi
  chown root:root "$key" "$cert"
  chmod 0600 "$key"
  chmod 0644 "$cert"
}

# --- systemd -----------------------------------------------------------------

render_unit() {
  cat <<EOF
[Unit]
Description=ARDTT log collection server (gunicorn)
After=network.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENV_FILE
Environment=PYTHONDONTWRITEBYTECODE=1
Environment=PYTHONUNBUFFERED=1
ExecStart=$VENV_DIR/bin/gunicorn --bind 127.0.0.1:$BACKEND_PORT --workers 2 --timeout 120 app:app
Restart=always
RestartSec=3
UMask=0027
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$LOG_ROOT
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
CapabilityBoundingSet=

[Install]
WantedBy=multi-user.target
EOF
}

install_unit() {
  local tmp
  tmp="$(mktemp)"
  render_unit >"$tmp"
  install -m 0644 -o root -g root "$tmp" "$UNIT_FILE"
  rm -f "$tmp"
}

# --- nginx -------------------------------------------------------------------

render_nginx_conf() { # host port ipv6(0|1) cert key
  local host="$1" port="$2" ipv6="$3" cert="$4" key="$5" names="127.0.0.1 localhost" listen6=""
  if [[ "$host" != "127.0.0.1" && "$host" != "localhost" ]]; then
    names="$host $names"
  fi
  if [[ "$ipv6" == 1 ]]; then
    listen6="    listen [::]:${port} ssl default_server;"$'\n'
  fi
  cat <<EOF
# Создано install-logserver.sh (ARDTT). При повторном запуске файл перезаписывается.
limit_req_zone \$binary_remote_addr zone=ardtt_logs_upload:10m rate=10r/m;
limit_req_zone \$binary_remote_addr zone=ardtt_logs_api:10m rate=60r/m;

server {
    listen ${port} ssl default_server;
${listen6}    server_name ${names};

    ssl_certificate     ${cert};
    ssl_certificate_key ${key};
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:ardtt_logs_ssl:5m;
    ssl_session_timeout 1h;
    ssl_session_tickets off;

    server_tokens off;
    client_max_body_size 25m;
    limit_req_status 429;
    limit_req_log_level warn;

    proxy_set_header Host              \$host;
    proxy_set_header X-Real-IP         \$remote_addr;
    proxy_set_header X-Forwarded-For   \$remote_addr;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_connect_timeout 5s;
    proxy_send_timeout    120s;
    proxy_read_timeout    120s;

    location = /health {
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
    }

    # Загрузка логов: 10 запросов в минуту с одного IP, пачка до 20.
    location = /api/upload-log {
        limit_req zone=ardtt_logs_upload burst=20 nodelay;
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
    }

    # История обращений и разбор.
    location /api/ {
        limit_req zone=ardtt_logs_api burst=60 nodelay;
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
    }

    location / {
        return 404;
    }
}
EOF
}

# Стандартный сайт nginx отключается, только если он не менялся (md5 из dpkg).
disable_stock_default_site() {
  local link=/etc/nginx/sites-enabled/default avail=/etc/nginx/sites-available/default want have
  if [[ ! -L "$link" ]]; then
    return 0
  fi
  if [[ "$(readlink -f "$link")" != "$(readlink -f "$avail")" || ! -f "$avail" ]]; then
    warn "Сайт nginx по умолчанию не стандартный — оставляю как есть."
    return 0
  fi
  # shellcheck disable=SC2016
  want="$(dpkg-query -W -f='${Conffiles}\n' nginx-common 2>/dev/null | awk -v f="$avail" '$1 == f { print $2; exit }' || true)"
  have="$(md5sum "$avail" | cut -d' ' -f1)"
  if [[ -n "$want" && "$want" == "$have" ]]; then
    rm -f "$link"
    log "Отключён стандартный сайт nginx («Welcome to nginx» на порту 80)"
  else
    warn "Сайт nginx по умолчанию изменён — оставляю как есть."
  fi
}

port_listeners() { # port -> строки ss или пусто
  command -v ss >/dev/null 2>&1 || return 0
  ss -H -ltnp "sport = :$1" 2>/dev/null || true
}

check_ports() {
  local listeners
  listeners="$(port_listeners "$LOG_PORT")"
  if [[ -n "$listeners" && "$listeners" != *nginx* ]]; then
    die "Порт $LOG_PORT уже занят не nginx:
$listeners
Освободите его или выберите другой: ARDTT_LOG_PORT=8443."
  fi
  listeners="$(port_listeners "$BACKEND_PORT")"
  if [[ -n "$listeners" ]] && ! systemctl is-active --quiet "$SERVICE_NAME.service"; then
    die "Внутренний порт $BACKEND_PORT занят чужим процессом:
$listeners"
  fi
}

# nginx -t открывает сокеты, поэтому IPv6-listen нужен, только если ядро умеет AF_INET6.
ipv6_available() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import socket; socket.socket(socket.AF_INET6, socket.SOCK_STREAM).close()' 2>/dev/null
  else
    [[ -e /proc/net/if_inet6 ]]
  fi
}

write_nginx_site() { # файл ipv6(0|1)
  local tmp
  tmp="$(mktemp)"
  render_nginx_conf "$LOG_HOST" "$LOG_PORT" "$2" "$TLS_DIR/cert.pem" "$TLS_DIR/key.pem" >"$tmp"
  install -m 0644 -o root -g root "$tmp" "$1"
  rm -f "$tmp"
}

install_nginx_site() {
  local avail other enabled="" bak="" tries=(0) try ok=0
  if ipv6_available; then
    tries=(1 0)
  fi
  if [[ -d /etc/nginx/sites-available && -d /etc/nginx/sites-enabled ]] \
    && grep -Eq '^[[:space:]]*include[[:space:]]+/etc/nginx/sites-enabled/' /etc/nginx/nginx.conf; then
    avail=/etc/nginx/sites-available/$SERVICE_NAME
    enabled=/etc/nginx/sites-enabled/$SERVICE_NAME
    other=/etc/nginx/conf.d/$SERVICE_NAME.conf
  elif grep -Eq '^[[:space:]]*include[[:space:]]+/etc/nginx/conf\.d/' /etc/nginx/nginx.conf; then
    avail=/etc/nginx/conf.d/$SERVICE_NAME.conf
    other=/etc/nginx/sites-enabled/$SERVICE_NAME
  else
    die "/etc/nginx/nginx.conf не подключает ни sites-enabled, ни conf.d — не знаю, куда положить сайт."
  fi
  # Не оставляем сайт в другом месте: дубли limit_req_zone ломают nginx -t.
  rm -f "$other"
  if [[ -f "$avail" ]]; then
    bak="$(mktemp)"
    cp -p "$avail" "$bak"
  fi
  for try in "${tries[@]}"; do
    write_nginx_site "$avail" "$try"
    if [[ -n "$enabled" ]]; then
      ln -sfn "$avail" "$enabled"
    fi
    if nginx -t; then
      ok=1
      break
    fi
    if [[ "$try" == 1 ]]; then
      warn "nginx не принял прослушивание IPv6 — пробую только IPv4."
    fi
  done
  if ((ok == 0)); then
    if [[ -n "$bak" ]]; then
      install -m 0644 -o root -g root "$bak" "$avail"
      rm -f "$bak"
    else
      rm -f "$avail" "$enabled"
    fi
    die "nginx -t отклонил конфигурацию (вывод выше). Прежнее состояние nginx восстановлено."
  fi
  if [[ -n "$bak" ]]; then
    rm -f "$bak"
  fi
  log "Сайт nginx: $avail (порт $LOG_PORT, TLS, лимиты запросов)"
}

open_firewall() {
  local status
  if command -v ufw >/dev/null 2>&1; then
    status="$(LC_ALL=C ufw status 2>/dev/null || true)"
    if [[ "$status" == "Status: active"* ]]; then
      if ufw allow "${LOG_PORT}/tcp" comment 'ARDTT log server' >/dev/null; then
        log "ufw: порт ${LOG_PORT}/tcp открыт"
      else
        warn "ufw не принял правило. Выполните вручную: ufw allow ${LOG_PORT}/tcp"
      fi
    fi
  fi
  if command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
    if firewall-cmd --permanent --add-port="${LOG_PORT}/tcp" >/dev/null && firewall-cmd --reload >/dev/null; then
      log "firewalld: порт ${LOG_PORT}/tcp открыт"
    else
      warn "firewalld не принял правило. Выполните вручную: firewall-cmd --permanent --add-port=${LOG_PORT}/tcp && firewall-cmd --reload"
    fi
  fi
}

# --- запуск и проверки -------------------------------------------------------

diagnostics() {
  warn "Диагностика:"
  {
    systemctl --no-pager -l status "$SERVICE_NAME.service" 2>&1 | tail -n 15 || true
    journalctl -u "$SERVICE_NAME.service" -n 30 --no-pager 2>&1 || true
    nginx -t 2>&1 || true
    ss -ltn 2>&1 | head -n 20 || true
  } >&2
}

start_services() {
  systemctl daemon-reload
  systemctl enable "$SERVICE_NAME.service" >/dev/null
  systemctl restart "$SERVICE_NAME.service"
  systemctl enable nginx >/dev/null
  if systemctl is-active --quiet nginx; then
    systemctl reload nginx
  else
    systemctl restart nginx
  fi
}

wait_for() { # описание, команда...: до ~25 с
  local what="$1"
  shift
  for _ in $(seq 1 25); do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  diagnostics
  die "$what не отвечает."
}

self_check() {
  local base="https://127.0.0.1:${LOG_PORT}" cert="$TLS_DIR/cert.pem" code hdr
  wait_for "Приёмник (127.0.0.1:$BACKEND_PORT)" curl -fsS --max-time 3 "http://127.0.0.1:$BACKEND_PORT/health"
  wait_for "HTTPS на порту $LOG_PORT" curl -fsS --max-time 5 --cacert "$cert" "$base/health"
  log "Проверка: curl --cacert $cert $base/health -> $(curl -fsS --max-time 5 --cacert "$cert" "$base/health")"
  # Пустой POST: 400 значит «токен не нужен, но тела нет»; 401 — загрузка без токена не включилась.
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 --cacert "$cert" -X POST "$base/api/upload-log" || true)"
  case "$code" in
    400) log "Загрузка без токена открыта (пустой POST -> 400, как и должно быть)" ;;
    401)
      diagnostics
      die "Приёмник требует токен для загрузки: TELEMETRY_UPLOAD_PUBLIC не применился."
      ;;
    *) warn "Неожиданный ответ приёмника на пустой POST: $code" ;;
  esac
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "http://127.0.0.1:$BACKEND_PORT/api/logs" || true)"
  if [[ "$code" != 401 ]]; then
    diagnostics
    die "Разбор логов без токена вернул $code вместо 401."
  fi
  hdr="$TMP_DIR/review.hdr"
  (
    umask 077
    printf 'Authorization: Bearer %s\n' "$REVIEW_TOKEN" >"$hdr"
  )
  curl -fsS --max-time 10 -H "@$hdr" "http://127.0.0.1:$BACKEND_PORT/api/logs?status=unread" >/dev/null \
    || {
      diagnostics
      die "Токен разбора из $TOKEN_FILE не принят приёмником."
    }
  rm -f "$hdr"
  log "Разбор логов с токеном работает"
}

print_summary() {
  local fp url
  fp="$(openssl x509 -in "$TLS_DIR/cert.pem" -noout -fingerprint -sha256 | cut -d= -f2)"
  url="https://${LOG_HOST}"
  if [[ "$LOG_PORT" != 443 ]]; then
    url="$url:$LOG_PORT"
  fi
  cat <<EOF

============================================================
ARDTT: сервер логов установлен и отвечает
============================================================
Адрес приёма для приложения: ${url}/api/upload-log
Логи на диске:               ${LOG_ROOT}/<client_id>/*.json
Токен разбора:               ${TOKEN_FILE} (читает только root, в вывод не печатается)
Сервис:                      systemctl status ${SERVICE_NAME}   журнал: journalctl -u ${SERVICE_NAME}
Лимиты и настройки:          ${ENV_FILE} (после правки: systemctl restart ${SERVICE_NAME})

Непрочитанные логи (на этом сервере):
  curl -s -H "Authorization: Bearer \$(cat ${TOKEN_FILE})" 'http://127.0.0.1:${BACKEND_PORT}/api/logs?status=unread'

Облачный firewall провайдера (если есть) должен пропускать ${LOG_PORT}/tcp.
Обновить код позже: повторите ту же команду установки; сертификат, токен и логи сохранятся.

СЕРТИФИКАТ — публичные данные, закрытый ключ остаётся на сервере.
Скопируйте блок от -----BEGIN CERTIFICATE----- до -----END CERTIFICATE----- и пришлите в чат.
>>>>>>>>>> СЕРТИФИКАТ: начало >>>>>>>>>>
$(openssl x509 -in "$TLS_DIR/cert.pem" -outform PEM)
<<<<<<<<<< СЕРТИФИКАТ: конец <<<<<<<<<<
SHA-256 отпечаток: ${fp}
(повторно показать: cat ${TLS_DIR}/cert.pem)
EOF
}

# --- удаление ----------------------------------------------------------------

do_uninstall() {
  log "Удаляю сервис $SERVICE_NAME и сайт nginx"
  if [[ -f "$UNIT_FILE" ]]; then
    systemctl disable --now "$SERVICE_NAME.service" >/dev/null 2>&1 || true
    rm -f "$UNIT_FILE"
    systemctl daemon-reload
  fi
  rm -f "/etc/nginx/sites-enabled/$SERVICE_NAME" "/etc/nginx/sites-available/$SERVICE_NAME" "/etc/nginx/conf.d/$SERVICE_NAME.conf"
  if command -v nginx >/dev/null 2>&1 && systemctl is-active --quiet nginx; then
    if nginx -t >/dev/null 2>&1; then
      systemctl reload nginx || warn "nginx не перечитал конфигурацию."
    else
      warn "nginx -t после удаления сайта не проходит — проверьте конфигурацию nginx вручную."
    fi
  fi
  rm -rf "$APP_ROOT"
  if [[ "${ARDTT_PURGE:-0}" == 1 ]]; then
    rm -rf "$LOG_ROOT" "$CONF_DIR" "$STATE_DIR"
    if id -u "$SERVICE_USER" >/dev/null 2>&1; then
      userdel "$SERVICE_USER" >/dev/null 2>&1 || warn "Не удалось удалить пользователя $SERVICE_USER."
    fi
    log "Удалены также логи ($LOG_ROOT), сертификат и токен ($CONF_DIR)"
  else
    log "Логи ($LOG_ROOT), сертификат и токен ($CONF_DIR) сохранены. Удалить и их: ARDTT_ACTION=uninstall ARDTT_PURGE=1"
  fi
  log "Пакеты (nginx, python3) и правила firewall не трогал."
}

do_install() {
  check_platform
  validate_params
  ensure_packages
  resolve_host
  fetch_sources
  ensure_user
  ensure_dirs
  ensure_venv
  install_app
  ensure_token
  write_env_file
  ensure_tls
  install_unit
  disable_stock_default_site
  check_ports
  install_nginx_site
  open_firewall
  start_services
  self_check
  print_summary
}

main() {
  trap 'on_error "$?" "$LINENO" "$BASH_COMMAND"' ERR
  trap cleanup EXIT
  # Скрипт мог прийти по `curl | bash`: ни одна команда не должна читать остаток stdin.
  exec </dev/null
  umask 022
  require_root
  case "${ARDTT_ACTION:-install}" in
    install) do_install ;;
    uninstall) do_uninstall ;;
    *) die "ARDTT_ACTION: install или uninstall, получено: ${ARDTT_ACTION}" ;;
  esac
}

# Исполняется при запуске и через `curl | bash`; при `source` (тесты) только определения.
if [[ "${BASH_SOURCE[0]:-$0}" == "$0" ]]; then
  main "$@"
fi
