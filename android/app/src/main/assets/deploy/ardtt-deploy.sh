#!/usr/bin/env bash
# ARDTT deploy v2: one image file from GitHub Releases, one container.
# Design: docs/adr/0006-deploy-v2.md. The phone uploads this file over SFTP and
# runs it as root; it is also published as a release asset for manual use.
#
# Protocol (stdout/stderr, one line each; the phone parses these):
#   ARDTT_PROGRESS|<0..1>|<step>
#   ARDTT_INFO|<text>          ARDTT_WARN|<text>
#   ARDTT_ERROR|code=<CODE>|<message>
#   ARDTT_CASCADE_PUBLIC_KEY|<key>
#   ARDTT_DONE|k=v|k=v…        (install)
#   ARDTT_STATUS|k=v|…         (status)
#   ARDTT_UNINSTALLED          (uninstall)
#
# Actions (ARDTT_ACTION): install (default; also update and 1.0.x migration),
# uninstall, status.
#
# Install inputs:
#   ARDTT_PUBLIC_HOST          required
#   ARDTT_DEPLOY_VERSION       stack version X → image ardtt/server:X (required)
#   ARDTT_RELEASE_TAG          GitHub release holding the image (default: found via API)
#   ARDTT_IMAGE_FILE           local image file instead of a download (lab, offline)
#   ARDTT_IMAGE_SHA256         expected SHA-256 of the image file (default: release SHA256SUMS-server.txt)
#   ARDTT_ROLE                 entry | exit
#   ARDTT_DIRECT_PORT ARDTT_BYPASS_PORT ARDTT_PROVISION_PORT ARDTT_TELEMETRY_PORT
#   ARDTT_AUTO_PORTS=1         take the next free port when one is busy
#   ARDTT_CASCADE_ENABLED ARDTT_CASCADE_LISTEN_PORT ARDTT_CASCADE_PEER_ENDPOINT
#   ARDTT_CASCADE_PEER_PUBLIC_KEY ARDTT_CASCADE_PEER_PROVISION_PORT ARDTT_CASCADE_DNS
#   ARDTT_CASCADE_SECRET ARDTT_CASCADE_FORCE_DISABLE=1 (ARDTT_CASCADE_ENABLED, when set, wins
#                              over the stored value; unset keeps the stored cascade)
#   ARDTT_CASCADE_SECRET_FILE  file with the exit's cascade secret (read, then deleted;
#                              keeps the secret out of the process list)
#   ARDTT_PROVISION_PUBLIC=1   publish provision/telemetry on 0.0.0.0 (default 127.0.0.1)
#   ARDTT_CPUS ARDTT_MEM_LIMIT ARDTT_PIDS_LIMIT
# Uninstall: ARDTT_PURGE_DATA=1 also removes /opt/ardtt/data.
set -euo pipefail
umask 022

INSTALLER_VERSION=2
DIR="${ARDTT_INSTALL_DIR:-/opt/ardtt}"
ACTION="${ARDTT_ACTION:-install}"
REPO="${ARDTT_GITHUB_REPO:-2kristalls36-hue/ARDTT}"
GH_API="${ARDTT_GITHUB_API:-https://api.github.com}"
GH_DL="${ARDTT_GITHUB_DOWNLOAD:-https://github.com}"
IMAGE_REPO="ardtt/server"
OWNER_LABEL="com.ardtt.owner"
INSTANCE_LABEL="com.ardtt.instance"

# ---------------------------------------------------------------- output ----
prog() { printf 'ARDTT_PROGRESS|%s|%s\n' "$1" "$2"; }
info() { printf 'ARDTT_INFO|%s\n' "$*"; }
warn() { printf 'ARDTT_WARN|%s\n' "$*"; }
# die CODE message… — stderr too, so a die inside $(…) still reaches the phone.
die() {
  local code="$1"
  shift
  printf 'ARDTT_ERROR|code=%s|%s\n' "$code" "$*" >&2
  exit 1
}
have() { command -v "$1" >/dev/null 2>&1; }

# ------------------------------------------------------------- utilities ----
host_arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    *) return 1 ;;
  esac
}

# KEY=VALUE files written by this script (and v1 .env). Never sourced.
kv_get() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  sed -n "s/^${key}=//p" "$file" | tail -n 1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

# Flat string/number field of a small JSON file (v1 instance.json).
json_get() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  tr -d '\n' <"$file" | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n 1
}

random_hex() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }

mb() { echo $(( ($1 + 1048575) / 1048576 )); }

avail_bytes() {
  local p="$1"
  while [ ! -e "$p" ] && [ "$p" != / ]; do p="$(dirname "$p")"; done
  df -PB1 "$p" 2>/dev/null | awk 'NR==2 {print $4}'
}

fs_id() {
  local p="$1"
  while [ ! -e "$p" ] && [ "$p" != / ]; do p="$(dirname "$p")"; done
  stat -c %d "$p" 2>/dev/null || echo "?"
}

# ------------------------------------------------------------ downloader ----
DL_TOOL=""
pick_downloader() {
  if have curl; then DL_TOOL=curl
  elif have wget; then DL_TOOL=wget
  elif have python3; then DL_TOOL=python3
  else DL_TOOL=""
  fi
}

http_get() { # url → stdout
  case "$DL_TOOL" in
    curl) curl -fsSL --retry 3 --connect-timeout 15 -A "ARDTT-deploy/${INSTALLER_VERSION}" "$1" ;;
    wget) wget -q -O - -U "ARDTT-deploy/${INSTALLER_VERSION}" "$1" ;;
    python3) python3 -c 'import sys,urllib.request as u; r=u.urlopen(u.Request(sys.argv[1],headers={"User-Agent":"ARDTT-deploy"}),timeout=60); sys.stdout.buffer.write(r.read())' "$1" ;;
    *) return 1 ;;
  esac
}

http_size() { # url → Content-Length of the final response, or empty
  case "$DL_TOOL" in
    curl) curl -fsSIL --connect-timeout 15 "$1" 2>/dev/null | tr -d '\r' | awk 'tolower($1)=="content-length:" {n=$2} END {print n}' ;;
    wget) wget -q --spider -S "$1" 2>&1 | awk 'tolower($1)=="content-length:" {n=$2} END {print n}' ;;
    python3) python3 -c 'import sys,urllib.request as u; r=u.urlopen(u.Request(sys.argv[1],method="HEAD"),timeout=30); print(r.headers.get("Content-Length",""))' "$1" 2>/dev/null ;;
  esac
}

http_download() { # url dest
  case "$DL_TOOL" in
    curl) curl -fSL --retry 3 --connect-timeout 15 -s -o "$2" "$1" ;;
    wget) wget -q -O "$2" "$1" ;;
    python3) python3 -c 'import sys,shutil,urllib.request as u; r=u.urlopen(u.Request(sys.argv[1],headers={"User-Agent":"ARDTT-deploy"}),timeout=60); shutil.copyfileobj(r,open(sys.argv[2],"wb"),1<<20)' "$1" "$2" ;;
    *) return 1 ;;
  esac
}

# ------------------------------------------------------- package manager ----
pkg_manager() {
  if have apt-get; then echo apt
  elif have dnf; then echo dnf
  elif have apk; then echo apk
  elif have zypper; then echo zypper
  elif have yum; then echo yum
  else echo ""
  fi
}

os_id() { sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | tr -d '"' | head -n 1; }

# Install distro packages. needrestart must not restart other services on apt.
pkg_install() {
  local pm log rc=0
  pm="$(pkg_manager)"
  log="$(mktemp)"
  case "$pm" in
    apt)
      export DEBIAN_FRONTEND=noninteractive NEEDRESTART_SUSPEND=1 NEEDRESTART_MODE=l
      { apt-get update -qq && apt-get install -y -qq --no-install-recommends -o Dpkg::Options::=--force-confold "$@"; } >"$log" 2>&1 || rc=$?
      ;;
    dnf) dnf install -y "$@" >"$log" 2>&1 || rc=$? ;;
    apk) apk add --no-cache "$@" >"$log" 2>&1 || rc=$? ;;
    zypper) zypper --non-interactive install "$@" >"$log" 2>&1 || rc=$? ;;
    *) rc=1; echo "пакетный менеджер не найден" >"$log" ;;
  esac
  if [ "$rc" -ne 0 ]; then
    tail -n 4 "$log" | sed 's/^/ARDTT_INFO|pkg: /'
  fi
  rm -f "$log"
  return "$rc"
}

ensure_downloader() {
  pick_downloader
  [ -n "$DL_TOOL" ] && return 0
  info "нет curl/wget/python3 — ставим curl из репозитория дистрибутива"
  pkg_install curl ca-certificates || die DOWNLOAD_FAILED "Нет curl, wget и python3, а поставить curl не удалось. Установите curl и повторите."
  pick_downloader
}

# ----------------------------------------------------------------- docker ----
docker_ok() { have docker && docker info >/dev/null 2>&1; }

ensure_docker() {
  if docker_ok; then
    info "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?') уже работает — не трогаем"
    return 0
  fi
  if have docker || have dockerd; then
    die DOCKER_NOT_RUNNING "Docker установлен, но демон не отвечает (docker info). Запустите его (systemctl start docker) и повторите — чужой Engine установщик не перезапускает."
  fi
  local os pm pkgs=()
  os="$(os_id)"
  pm="$(pkg_manager)"
  case "$pm:$os" in
    apt:*) pkgs=(docker.io) ;;
    dnf:fedora) pkgs=(moby-engine) ;;
    apk:*) pkgs=(docker) ;;
    zypper:*) pkgs=(docker) ;;
    *)
      die DOCKER_MISSING "На ${os:-этой системе} в репозиториях дистрибутива нет Docker. Установите Docker Engine вручную (https://docs.docker.com/engine/install/) и повторите."
      ;;
  esac
  prog 0.08 "Установка Docker из репозитория дистрибутива (${pkgs[*]})"
  pkg_install "${pkgs[@]}" || die DOCKER_INSTALL_FAILED "Не удалось поставить ${pkgs[*]} через ${pm}. Установите Docker вручную и повторите."
  if have systemctl; then
    systemctl enable --now docker >/dev/null 2>&1 || true
  elif have rc-update; then
    rc-update add docker default >/dev/null 2>&1 || true
    service docker start >/dev/null 2>&1 || true
  fi
  local _
  for _ in $(seq 1 30); do docker_ok && break; sleep 1; done
  docker_ok || die DOCKER_NOT_RUNNING "Docker поставлен, но демон не поднялся. Проверьте: systemctl status docker"
  info "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null) поставлен из репозитория дистрибутива"
}

docker_label() { # kind name key
  case "$1" in
    network) docker network inspect -f "{{index .Labels \"$3\"}}" "$2" 2>/dev/null || true ;;
    image) docker image inspect -f "{{index .Config.Labels \"$3\"}}" "$2" 2>/dev/null || true ;;
    *) docker container inspect -f "{{index .Config.Labels \"$3\"}}" "$2" 2>/dev/null || true ;;
  esac
}

container_exists() { docker container inspect "$1" >/dev/null 2>&1; }

# Ours: our instance label, or (very old installs) an unlabeled ARDTT/NonameVPN image.
container_is_ours() {
  local name="$1" inst image
  container_exists "$name" || return 1
  inst="$(docker_label container "$name" "$INSTANCE_LABEL")"
  if [ -n "$inst" ]; then
    [ "$inst" = "$INSTANCE_ID" ]
    return
  fi
  image="$(docker container inspect -f '{{.Config.Image}}' "$name" 2>/dev/null || true)"
  case "$image" in
    ardtt/*|nonamevpn/*|*/ardtt-server*|*/nonamevpn*) return 0 ;;
  esac
  return 1
}

# --------------------------------------------------------------- state -----
STATE_FILE="$DIR/instance.env"
V1_JSON="$DIR/instance.json"
V1_ENV="$DIR/.env"
[ -f "$V1_ENV" ] || [ ! -f "$DIR/current/.env" ] || V1_ENV="$DIR/current/.env"
MIGRATING_V1=0

# v1 committed instance.json, else the pending one of an install that never
# committed (its container may still be running).
v1_json() {
  local v
  v="$(json_get "$V1_JSON" "$1")"
  [ -n "$v" ] || v="$(json_get "$DIR/instance.pending.json" "$1")"
  printf '%s' "$v"
}

# Last resort for a 1.0.x install without metadata: the one ARDTT instance whose
# container runs on this host (/opt/ardtt holds a single instance).
sole_ardtt_instance() {
  docker_ok || return 0
  local ids
  ids="$(docker ps -a --filter "label=${OWNER_LABEL}=ardtt" --format "{{.Label \"${INSTANCE_LABEL}\"}}" 2>/dev/null | grep -v '^$' | sort -u)"
  [ "$(printf '%s\n' "$ids" | grep -c .)" = 1 ] && printf '%s' "$ids"
  return 0
}

stored() { # KEY → value from v2 state, else v1 .env / instance.json
  local key="$1" v=""
  if [ -f "$STATE_FILE" ]; then
    v="$(kv_get "$STATE_FILE" "$key")"
    printf '%s' "$v"
    return 0
  fi
  case "$key" in
    INSTANCE_ID)
      v="$(v1_json instanceId)"
      [ -n "$v" ] || v="$(kv_get "$V1_ENV" ARDTT_INSTANCE_ID)"
      # Only with 1.0.x leftovers here: never adopt another directory's instance.
      if [ -z "$v" ] && { [ -e "$DIR/current" ] || [ -f "$DIR/instance.pending.json" ]; }; then
        v="$(sole_ardtt_instance)"
      fi
      ;;
    CONTAINER) v="$(v1_json containerName)"; [ -n "$v" ] || v="$(kv_get "$V1_ENV" ARDTT_CONTAINER_NAME)" ;;
    NETWORK) v="$(v1_json networkName)"; [ -n "$v" ] || v="$(kv_get "$V1_ENV" ARDTT_NETWORK_NAME)" ;;
    SUBNET) v="$(v1_json bridgeSubnet)"; [ -n "$v" ] || v="$(kv_get "$V1_ENV" ARDTT_BRIDGE_SUBNET)" ;;
    VERSION) v="$(kv_get "$V1_ENV" ARDTT_DEPLOY_VERSION)" ;;
    PROVISION_PUBLIC)
      case "$(kv_get "$V1_ENV" ARDTT_PROVISION_BIND)" in
        ""|127.*|localhost) v="$(kv_get "$V1_ENV" ARDTT_PROVISION_PUBLIC)" ;;
        *) v=1 ;;
      esac
      ;;
    *) v="$(kv_get "$V1_ENV" "ARDTT_${key}")" ;;
  esac
  printf '%s' "$v"
}

save_state() {
  local tmp="$STATE_FILE.tmp"
  cat >"$tmp" <<EOF
# ARDTT instance (deploy v${INSTALLER_VERSION}). Written by ardtt-deploy.sh.
INSTANCE_ID=$INSTANCE_ID
CONTAINER=$CONTAINER
NETWORK=$NETWORK
SUBNET=$SUBNET
VERSION=$VERSION
PREV_VERSION=$PREV_VERSION
PUBLIC_HOST=$PUBLIC_HOST
ROLE=$ROLE
DIRECT_PORT=$DIRECT_PORT
BYPASS_PORT=$BYPASS_PORT
PROVISION_PORT=$PROVISION_PORT
TELEMETRY_PORT=$TELEMETRY_PORT
PROVISION_PUBLIC=$PROVISION_PUBLIC
CASCADE_ENABLED=$CASCADE_ENABLED
CASCADE_LISTEN_PORT=$CASCADE_LISTEN_PORT
CASCADE_PEER_ENDPOINT=$CASCADE_PEER_ENDPOINT
CASCADE_PEER_PUBLIC_KEY=$CASCADE_PEER_PUBLIC_KEY
CASCADE_PEER_PROVISION_PORT=$CASCADE_PEER_PROVISION_PORT
CASCADE_DNS=$CASCADE_DNS
EOF
  chmod 600 "$tmp"
  mv -f "$tmp" "$STATE_FILE"
}

# ----------------------------------------------------------------- ports ----
OWN_PORTS=" "

OTHER_DOCKER_PORTS=" "

port_bindings() { # container → lines «udp/51820»
  docker container inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostPort}}{{"\n"}}{{end}}{{end}}' "$1" 2>/dev/null \
    | sed -n 's#^[0-9]*/\(udp\|tcp\)=\([0-9][0-9]*\)$#\1/\2#p'
}

# Ports published by this instance count as free. Ports other containers
# publish count as busy even without a host socket (dockerd with
# userland-proxy=false publishes through NAT rules only).
collect_own_ports() {
  local c line
  OWN_PORTS=" "
  OTHER_DOCKER_PORTS=" "
  for c in $(docker ps -aq --filter "label=${INSTANCE_LABEL}=${INSTANCE_ID}" 2>/dev/null) "$CONTAINER" "${CONTAINER}-prev"; do
    container_is_ours "$c" 2>/dev/null || continue
    while IFS= read -r line; do
      [ -n "$line" ] && OWN_PORTS="${OWN_PORTS}${line} "
    done < <(port_bindings "$c")
  done
  for c in $(docker ps -q 2>/dev/null); do
    [ "$(docker_label container "$c" "$INSTANCE_LABEL")" = "$INSTANCE_ID" ] && continue
    while IFS= read -r line; do
      [ -n "$line" ] && OTHER_DOCKER_PORTS="${OTHER_DOCKER_PORTS}${line} "
    done < <(port_bindings "$c")
  done
}

port_busy() { # proto port
  local proto="$1" port="$2" flag
  case " $OWN_PORTS " in *" ${proto}/${port} "*) return 1 ;; esac
  case " $OTHER_DOCKER_PORTS " in *" ${proto}/${port} "*) return 0 ;; esac
  have ss || return 1
  [ "$proto" = udp ] && flag=-lnu || flag=-lnt
  # No -H: old iproute2 lacks it; the header is the first line.
  ss "$flag" "sport = :${port}" 2>/dev/null | awk 'NR > 1' | grep -q .
}

# resolve_port proto port label [avoid] → chosen port
resolve_port() {
  local proto="$1" want="$2" label="$3" avoid="${4:-}" p
  if ! port_busy "$proto" "$want" && [ "$want" != "$avoid" ]; then
    echo "$want"
    return 0
  fi
  if [ "$AUTO_PORTS" != 1 ]; then
    die PORT_BUSY "Порт ${want}/${proto} (${label}) занят другим процессом. Освободите его, выберите другой порт или включите автоподбор портов."
  fi
  for p in $(seq $((want + 1)) $((want + 200))); do
    [ "$p" -le 65535 ] || break
    [ "$p" = "$avoid" ] && continue
    if ! port_busy "$proto" "$p"; then
      warn "${label}: порт ${want}/${proto} занят — выбран ${p}/${proto}" >&2
      echo "$p"
      return 0
    fi
  done
  die PORT_BUSY "Не найден свободный порт ${proto} для «${label}» в диапазоне ${want}–$((want + 200))."
}

# ---------------------------------------------------------------- subnet ----
ip_to_int() {
  local IFS=. a b c d
  read -r a b c d <<<"$1"
  echo $(( (a << 24) | (b << 16) | (c << 8) | d ))
}

cidr_range() { # a.b.c.d/n → "start end"
  local ip="${1%/*}" n="${1#*/}" base size
  [ "$n" = "$1" ] && n=32
  base=$(ip_to_int "$ip")
  size=$(( 1 << (32 - n) ))
  base=$(( base & ~(size - 1) & 0xFFFFFFFF ))
  echo "$base $(( base + size - 1 ))"
}

overlaps() {
  local a1 a2 b1 b2
  read -r a1 a2 <<<"$(cidr_range "$1")"
  read -r b1 b2 <<<"$(cidr_range "$2")"
  [ "$a1" -le "$b2" ] && [ "$b1" -le "$a2" ]
}

taken_cidrs() { # host routes, addresses and other Docker networks (not ours)
  ip -4 route show 2>/dev/null | awk '{print $1}' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(/[0-9]+)?$' || true
  ip -4 -o addr show 2>/dev/null | awk '{print $4}' || true
  local id
  for id in $(docker network ls -q 2>/dev/null); do
    [ "$(docker_label network "$id" "$INSTANCE_LABEL")" = "$INSTANCE_ID" ] && continue
    docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{"\n"}}{{end}}' "$id" 2>/dev/null || true
  done
  # Well-known tunnel ranges inside the container.
  printf '%s\n' 10.8.0.0/24 10.9.0.0/24 10.10.0.0/30 10.99.99.0/24
}

subnet_free() {
  local want="$1" other
  while IFS= read -r other; do
    other="$(printf '%s' "$other" | tr -d '[:space:]')"
    case "$other" in *.*.*.*) ;; *) continue ;; esac
    case "$other" in 127.*) continue ;; esac
    # Own bridge route/address appears here before the network is reused.
    [ -n "${OWN_SUBNET:-}" ] && [ "$(cidr_range "$other")" = "$(cidr_range "$OWN_SUBNET")" ] && continue
    overlaps "$want" "$other" && return 1
  done < <(taken_cidrs)
  return 0
}

pick_subnet() {
  local pref="$1" fam o
  if [ -n "$pref" ] && subnet_free "$pref"; then echo "$pref"; return 0; fi
  for fam in "172.28 20 250" "172.30 0 250" "10.112 0 250" "10.210 0 250"; do
    set -- $fam
    for o in $(seq "$2" "$3"); do
      if subnet_free "$1.$o.0/24"; then echo "$1.$o.0/24"; return 0; fi
    done
  done
  die SUBNET_EXHAUSTED "Не найдена свободная /24 для сети ARDTT (все кандидаты пересекаются с маршрутами или сетями Docker)."
}

# -------------------------------------------------------------- secrets ----
ensure_secret() { # file envvalue → sets SECRET_VALUE, SECRET_NEW
  local file="$1" given="${2:-}"
  SECRET_NEW=0
  if [ -n "$given" ]; then
    printf '%s\n' "$given" >"$file"
  elif [ ! -s "$file" ]; then
    random_hex >"$file"
    echo >>"$file"
    SECRET_NEW=1
  fi
  chmod 600 "$file"
  SECRET_VALUE="$(tr -d '[:space:]' <"$file")"
}

# ------------------------------------------------------------- readiness ----
ready_once() { docker exec "$1" bash /opt/ardtt/ready.sh 2>&1; }

wait_ready() { # name seconds
  local name="$1" limit="${2:-120}" t=0 restarts=0 state
  while [ "$t" -lt "$limit" ]; do
    state="$(docker container inspect -f '{{.State.Status}} {{.State.OOMKilled}}' "$name" 2>/dev/null || echo missing)"
    case "$state" in
      "running "*) ready_once "$name" >/dev/null && return 0 ;;
      "restarting "*) restarts=$((restarts + 1)); [ "$restarts" -ge 4 ] && return 1 ;;
      *" true") return 1 ;;
      exited*|dead*|missing) return 1 ;;
    esac
    sleep 3
    t=$((t + 3))
  done
  return 1
}

ready_detail() {
  local name="$1" state out logs
  state="$(docker container inspect -f 'state={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$name" 2>/dev/null || echo 'state=missing')"
  out="$(ready_once "$name" 2>/dev/null | tail -n 2 | tr '\n|' '  ' | cut -c1-200 || true)"
  logs="$(docker logs --tail 15 "$name" 2>&1 | tr '\n|' '  ' | tr -s ' ' | cut -c1-500 || true)"
  printf '%s %s журнал: %s' "$state" "$out" "$logs"
}

# ---------------------------------------------------------------- install ----

take_lock() {
  mkdir -p "$DIR"
  chmod 755 "$DIR"
  exec 9>>"$DIR/.deploy.lock"
  if have flock && ! flock -n 9; then
    die DEPLOY_IN_PROGRESS "Другая установка ARDTT на этом сервере ещё идёт. Дождитесь её окончания."
  fi
}

resolve_release_assets() { # sets IMAGE_URL, SUMS_URL
  local asset="ardtt-server-${VERSION}-linux-${ARCH}.image.tar.gz" tag="${ARDTT_RELEASE_TAG:-}" page url
  if [ -z "$tag" ]; then
    prog 0.15 "Поиск релиза с образом ${VERSION}"
    page="$(http_get "${GH_API}/repos/${REPO}/releases?per_page=40" 2>/dev/null)" \
      || die DOWNLOAD_FAILED "VPS не получил список релизов GitHub (${GH_API}). Нужен исходящий HTTPS к github.com."
    url="$(printf '%s' "$page" | grep -o "\"browser_download_url\": *\"[^\"]*/${asset}\"" | head -n 1 | sed 's/.*"\(https[^"]*\)"$/\1/')"
    [ -n "$url" ] || die DOWNLOAD_FAILED "В релизах ${REPO} пока нет ${asset}. Сразу после выхода версии образ публикуется до получаса — повторите позже."
    IMAGE_URL="$url"
    SUMS_URL="${url%/*}/SHA256SUMS-server.txt"
  else
    IMAGE_URL="${GH_DL}/${REPO}/releases/download/${tag}/${asset}"
    SUMS_URL="${GH_DL}/${REPO}/releases/download/${tag}/SHA256SUMS-server.txt"
  fi
}

image_loaded() {
  docker image inspect "${IMAGE_REPO}:${VERSION}" >/dev/null 2>&1 || return 1
  [ "$(docker_label image "${IMAGE_REPO}:${VERSION}" com.ardtt.deploy-version)" = "$VERSION" ]
}

check_space() { # bytes_to_download raw_import_estimate
  local dl="$1" raw="$2" margin=$((300 * 1048576)) a_dir a_docker root
  root="$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)"
  a_dir="$(avail_bytes "$DIR")"
  a_docker="$(avail_bytes "$root")"
  if [ "$(fs_id "$DIR")" = "$(fs_id "$root")" ]; then
    if [ "${a_dir:-0}" -lt $((dl + raw + margin)) ]; then
      die INSUFFICIENT_DISK "Мало места на диске: свободно $(mb "${a_dir:-0}") МБ, нужно около $(mb $((dl + raw + margin))) МБ (образ $(mb "$dl") МБ, распаковка ~$(mb "$raw") МБ, запас 300 МБ)."
    fi
  else
    [ "${a_dir:-0}" -ge $((dl + margin / 2)) ] \
      || die INSUFFICIENT_DISK "Мало места в ${DIR}: свободно $(mb "${a_dir:-0}") МБ, нужно $(mb $((dl + margin / 2))) МБ."
    [ "${a_docker:-0}" -ge $((raw + margin / 2)) ] \
      || die INSUFFICIENT_DISK "Мало места в ${root}: свободно $(mb "${a_docker:-0}") МБ, нужно $(mb $((raw + margin / 2))) МБ."
  fi
}

obtain_image() {
  if [ -n "${ARDTT_IMAGE_FILE:-}" ]; then
    [ -f "$ARDTT_IMAGE_FILE" ] || die DOWNLOAD_FAILED "Нет файла образа ${ARDTT_IMAGE_FILE}"
    local size
    size="$(stat -c %s "$ARDTT_IMAGE_FILE")"
    check_space 0 $((size * 5))
    if [ -n "${ARDTT_IMAGE_SHA256:-}" ]; then
      prog 0.30 "Проверка SHA-256 образа"
      [ "$(sha256sum "$ARDTT_IMAGE_FILE" | awk '{print $1}')" = "$ARDTT_IMAGE_SHA256" ] \
        || die SHA256_MISMATCH "SHA-256 файла образа не совпал с ожидаемым."
    fi
    load_image "$ARDTT_IMAGE_FILE"
    return 0
  fi
  if image_loaded; then
    info "образ ${IMAGE_REPO}:${VERSION} уже загружен — скачивание пропущено"
    check_space 0 0
    return 0
  fi
  ensure_downloader
  resolve_release_assets
  local size want file tmp="$DIR/tmp"
  size="$(http_size "$IMAGE_URL" || true)"
  [ -n "$size" ] && [ "$size" -gt 0 ] 2>/dev/null || size=$((80 * 1048576))
  check_space "$size" $((size * 5))
  want="${ARDTT_IMAGE_SHA256:-}"
  if [ -z "$want" ]; then
    want="$(http_get "$SUMS_URL" 2>/dev/null | awk -v f="${IMAGE_URL##*/}" '$2==f || $2=="*"f {print $1}' | head -n 1)"
    [ -n "$want" ] || die SHA256_MISMATCH "В релизе нет SHA256SUMS-server.txt для ${IMAGE_URL##*/} — образ не проверить, установка остановлена."
  fi
  rm -rf "$tmp"
  mkdir -p "$tmp"
  chmod 700 "$tmp"
  file="$tmp/${IMAGE_URL##*/}"
  prog 0.20 "Загрузка образа ${VERSION} ($(mb "$size") МБ)"
  http_download "$IMAGE_URL" "$file" &
  local pid=$! got rc=0
  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
    got="$(stat -c %s "$file" 2>/dev/null || echo 0)"
    prog "$(awk -v g="$got" -v s="$size" 'BEGIN {f = 0.20 + 0.30 * g / s; if (f > 0.5) f = 0.5; printf "%.3f", f}')" \
      "Загрузка образа: $(mb "$got") из $(mb "$size") МБ"
  done
  wait "$pid" || rc=$?
  [ "$rc" -eq 0 ] || { rm -rf "$tmp"; die DOWNLOAD_FAILED "Не удалось скачать ${IMAGE_URL}. Проверьте доступ VPS к github.com."; }
  prog 0.52 "Проверка SHA-256 образа"
  if [ "$(sha256sum "$file" | awk '{print $1}')" != "$want" ]; then
    rm -rf "$tmp"
    die SHA256_MISMATCH "SHA-256 скачанного образа не совпал с релизом — файл повреждён или подменён. Повторите установку."
  fi
  load_image "$file"
  rm -rf "$tmp"
}

load_image() {
  prog 0.55 "Загрузка образа в Docker"
  local out
  out="$(docker load -i "$1" 2>&1)" || die IMAGE_LOAD_FAILED "docker load не прошёл: $(printf '%s' "$out" | tail -n 2 | tr '\n' ' ')"
  docker image inspect "${IMAGE_REPO}:${VERSION}" >/dev/null 2>&1 \
    || die IMAGE_LOAD_FAILED "После загрузки нет образа ${IMAGE_REPO}:${VERSION} (в файле: $(printf '%s' "$out" | tr '\n' ' '))"
  local arch
  arch="$(docker image inspect -f '{{.Architecture}}' "${IMAGE_REPO}:${VERSION}")"
  [ "$arch" = "$ARCH" ] || die IMAGE_LOAD_FAILED "Образ собран для ${arch}, а сервер ${ARCH}."
}

resolve_params() {
  PUBLIC_HOST="${ARDTT_PUBLIC_HOST:-$(stored PUBLIC_HOST)}"
  [ -n "$PUBLIC_HOST" ] || die BAD_INPUT "Не задан ARDTT_PUBLIC_HOST"
  VERSION="${ARDTT_DEPLOY_VERSION:-}"
  [ -n "$VERSION" ] || die BAD_INPUT "Не задан ARDTT_DEPLOY_VERSION"
  PREV_VERSION="$(stored VERSION)"
  ROLE="${ARDTT_ROLE:-$(stored ROLE)}"
  ROLE="${ROLE:-entry}"
  case "$ROLE" in entry|exit) ;; *) die BAD_INPUT "ARDTT_ROLE должен быть entry или exit" ;; esac
  AUTO_PORTS="${ARDTT_AUTO_PORTS:-0}"

  INSTANCE_ID="$(stored INSTANCE_ID)"
  [ -n "$INSTANCE_ID" ] || INSTANCE_ID="$(random_hex | cut -c1-16)"
  NETWORK="$(stored NETWORK)"
  NETWORK="${NETWORK:-ardtt-${INSTANCE_ID}}"
  CONTAINER="$(stored CONTAINER)"
  CONTAINER="${CONTAINER:-ardtt}"
  if container_exists "$CONTAINER" && ! container_is_ours "$CONTAINER"; then
    warn "имя контейнера ${CONTAINER} занято чужим контейнером — используем ardtt-${INSTANCE_ID:0:8}"
    CONTAINER="ardtt-${INSTANCE_ID:0:8}"
    container_exists "$CONTAINER" && ! container_is_ours "$CONTAINER" \
      && die FOREIGN_CONTAINER "Имена ardtt и ${CONTAINER} заняты чужими контейнерами."
  fi

  local s
  DIRECT_PORT="${ARDTT_DIRECT_PORT:-$(stored DIRECT_PORT)}";       DIRECT_PORT="${DIRECT_PORT:-51820}"
  BYPASS_PORT="${ARDTT_BYPASS_PORT:-$(stored BYPASS_PORT)}";       BYPASS_PORT="${BYPASS_PORT:-56003}"
  PROVISION_PORT="${ARDTT_PROVISION_PORT:-$(stored PROVISION_PORT)}"; PROVISION_PORT="${PROVISION_PORT:-9100}"
  TELEMETRY_PORT="${ARDTT_TELEMETRY_PORT:-$(stored TELEMETRY_PORT)}"; TELEMETRY_PORT="${TELEMETRY_PORT:-9200}"
  s="$(stored PROVISION_PUBLIC)"
  PROVISION_PUBLIC="${ARDTT_PROVISION_PUBLIC:-${s:-0}}"
  case "$PROVISION_PUBLIC" in 1|true|yes|on) PROVISION_PUBLIC=1 ;; *) PROVISION_PUBLIC=0 ;; esac

  # An explicit ARDTT_CASCADE_ENABLED (the app always sends it) wins, 0 included;
  # a manual run without it keeps the stored cascade.
  s="$(stored CASCADE_ENABLED)"
  if [ -n "${ARDTT_CASCADE_ENABLED:-}" ]; then
    CASCADE_ENABLED="$ARDTT_CASCADE_ENABLED"
  else
    CASCADE_ENABLED="${s:-0}"
  fi
  [ "${ARDTT_CASCADE_FORCE_DISABLE:-0}" = 1 ] && CASCADE_ENABLED=0
  [ "$CASCADE_ENABLED" = 1 ] || CASCADE_ENABLED=0
  if [ "$ROLE" = entry ] && [ "$CASCADE_ENABLED" = 0 ] && [ "$s" = 1 ]; then
    info "каскад выключен — вход работает сам"
    CASCADE_PEER_ENDPOINT_RESET=1
  fi
  [ "$ROLE" = exit ] && CASCADE_ENABLED=1
  CASCADE_LISTEN_PORT="${ARDTT_CASCADE_LISTEN_PORT:-$(stored CASCADE_LISTEN_PORT)}"; CASCADE_LISTEN_PORT="${CASCADE_LISTEN_PORT:-51820}"
  CASCADE_PEER_ENDPOINT="${ARDTT_CASCADE_PEER_ENDPOINT:-$(stored CASCADE_PEER_ENDPOINT)}"
  CASCADE_PEER_PUBLIC_KEY="${ARDTT_CASCADE_PEER_PUBLIC_KEY:-$(stored CASCADE_PEER_PUBLIC_KEY)}"
  if [ "${CASCADE_PEER_ENDPOINT_RESET:-0}" = 1 ]; then
    CASCADE_PEER_ENDPOINT=""
    CASCADE_PEER_PUBLIC_KEY=""
  fi
  CASCADE_PEER_PROVISION_PORT="${ARDTT_CASCADE_PEER_PROVISION_PORT:-$(stored CASCADE_PEER_PROVISION_PORT)}"
  CASCADE_PEER_PROVISION_PORT="${CASCADE_PEER_PROVISION_PORT:-9100}"
  CASCADE_DNS="${ARDTT_CASCADE_DNS:-$(stored CASCADE_DNS)}"
  if [ "$CASCADE_ENABLED" = 1 ]; then CASCADE_DNS="${CASCADE_DNS:-10.10.0.2}"; else CASCADE_DNS=""; fi
  [ "$ROLE" = exit ] && DIRECT_PORT="$CASCADE_LISTEN_PORT"

  SUBNET="$(stored SUBNET)"
  MIGRATING_V1=0
  if [ ! -f "$STATE_FILE" ] && { [ -f "$V1_JSON" ] || [ -f "$V1_ENV" ] || [ -f "$DIR/instance.pending.json" ] || [ -e "$DIR/current" ]; }; then
    MIGRATING_V1=1
  fi
  return 0
}

resolve_ports() {
  collect_own_ports
  if [ "$ROLE" = exit ]; then
    DIRECT_PORT="$(resolve_port udp "$DIRECT_PORT" "каскад AmneziaWG")"
    CASCADE_LISTEN_PORT="$DIRECT_PORT"
  else
    DIRECT_PORT="$(resolve_port udp "$DIRECT_PORT" "Direct AmneziaWG")"
    BYPASS_PORT="$(resolve_port udp "$BYPASS_PORT" "Bypass" "$DIRECT_PORT")"
  fi
  PROVISION_PORT="$(resolve_port tcp "$PROVISION_PORT" "provision")"
  TELEMETRY_PORT="$(resolve_port tcp "$TELEMETRY_PORT" "telemetry" "$PROVISION_PORT")"
}

ensure_network() {
  OWN_SUBNET=""
  if docker network inspect "$NETWORK" >/dev/null 2>&1; then
    if [ "$(docker_label network "$NETWORK" "$INSTANCE_LABEL")" = "$INSTANCE_ID" ]; then
      SUBNET="$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' "$NETWORK")"
      info "сеть ${NETWORK} (${SUBNET}) переиспользуется"
      return 0
    fi
    NETWORK="ardtt-${INSTANCE_ID}-net"
    warn "сеть с именем ARDTT принадлежит не этому экземпляру — используем ${NETWORK}"
  fi
  SUBNET="$(pick_subnet "$SUBNET")"
  docker network create --driver bridge --subnet "$SUBNET" \
    --label "${OWNER_LABEL}=ardtt" --label "${INSTANCE_LABEL}=${INSTANCE_ID}" "$NETWORK" >/dev/null \
    || die CONTAINER_START_FAILED "Не удалось создать сеть ${NETWORK} (${SUBNET})"
  info "сеть ${NETWORK} создана (${SUBNET})"
}

write_container_env() {
  local f="$DIR/container.env" bypass_dns=10.9.0.1 warp_mode=hideip warp_ifaces="awg0 wdttraw0" warp_url=""
  if [ "$CASCADE_ENABLED" = 1 ]; then bypass_dns="$CASCADE_DNS"; warp_mode=passthrough; fi
  if [ "$ROLE" = exit ]; then
    warp_mode=exit-hideip
    warp_ifaces=cascade0
    warp_url=http://10.10.0.1:9100/v1/hide-ip-prefixes
  fi
  cat >"$f.tmp" <<EOF
ARDTT_PUBLIC_HOST=$PUBLIC_HOST
ARDTT_PROVISION_LISTEN=0.0.0.0:9100
ARDTT_PROVISION_PORT=$PROVISION_PORT
ARDTT_DATA=/data
ARDTT_DEPLOY_VERSION=$VERSION
ARDTT_ROLE=$ROLE
ARDTT_CASCADE_ROLE=$ROLE
ARDTT_CASCADE_ENABLED=$CASCADE_ENABLED
ARDTT_CASCADE_DNS=$CASCADE_DNS
ARDTT_CASCADE_PEER_ENDPOINT=$CASCADE_PEER_ENDPOINT
ARDTT_CASCADE_PEER_PUBLIC_KEY=$CASCADE_PEER_PUBLIC_KEY
ARDTT_CASCADE_PEER_PROVISION_PORT=$CASCADE_PEER_PROVISION_PORT
ARDTT_CASCADE_LISTEN_PORT=51820
ARDTT_DIRECT_PORT=$DIRECT_PORT
ARDTT_DIRECT_LISTEN_PORT=51820
ARDTT_BYPASS_PORT=$BYPASS_PORT
ARDTT_BYPASS_LISTEN_PORT=56003
ARDTT_BYPASS_DNS=$bypass_dns
ARDTT_WDTT_CONFIG=/data/wdtt
GOMEMLIMIT=256MiB
ARDTT_WARP_STATE=/data/warp
ARDTT_WARP_MODE=$warp_mode
ARDTT_WARP_DNS_IIFACES=$warp_ifaces
ARDTT_WARP_HIDEIP_URL=$warp_url
TELEMETRY_LOG_ROOT=/var/logs/app
TELEMETRY_LISTEN=0.0.0.0:9200
ARDTT_TELEMETRY_PORT=$TELEMETRY_PORT
ARDTT_SKIP_TELEMETRY=0
ARDTT_NETWORK_MODE=isolated
EOF
  chmod 600 "$f.tmp"
  mv -f "$f.tmp" "$f"
}

resource_limits() {
  local total host
  total="$(awk '/MemTotal:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
  host="$(nproc 2>/dev/null || echo 1)"
  MEM_LIMIT="${ARDTT_MEM_LIMIT:-}"
  if [ -z "$MEM_LIMIT" ]; then
    if [ "${total:-0}" -gt 0 ] && [ "$total" -lt 1500 ]; then MEM_LIMIT=512m; else MEM_LIMIT=1g; fi
  fi
  CPUS="${ARDTT_CPUS:-}"
  [ -n "$CPUS" ] || { [ "$host" -ge 2 ] && CPUS=2.0 || CPUS=1.0; }
  CPUS="$(awk -v w="$CPUS" -v h="$host" 'BEGIN {if (w + 0 < 0.01) w = 0.01; if (w + 0 > h + 0) w = h + 0; printf "%.2f", w}')"
  PIDS_LIMIT="${ARDTT_PIDS_LIMIT:-512}"
}

run_container() {
  local bind=127.0.0.1 ports=()
  [ "$PROVISION_PUBLIC" = 1 ] && bind=0.0.0.0
  ports+=(-p "${DIRECT_PORT}:51820/udp")
  [ "$ROLE" = exit ] || ports+=(-p "${BYPASS_PORT}:56003/udp")
  ports+=(-p "${bind}:${PROVISION_PORT}:9100/tcp" -p "${bind}:${TELEMETRY_PORT}:9200/tcp")
  docker run -d --name "$CONTAINER" --hostname ardtt \
    --label "${OWNER_LABEL}=ardtt" --label "${INSTANCE_LABEL}=${INSTANCE_ID}" \
    --label "com.ardtt.role=${ROLE}" --label "com.ardtt.deploy-version=${VERSION}" \
    --label "com.ardtt.installer=${INSTALLER_VERSION}" \
    --restart unless-stopped --init \
    --cap-drop ALL --cap-add NET_ADMIN --cap-add NET_RAW --cap-add SETUID --cap-add SETGID \
    --security-opt no-new-privileges:true --device /dev/net/tun:/dev/net/tun \
    --memory "$MEM_LIMIT" --cpus "$CPUS" --pids-limit "$PIDS_LIMIT" \
    --sysctl net.ipv4.ip_forward=1 --sysctl net.ipv4.conf.all.src_valid_mark=1 \
    -v "$DIR/data:/data" -v "$DIR/logs:/var/logs/app" \
    --env-file "$DIR/container.env" \
    "${ports[@]}" \
    --network "$NETWORK" \
    --health-cmd "bash /opt/ardtt/ready.sh" --health-interval 15s --health-timeout 8s \
    --health-retries 8 --health-start-period 45s \
    --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
    "${IMAGE_REPO}:${VERSION}" 2>&1
}

ensure_cascade_keys() {
  local data="$DIR/data" pub
  [ "$CASCADE_ENABLED" = 1 ] || return 0
  prog 0.62 "Ключи каскада"
  if [ ! -s "$data/cascade.priv" ]; then
    docker run --rm --network none --entrypoint awg "${IMAGE_REPO}:${VERSION}" genkey >"$data/cascade.priv"
    chmod 600 "$data/cascade.priv"
  fi
  docker run --rm --network none -i --entrypoint awg "${IMAGE_REPO}:${VERSION}" pubkey <"$data/cascade.priv" >"$data/cascade.pub"
  chmod 644 "$data/cascade.pub"
  [ -n "$CASCADE_PEER_PUBLIC_KEY" ] && printf '%s\n' "$CASCADE_PEER_PUBLIC_KEY" >"$data/cascade.peer.pub"
  [ -n "$CASCADE_PEER_ENDPOINT" ] && printf '%s\n' "$CASCADE_PEER_ENDPOINT" >"$data/cascade.peer.endpoint"
  pub="$(tr -d '[:space:]' <"$data/cascade.pub")"
  [ -n "$pub" ] && printf 'ARDTT_CASCADE_PUBLIC_KEY|%s\n' "$pub"
  return 0
}

# Entry → exit: hand our cascade key to the exit's provision API (no third SSH hop).
push_entry_key_to_exit() {
  [ "$ROLE" = entry ] && [ "$CASCADE_ENABLED" = 1 ] || return 0
  local pub host url _
  pub="$(tr -d '[:space:]' <"$DIR/data/cascade.pub" 2>/dev/null || true)"
  host="${CASCADE_PEER_ENDPOINT%%:*}"
  if [ -z "$pub" ] || [ -z "$host" ] || [ -z "$CASCADE_SECRET" ]; then
    warn "ключ входа не отправлен на выход: нет ключа, адреса выхода или секрета каскада"
    return 0
  fi
  url="http://${host}:${CASCADE_PEER_PROVISION_PORT}/v1/cascade/peer"
  prog 0.92 "Передача ключа каскада на выход"
  for _ in 1 2 3 4 5 6; do
    if docker exec "$CONTAINER" curl -fsS -m 12 -X POST -H 'Content-Type: application/json' \
        -H "Authorization: Bearer ${CASCADE_SECRET}" -d "{\"publicKey\":\"${pub}\"}" "$url" >/dev/null 2>&1; then
      info "ключ входа отправлен на выход ${host}:${CASCADE_PEER_PROVISION_PORT}"
      return 0
    fi
    sleep 2
  done
  warn "не удалось отправить ключ входа на ${url} — проверьте доступ входа к выходу по порту ${CASCADE_PEER_PROVISION_PORT}"
}

provision_cert_fp() {
  docker exec "$CONTAINER" curl -fsS -m 3 http://127.0.0.1:9100/health 2>/dev/null \
    | sed -n 's/.*"provisionCertFp"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}

gc_images() {
  local ref id keep_cur="${IMAGE_REPO}:${VERSION}" keep_prev="${IMAGE_REPO}:${PREV_VERSION}"
  docker images --filter "label=${OWNER_LABEL}=ardtt" --format '{{.Repository}}:{{.Tag}} {{.ID}}' 2>/dev/null \
    | while read -r ref id; do
        [ "$ref" = "$keep_cur" ] || [ "$ref" = "$keep_prev" ] && continue
        case "$ref" in
          "<none>:<none>") docker rmi "$id" >/dev/null 2>&1 || true ;;
          ardtt/*|nonamevpn/*) docker rmi "$ref" >/dev/null 2>&1 || true ;;
        esac
      done
  return 0
}

# Remove what deploy 1.0.x kept in /opt/ardtt (never data, logs or backups).
cleanup_v1() {
  [ "$MIGRATING_V1" = 1 ] || return 0
  local p
  for p in releases current current-release previous previous-release staging incoming cache state install-lib scripts \
           bin/docker-compose instance.json instance.pending.json .env install.lock fetch.lock \
           DEPLOY_VERSION fetch-and-install.sh ardttctl install.log; do
    [ -e "$DIR/$p" ] || [ -L "$DIR/$p" ] || continue
    rm -rf "${DIR:?}/$p"
  done
  rmdir "$DIR/bin" 2>/dev/null || true
  info "служебные файлы деплоя 1.0.x в ${DIR} удалены (data, logs, backups сохранены)"
}

restore_previous() { # rename *-prev back and start it
  local prev="$1"
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  [ -n "$prev" ] && container_exists "$prev" || return 1
  docker rename "$prev" "$PREV_NAME" >/dev/null 2>&1 || return 2
  docker update --restart unless-stopped "$PREV_NAME" >/dev/null 2>&1 || true
  docker start "$PREV_NAME" >/dev/null 2>&1 || return 2
  return 0
}

do_install() {
  [ "$(id -u)" -eq 0 ] || die NOT_ROOT "Нужен root (или sudo)."
  ARCH="$(host_arch)" || die UNSUPPORTED_ARCH "Поддерживаются только amd64 и arm64 (uname -m: $(uname -m))."
  take_lock
  prog 0.02 "Проверка сервера"
  if [ ! -e /dev/net/tun ]; then modprobe tun 2>/dev/null || true; fi
  [ -e /dev/net/tun ] || die TUN_MISSING "Нет /dev/net/tun: провайдер VPS отключил TUN. Включите его в панели провайдера."
  local avail_mem
  avail_mem="$(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
  [ "${avail_mem:-0}" -ge 200 ] || die LOW_MEMORY "Свободно ${avail_mem} МБ RAM, нужно не меньше 200 МБ."

  prog 0.05 "Docker"
  ensure_docker
  resolve_params
  [ "$MIGRATING_V1" = 1 ] && info "найдена установка 1.0.x (${PREV_VERSION:-версия неизвестна}) — перенос в деплой v2"
  info "экземпляр ${INSTANCE_ID}, роль ${ROLE}, версия ${PREV_VERSION:-нет} → ${VERSION}"

  obtain_image

  prog 0.58 "Порты и сеть"
  resolve_ports
  ensure_network
  mkdir -p "$DIR/data" "$DIR/logs"
  chmod 700 "$DIR/data"
  if [ ! -s "$DIR/data/users.json" ] && [ -d "$DIR/stack/data" ]; then
    cp -a "$DIR/stack/data/." "$DIR/data/"
    info "данные очень старой установки перенесены из ${DIR}/stack/data"
  fi

  ensure_secret "$DIR/data/admin.token" "${ARDTT_ADMIN_TOKEN:-}"; ADMIN_TOKEN="$SECRET_VALUE"; ADMIN_NEW="$SECRET_NEW"
  ensure_secret "$DIR/data/telemetry.token" "${ARDTT_TELEMETRY_TOKEN:-}"; TELEMETRY_TOKEN="$SECRET_VALUE"; TELEMETRY_NEW="$SECRET_NEW"
  CASCADE_SECRET=""; CASCADE_NEW=0
  if [ -n "${ARDTT_CASCADE_SECRET_FILE:-}" ] && [ -f "$ARDTT_CASCADE_SECRET_FILE" ]; then
    ARDTT_CASCADE_SECRET="$(tr -d '[:space:]' <"$ARDTT_CASCADE_SECRET_FILE")"
    rm -f "$ARDTT_CASCADE_SECRET_FILE"
  fi
  if [ "$CASCADE_ENABLED" = 1 ] || [ -n "${ARDTT_CASCADE_SECRET:-}" ]; then
    ensure_secret "$DIR/data/cascade.secret" "${ARDTT_CASCADE_SECRET:-}"; CASCADE_SECRET="$SECRET_VALUE"; CASCADE_NEW="$SECRET_NEW"
  fi
  ensure_cascade_keys
  write_container_env
  resource_limits

  # Switch: the running container becomes <name>-prev until the new one is ready.
  local old="" c
  PREV_NAME="$CONTAINER"
  for c in "$CONTAINER" ardtt ardtt-host $(docker ps -aq --filter "label=${INSTANCE_LABEL}=${INSTANCE_ID}" 2>/dev/null); do
    [ "$c" = "${CONTAINER}-prev" ] && continue
    container_exists "$c" && container_is_ours "$c" || continue
    old="$(docker container inspect -f '{{.Name}}' "$c" | sed 's#^/##')"
    break
  done
  if container_exists "${CONTAINER}-prev" && container_is_ours "${CONTAINER}-prev"; then
    if [ -z "$old" ]; then
      old="${CONTAINER}-prev"   # an earlier run stopped mid-switch: that one is the live version
    else
      docker rm -f "${CONTAINER}-prev" >/dev/null 2>&1 || true
    fi
  fi
  local prev=""
  if [ -n "$old" ]; then
    PREV_NAME="$old"
    prog 0.66 "Остановка текущей версии (${PREV_VERSION:-?})"
    docker stop -t 30 "$old" >/dev/null 2>&1 || true
    if [ "$old" != "${CONTAINER}-prev" ]; then
      docker rename "$old" "${CONTAINER}-prev" || die CONTAINER_START_FAILED "Не удалось переименовать ${old}"
    fi
    prev="${CONTAINER}-prev"
    docker update --restart no "$prev" >/dev/null 2>&1 || true
  fi

  prog 0.72 "Запуск контейнера ${VERSION}"
  local out rb=0
  if ! out="$(run_container)"; then
    restore_previous "$prev" || rb=$?
    [ "$rb" -eq 2 ] && die ROLLBACK_FAILED "Новый контейнер не создан ($(printf '%s' "$out" | tail -n 1)), и предыдущая версия не поднялась. Повторите установку."
    die CONTAINER_START_FAILED "Docker не создал контейнер: $(printf '%s' "$out" | tail -n 2 | tr '\n' ' ')${prev:+ Предыдущая версия возвращена.}"
  fi

  prog 0.80 "Проверка готовности (до 2 минут)"
  if ! wait_ready "$CONTAINER" 120; then
    local detail
    detail="$(ready_detail "$CONTAINER")"
    restore_previous "$prev" || rb=$?
    [ "$rb" -eq 2 ] && die ROLLBACK_FAILED "Новая версия не запустилась, и предыдущая тоже не поднялась. ${detail}"
    if [ -n "$prev" ] && [ "$rb" -eq 0 ]; then
      prog 0.90 "Возврат ${PREV_VERSION:-предыдущей версии}"
      wait_ready "$PREV_NAME" 120 \
        || die ROLLBACK_FAILED "Новая версия ${VERSION} не прошла проверку готовности; предыдущая запущена, но тоже не готова. ${detail}"
    fi
    die READINESS_FAILED "Новая версия ${VERSION} не прошла проверку готовности${prev:+ — возвращена ${PREV_VERSION:-предыдущая}}. ${detail}"
  fi
  info "готово: $(ready_once "$CONTAINER" | tail -n 1)"

  [ -n "$prev" ] && docker rm -f "$prev" >/dev/null 2>&1 || true
  [ "$PREV_VERSION" = "$VERSION" ] && PREV_VERSION=""
  save_state
  cleanup_v1
  gc_images
  push_entry_key_to_exit
  install -m 755 "$0" "$DIR/ardtt-deploy.sh" 2>/dev/null || true

  local fp secrets=""
  fp="$(provision_cert_fp || true)"
  [ "$ADMIN_NEW" = 1 ] && secrets="${secrets}|admin_token=${ADMIN_TOKEN}"
  [ "$CASCADE_NEW" = 1 ] && secrets="${secrets}|cascade_secret=${CASCADE_SECRET}"
  [ "$TELEMETRY_NEW" = 1 ] && secrets="${secrets}|telemetry_token=${TELEMETRY_TOKEN}"
  [ -n "$fp" ] && secrets="${secrets}|provision_cert_fp=${fp}"
  prog 1 "Готово"
  printf 'ARDTT_DONE|install_dir=%s|public_host=%s|deploy_version=%s|telemetry_port=%s|provision_port=%s|role=%s|cascade=%s|network_mode=isolated|direct_port=%s|bypass_port=%s|cascade_listen_port=%s|auto_ports=%s|instance=%s|container=%s|installer=%s%s\n' \
    "$DIR" "$PUBLIC_HOST" "$VERSION" "$TELEMETRY_PORT" "$PROVISION_PORT" "$ROLE" "$CASCADE_ENABLED" \
    "$DIRECT_PORT" "$BYPASS_PORT" "$CASCADE_LISTEN_PORT" "$AUTO_PORTS" "$INSTANCE_ID" "$CONTAINER" \
    "$INSTALLER_VERSION" "$secrets"
}

# -------------------------------------------------------------- uninstall ----
do_uninstall() {
  [ "$(id -u)" -eq 0 ] || die NOT_ROOT "Нужен root (или sudo)."
  take_lock
  prog 0.1 "Поиск экземпляра ARDTT"
  INSTANCE_ID="$(stored INSTANCE_ID)"
  CONTAINER="$(stored CONTAINER)"; CONTAINER="${CONTAINER:-ardtt}"
  if docker_ok; then
    local c n
    prog 0.3 "Остановка и удаление контейнеров"
    for c in $( [ -n "$INSTANCE_ID" ] && docker ps -aq --filter "label=${INSTANCE_LABEL}=${INSTANCE_ID}") "$CONTAINER" "${CONTAINER}-prev"; do
      container_is_ours "$c" 2>/dev/null || continue
      docker stop -t 30 "$c" >/dev/null 2>&1 || true
      docker rm -f "$c" >/dev/null 2>&1 || true
    done
    prog 0.5 "Сеть и образы"
    if [ -n "$INSTANCE_ID" ]; then
      for n in $(docker network ls -q --filter "label=${INSTANCE_LABEL}=${INSTANCE_ID}"); do
        docker network rm "$n" >/dev/null 2>&1 || true
      done
    fi
    # Images: ARDTT's own, and only when no other ARDTT instance still runs on them.
    if [ -z "$(docker ps -aq --filter "label=${OWNER_LABEL}=ardtt")" ]; then
      docker images --filter "label=${OWNER_LABEL}=ardtt" -q | sort -u | while read -r n; do
        docker rmi -f "$n" >/dev/null 2>&1 || true
      done
    fi
  fi
  prog 0.8 "Каталог ${DIR}"
  if [ "${ARDTT_PURGE_DATA:-0}" = 1 ]; then
    rm -rf "${DIR:?}"
  else
    find "$DIR" -mindepth 1 -maxdepth 1 ! -name data ! -name backups ! -name logs -exec rm -rf {} + 2>/dev/null || true
    info "данные сохранены в ${DIR}/data"
  fi
  prog 1 "Готово"
  echo "ARDTT_UNINSTALLED"
}

# ----------------------------------------------------------------- status ----
do_status() {
  INSTANCE_ID="$(stored INSTANCE_ID)"
  CONTAINER="$(stored CONTAINER)"; CONTAINER="${CONTAINER:-ardtt}"
  local state="absent" health="" version
  version="$(stored VERSION)"
  if docker_ok && container_exists "$CONTAINER"; then
    state="$(docker container inspect -f '{{.State.Status}}' "$CONTAINER")"
    health="$(docker container inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$CONTAINER")"
  fi
  printf 'ARDTT_STATUS|installed=%s|instance=%s|version=%s|container=%s|state=%s|health=%s|installer=%s\n' \
    "$([ -n "$INSTANCE_ID" ] && echo 1 || echo 0)" "$INSTANCE_ID" "$version" "$CONTAINER" "$state" "$health" \
    "$([ -f "$STATE_FILE" ] && echo 2 || { [ -f "$V1_JSON" ] && echo 1 || echo 0; })"
}

# ------------------------------------------------------------------- main ----
run_action() {
  case "$ACTION" in
    install|update|"") ;;
    uninstall) do_uninstall; return ;;
    status) do_status; return ;;
    *) die BAD_INPUT "Неизвестное ARDTT_ACTION=${ACTION} (install|uninstall|status)" ;;
  esac
  mkdir -p "$DIR"
  local log="$DIR/deploy.log"
  # Full log of the last run on the VPS (root only: it holds new tokens).
  : >"$log"
  chmod 600 "$log"
  do_install > >(tee -a "$log") 2> >(tee -a "$log" >&2)
}

# A phone loses SSH easily (screen off, network switch). Install and uninstall
# therefore run in their own session: losing SSH only stops this follower, the
# switch to the new version still completes (or rolls back) on the server.
run_detached() {
  local run rc waited=0 child
  run="$(mktemp -d /tmp/ardtt-run.XXXXXX)"
  chmod 700 "$run"
  cp "$0" "$run/ardtt-deploy.sh"
  : >"$run/out"
  # The child records its own pid: setsid may fork and return at once.
  ARDTT_DETACHED=1 setsid bash -c 'echo $$ >"$2/pid"; trap "" HUP; bash "$1" >>"$2/out" 2>&1 </dev/null; echo $? >"$2/rc"' \
    _ "$run/ardtt-deploy.sh" "$run" </dev/null >/dev/null 2>&1 &
  tail -n +1 -f "$run/out" &
  local tail_pid=$!
  while [ ! -s "$run/rc" ]; do
    sleep 1
    waited=$((waited + 1))
    child="$(cat "$run/pid" 2>/dev/null || true)"
    if { [ -n "$child" ] && ! kill -0 "$child" 2>/dev/null; } || { [ -z "$child" ] && [ "$waited" -gt 15 ]; }; then
      sleep 1
      if [ ! -s "$run/rc" ]; then
        echo "ARDTT_ERROR|code=INSTALL_FAILED|установщик на VPS завершился без кода выхода" >>"$run/out"
        echo 1 >"$run/rc"
      fi
    fi
  done
  sleep 1
  kill "$tail_pid" 2>/dev/null || true
  wait "$tail_pid" 2>/dev/null || true
  rc="$(cat "$run/rc")"
  rm -rf "$run"
  return "$rc"
}

main() {
  case "$ACTION" in
    status) run_action; return ;;
  esac
  if [ "${ARDTT_DETACHED:-0}" != 1 ] && have setsid && [ "${ARDTT_NO_DETACH:-0}" != 1 ]; then
    run_detached
    return
  fi
  run_action
}

if [ "${ARDTT_DEPLOY_LIB:-0}" != 1 ]; then
  main
fi
