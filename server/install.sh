#!/bin/bash
# ARDTT VPS installer — canonical copy lives here (server/install.sh).
# Protocol (must stay in the first 400 chars: APK ≤0.5.245 sniffs only that window):
#   ARDTT_PROGRESS|<0..1>|<step>
#   ARDTT_ERROR|<message>
#   ARDTT_DONE|install_dir=…|public_host=…|direct_port=…|bypass_port=…
#   ARDTT_WARN|<message>
#
# Product path (from the app): the phone downloads server/ from GitHub
#   (release asset ardtt-stack-*.tar.gz or a source tarball) and uploads it
#   over SSH with this script, then:
#   ARDTT_PUBLIC_HOST=… ARDTT_GIT_REF=vX.Y.Z bash /opt/ardtt/install.sh
# Git path (on the VPS, no phone upload):
#   curl -fsSL https://raw.githubusercontent.com/<owner>/ARDTT/<tag>/server/install.sh \
#     -o /opt/ardtt/install.sh
#   ARDTT_PUBLIC_HOST=… ARDTT_GIT_REF=<tag> bash /opt/ardtt/install.sh
# Cascade: phone SSHs to the exit VPS (ARDTT_ROLE=exit) and the entry VPS
#   (ARDTT_ROLE=entry ARDTT_CASCADE_ENABLED=1) separately. Do not write SSH passwords
#   into .env.
# Ops path: same script; if the tarball is gone, re-run against already unpacked stack/.
# ARDTT_AUTO_PORTS=1 (app default): pick free UDP ports when preferred
# Direct/Bypass (or cascade listen) ports are already taken on the VPS.
#
# On VPS with ≥1.8 GiB RAM, build new images BEFORE stopping the old stack so a
# mid-build SSH drop does not take the VPN down. On ~1 GiB hosts that order OOMs
# BuildKit (snapshot … does not exist). We stop first, wipe BuildKit, and bring
# the previous stack back if the build fails.
set -euo pipefail

# Legacy NVPN_* from pre-rename deploys / .env.
INSTALL_DIR="${ARDTT_INSTALL_DIR:-${NVPN_INSTALL_DIR:-/opt/ardtt}}"
PUBLIC_HOST="${ARDTT_PUBLIC_HOST:-${NVPN_PUBLIC_HOST:-}}"
DIRECT_PORT="${ARDTT_DIRECT_PORT:-${NVPN_DIRECT_PORT:-51820}}"
BYPASS_PORT="${ARDTT_BYPASS_PORT:-${NVPN_BYPASS_PORT:-56003}}"
AUTO_PORTS="${ARDTT_AUTO_PORTS:-${NVPN_AUTO_PORTS:-0}}"
PROVISION_LISTEN="${ARDTT_PROVISION_LISTEN:-${NVPN_PROVISION_LISTEN:-0.0.0.0:9100}}"
TELEMETRY_PORT="${ARDTT_TELEMETRY_PORT:-${NVPN_TELEMETRY_PORT:-9200}}"
KEEP_INSTALL_LOG="${ARDTT_KEEP_INSTALL_LOG:-${NVPN_KEEP_INSTALL_LOG:-0}}"
COMPOSE_PROJECT="${ARDTT_COMPOSE_PROJECT:-${NVPN_COMPOSE_PROJECT:-stack}}"
MIN_SWAP_MB="${ARDTT_MIN_SWAP_MB:-${NVPN_MIN_SWAP_MB:-2048}}"
# Sequential one-image builds; do not demand 1.8G free on a 8–10G VPS.
MIN_DISK_MB="${ARDTT_MIN_DISK_MB:-${NVPN_MIN_DISK_MB:-1100}}"
MIN_DISK_UPDATE_MB="${ARDTT_MIN_DISK_UPDATE_MB:-${NVPN_MIN_DISK_UPDATE_MB:-500}}"
# entry = phone-facing stack. exit = hop egress (AWG + DNS + WARP).
ROLE="${ARDTT_ROLE:-${NVPN_ROLE:-entry}}"
CASCADE_ENABLED="${ARDTT_CASCADE_ENABLED:-${NVPN_CASCADE_ENABLED:-0}}"
CASCADE_LISTEN_PORT="${ARDTT_CASCADE_LISTEN_PORT:-${NVPN_CASCADE_LISTEN_PORT:-51820}}"
CASCADE_PEER_ENDPOINT="${ARDTT_CASCADE_PEER_ENDPOINT:-${NVPN_CASCADE_PEER_ENDPOINT:-}}"
CASCADE_PEER_PUBLIC_KEY="${ARDTT_CASCADE_PEER_PUBLIC_KEY:-${NVPN_CASCADE_PEER_PUBLIC_KEY:-}}"
CASCADE_DNS="${ARDTT_CASCADE_DNS:-${NVPN_CASCADE_DNS:-10.10.0.2}}"
GIT_REPO="${ARDTT_GIT_REPO:-${NVPN_GIT_REPO:-https://github.com/2kristalls36-hue/ARDTT.git}}"
GIT_REF="${ARDTT_GIT_REF:-${NVPN_GIT_REF:-}}"
if [ "$ROLE" = "exit" ]; then
  CASCADE_ENABLED=1
fi

env_file_val() {
  local file="$1" key="$2" v=""
  [ -f "$file" ] || return 0
  v="$(grep -E "^${key}=" "$file" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\"' | tr -d "'" | tr -d '[:space:]')"
  if [ -z "$v" ] && [[ "$key" == ARDTT_* ]]; then
    v="$(grep -E "^NVPN_${key#ARDTT_}=" "$file" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\"' | tr -d "'" | tr -d '[:space:]')"
  fi
  printf '%s' "$v"
}

migrate_legacy_install_dir() {
  if [ -d /opt/nonamevpn ] && [ ! -e /opt/ardtt ]; then
    mv /opt/nonamevpn /opt/ardtt
    echo "ARDTT_WARN|каталог /opt/nonamevpn перенесён в /opt/ardtt"
  fi
  if [ "$INSTALL_DIR" = "/opt/nonamevpn" ]; then
    INSTALL_DIR=/opt/ardtt
  fi
}

ARDTT_CONTAINER_NAMES="ardtt ardtt-host ardtt-provision ardtt-direct ardtt-bypass ardtt-dns ardtt-warp ardtt-telemetry ardtt-cascade nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry nvpn-cascade"

drop_legacy_containers() {
  # shellcheck disable=SC2086
  docker rm -f $ARDTT_CONTAINER_NAMES >/dev/null 2>&1 || true
}

# True if Docker is already running workloads that are not ARDTT.
# Used to refuse dockerd restarts, builder prune -af, swap shrink, and
# container prune that would take down someone else's stack on a shared VPS.
foreign_docker_workloads() {
  command -v docker >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1 || return 1
  local names
  names="$(docker ps -a --format '{{.Names}}' 2>/dev/null || true)"
  [ -n "$names" ] || return 1
  echo "$names" | grep -vE '^(ardtt|nvpn-|stack-|buildx_buildkit_)' | grep -q .
}

# After stopping the old host-network stack, leftover TUN / iptables / ip rules
# on the VPS would keep breaking nginx, other VPNs, and Docker bridge traffic.
cleanup_host_dataplane() {
  echo "ARDTT_INFO|снимаем leftover awg0/warp0/iptables/ip-rule с хоста"
  local iface proto net fr pref n table chain comment
  for iface in awg0 wdttraw0 warp0 cascade0; do
    ip link del "$iface" 2>/dev/null || true
  done
  rm -f /etc/wireguard/warp0.conf 2>/dev/null || true
  # Snapshot first. Skip foreign WireGuard that happens to use table 51820.
  while IFS= read -r fr; do
    [ -n "$fr" ] || continue
    echo "$fr" | grep -Eq 'from 10\.(8|9)\.|from 10\.10\.0\.|from 10\.99\.99\.|iif (awg0|wdttraw0|warp0|cascade0)' || continue
    pref="$(echo "$fr" | cut -d: -f1 | tr -d '[:space:]')"
    [ -n "$pref" ] && ip rule del pref "$pref" 2>/dev/null || true
  done <<< "$(ip rule show 2>/dev/null | grep "lookup 51820" || true)"
  for iface in awg0 wdttraw0 cascade0; do
    for proto in udp tcp; do
      ip rule del iif "$iface" ipproto "$proto" dport 53 lookup main 2>/dev/null || true
    done
  done
  for net in 10.8.0.0/24 10.9.0.0/24 10.10.0.0/30 10.99.99.0/24 127.0.0.0/8; do
    ip rule del to "$net" lookup main 2>/dev/null || true
  done
  command -v iptables >/dev/null 2>&1 || return 0
  for comment in AWG_DIRECT_MANAGED ARDTT_BYPASS_MANAGED ARDTT_WARP_MANAGED ARDTT_WARP_DNS_MAIN ARDTT_CASCADE_WAN_MASQ ARDTT_CASCADE_MANAGED; do
    for table in filter nat mangle; do
      for chain in INPUT FORWARD POSTROUTING PREROUTING OUTPUT; do
        while true; do
          n="$(iptables -t "$table" -L "$chain" --line-numbers -n 2>/dev/null \
            | grep -F "$comment" | awk '{print $1}' | tail -1 || true)"
          [ -n "$n" ] || break
          iptables -t "$table" -D "$chain" "$n" 2>/dev/null || break
        done
      done
    done
  done
}

migrate_bypass_volume() {
  local dest="$1/wdtt"
  mkdir -p "$dest"
  if [ -n "$(ls -A "$dest" 2>/dev/null || true)" ]; then
    return 0
  fi
  command -v docker >/dev/null 2>&1 || return 0
  local vol img
  img="${COMPOSE_PROJECT}-ardtt:latest"
  for vol in "${COMPOSE_PROJECT}_bypass-config" stack_bypass-config bypass-config; do
    docker volume inspect "$vol" >/dev/null 2>&1 || continue
    echo "ARDTT_INFO|перенос паролей bypass из Docker volume $vol → data/wdtt"
    if docker image inspect "$img" >/dev/null 2>&1; then
      docker run --rm --network none --entrypoint cp \
        -v "$vol":/from:ro -v "$dest":/to "$img" -a /from/. /to/ 2>/dev/null || true
    fi
    break
  done
}

resolve_network_mode() {
  local prev=""
  NETWORK_MODE="${ARDTT_NETWORK_MODE:-${NVPN_NETWORK_MODE:-}}"
  if [ -z "$NETWORK_MODE" ]; then
    prev="$(env_file_val "$INSTALL_DIR/stack/.env" ARDTT_NETWORK_MODE)"
    NETWORK_MODE="$prev"
  fi
  case "$NETWORK_MODE" in
    host|hostnet)
      NETWORK_MODE=hostnet
      COMPOSE_PROFILES=hostnet
      ;;
    *)
      NETWORK_MODE=isolated
      COMPOSE_PROFILES=isolated
      ;;
  esac
  export COMPOSE_PROFILES NETWORK_MODE
}

who_owns_port() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lntup 2>/dev/null | grep -E "[.:]${port}[[:space:]]" | head -3 | tr '\n' ' ' | cut -c1-240
  fi
}

udp_listen_port() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lun 2>/dev/null | awk -v p=":${port}" '
      $4 == p || $4 ~ (p "$") { found=1 }
      END { exit !found }
    '
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lun 2>/dev/null | grep -Eq "[.:]${port}[[:space:]]"
  else
    return 1
  fi
}

require_host_port() {
  local proto="$1" port="$2" what="$3"
  if [ "$proto" = udp ]; then
    udp_listen_port "$port" || return 0
  else
    tcp_listen_port "$port" || return 0
  fi
  die "Порт ${port}/${proto} занят (${what}) — другой сервис на этом VPS. Освободите порт или задайте другой ARDTT_*_PORT. Сейчас: $(who_owns_port "$port")"
}

# True if $1 equals any later argument (used so Direct ≠ Bypass ≠ cascade).
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

find_free_udp_port() {
  local start="$1"
  shift
  local port="$start" tries=0
  if [ -z "$port" ] || [ "$port" -lt 1024 ] 2>/dev/null; then
    port=1024
  fi
  while [ "$tries" -lt 500 ]; do
    if ! port_in_use_by_us "$port" "$@" && ! udp_listen_port "$port"; then
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

# If preferred UDP is free, keep it; with AUTO_PORTS=1 pick the next free port.
resolve_udp_host_port() {
  local preferred="$1" what="$2"
  shift 2
  if ! udp_listen_port "$preferred" && ! port_in_use_by_us "$preferred" "$@"; then
    printf '%s' "$preferred"
    return 0
  fi
  if [ "$AUTO_PORTS" != "1" ]; then
    require_host_port udp "$preferred" "$what"
    return 1
  fi
  local next
  next="$(find_free_udp_port "$preferred" "$@")" || \
    die "Не удалось подобрать свободный UDP-порт для ${what} (старт с ${preferred})"
  if [ "$next" != "$preferred" ]; then
    # Must not mix with the printf port on stdout (command substitution).
    echo "ARDTT_WARN|${what}: порт ${preferred}/udp занят — выбран ${next}/udp. Было: $(who_owns_port "$preferred")" >&2
  fi
  printf '%s' "$next"
}

# Prefer ports from a previous deploy when auto-picking (keeps client profiles).
preserve_previous_ports() {
  [ "$AUTO_PORTS" = "1" ] || return 0
  local envf="$INSTALL_DIR/stack/.env" prev=""
  prev="$(env_file_val "$envf" ARDTT_DIRECT_PORT)"
  if [ -n "$prev" ]; then DIRECT_PORT="$prev"; fi
  prev="$(env_file_val "$envf" ARDTT_BYPASS_PORT)"
  if [ -n "$prev" ]; then BYPASS_PORT="$prev"; fi
  prev="$(env_file_val "$envf" ARDTT_CASCADE_LISTEN_PORT)"
  if [ -n "$prev" ]; then CASCADE_LISTEN_PORT="$prev"; fi
  return 0
}

env_set_key() {
  local file="$1" key="$2" val="$3"
  [ -f "$file" ] || return 0
  if grep -qE "^${key}=" "$file" 2>/dev/null; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$file"
  else
    printf '%s=%s\n' "$key" "$val" >> "$file"
  fi
}

# Isolated compose publishes host TELEMETRY_PORT → container :9200. When nginx
# already owns :9200 and socat owns :9199, that publish would fail `compose up`
# and ARDTT_SKIP_TELEMETRY=1 would leave gunicorn off (Android upload → 502).
drop_telemetry_host_publish() {
  local compose="$1" tmp
  [ -f "$compose" ] || return 0
  tmp="$(mktemp)"
  grep -v 'ARDTT_TELEMETRY_PORT.*9200/tcp' "$compose" >"$tmp"
  if cmp -s "$compose" "$tmp"; then
    rm -f "$tmp"
    echo "ARDTT_WARN|в compose нет publish telemetry — оставляю как есть"
    return 0
  fi
  mv "$tmp" "$compose"
}

# An in-app "update" of the entry hop often omits cascade flags. Dropping them
# tears down ardtt-cascade, leaves profiles on 10.10.0.2 DNS, and Hide-IP-off
# traffic can stick on Cloudflare while the app still shows the VPS WAN.
preserve_live_cascade() {
  [ "$ROLE" = "entry" ] || return 0
  [ "${ARDTT_CASCADE_FORCE_DISABLE:-${NVPN_CASCADE_FORCE_DISABLE:-0}}" = "1" ] && return 0
  local envf="$INSTALL_DIR/stack/.env"
  local data="$INSTALL_DIR/stack/data"
  local prev
  prev="$(env_file_val "$envf" ARDTT_CASCADE_ENABLED)"
  if [ "$prev" = "1" ] && [ "$CASCADE_ENABLED" != "1" ]; then
    CASCADE_ENABLED=1
    echo "ARDTT_WARN|каскад сохранён с прошлого деплоя (ARDTT_CASCADE_FORCE_DISABLE=1 чтобы снять)"
  fi
  if [ "$CASCADE_ENABLED" != "1" ]; then
    if [ -s "$data/cascade.priv" ]; then
      echo "ARDTT_WARN|каскадные ключи на диске, hop выключен — включите каскад в приложении чтобы снова связать вход с выходом"
    fi
    CASCADE_DNS=""
    return 0
  fi
  if [ -z "$CASCADE_PEER_ENDPOINT" ]; then
    CASCADE_PEER_ENDPOINT="$(env_file_val "$envf" ARDTT_CASCADE_PEER_ENDPOINT)"
  fi
  if [ -z "$CASCADE_PEER_ENDPOINT" ] && [ -s "$data/cascade.peer.endpoint" ]; then
    CASCADE_PEER_ENDPOINT="$(tr -d '[:space:]' < "$data/cascade.peer.endpoint")"
  fi
  if [ -z "$CASCADE_PEER_PUBLIC_KEY" ]; then
    CASCADE_PEER_PUBLIC_KEY="$(env_file_val "$envf" ARDTT_CASCADE_PEER_PUBLIC_KEY)"
  fi
  if [ -z "$CASCADE_PEER_PUBLIC_KEY" ] && [ -s "$data/cascade.peer.pub" ]; then
    CASCADE_PEER_PUBLIC_KEY="$(tr -d '[:space:]' < "$data/cascade.peer.pub")"
  fi
  [ -n "$CASCADE_DNS" ] || CASCADE_DNS="10.10.0.2"
}
migrate_legacy_install_dir
preserve_previous_ports
preserve_live_cascade
resolve_network_mode

LOG_FILE="$(mktemp /tmp/ardtt-install.XXXXXX.log)"
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

# Mountpoints under $1 from $2 (default /proc/mounts), deepest first.
list_mounts_under() {
  local root="$1"
  local src="${2:-/proc/mounts}"
  [ -n "$root" ] || return 0
  [ -r "$src" ] || return 0
  awk -v p="$root" '
    $2 == p || index($2, p "/") == 1 { print length($2) " " $2 }
  ' "$src" | sort -nr | awk '{ $1=""; sub(/^ /,""); print }'
}

# Drop overlay/bind mounts that keep rm -rf "Device or resource busy"
# (BuildKit executor rootfs after an incomplete dockerd stop on tiny VPS).
unmount_tree() {
  local root="$1"
  local tries=0 m any
  [ -n "$root" ] || return 0
  while [ "$tries" -lt 10 ]; do
    tries=$((tries + 1))
    any=0
    while IFS= read -r m; do
      [ -n "$m" ] || continue
      any=1
      umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null || true
    done < <(list_mounts_under "$root")
    [ "$any" = 0 ] && return 0
    sleep 1
  done
}

wipe_dir_best_effort() {
  local dir="$1"
  [ -e "$dir" ] || return 0
  unmount_tree "$dir"
  rm -rf "$dir" 2>/dev/null && return 0
  echo "ARDTT_WARN|не удалось удалить $dir — lazy umount и повтор"
  unmount_tree "$dir"
  rm -rf "$dir" 2>/dev/null || true
  if [ -e "$dir" ]; then
    echo "ARDTT_WARN|$dir всё ещё занят — продолжаем, Docker пересоздаст"
  fi
}

kill_pids_of() {
  local name="$1" pids
  pids="$(pidof "$name" 2>/dev/null || true)"
  [ -n "$pids" ] || return 0
  # shellcheck disable=SC2086
  kill -9 $pids 2>/dev/null || true
}

stop_docker_engine() {
  if command -v systemctl >/dev/null 2>&1; then
    systemctl stop docker.socket docker.service docker containerd 2>/dev/null || true
  else
    service docker stop 2>/dev/null || true
  fi
  local i
  for i in $(seq 1 20); do
    if ! pidof dockerd >/dev/null 2>&1 && ! pidof containerd >/dev/null 2>&1 && ! pidof buildkitd >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  kill_pids_of buildkitd
  kill_pids_of dockerd
  sleep 1
}

start_docker_engine() {
  if command -v systemctl >/dev/null 2>&1; then
    systemctl start containerd 2>/dev/null || true
    systemctl start docker 2>/dev/null || service docker start 2>/dev/null || true
  else
    service docker start 2>/dev/null || true
  fi
}

# Stop dockerd, unmount leftover executor rootfs, delete BuildKit cache, start dockerd.
# Callers on tiny VPS must compose-down the VPN stack first.
# Never abort on "Device or resource busy": a 1 GiB VPS often leaves overlay
# mounts after `systemctl stop docker`, and `set -e` + bare `rm -rf` killed cascade.
reset_docker_buildkit() {
  command -v docker >/dev/null 2>&1 || return 0
  if foreign_docker_workloads; then
    echo "ARDTT_WARN|пропускаем builder prune -af и restart dockerd — на хосте есть другие контейнеры"
    docker image prune -f >/dev/null 2>&1 || true
    return 0
  fi
  docker builder prune -af >/dev/null 2>&1 || true
  docker buildx prune -af >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true
  echo "ARDTT_INFO|сброс BuildKit: stop docker, umount executor, удаление /var/lib/docker/buildkit"
  stop_docker_engine
  wipe_dir_best_effort /var/lib/docker/buildkit
  start_docker_engine
  if ! wait_for_docker; then
    echo "ARDTT_WARN|docker не ответил сразу после сброса BuildKit"
  fi
}

cleanup_docker_build_junk() {
  prepare_docker_build
}

STACK_STOPPED_FOR_BUILD=0

restore_live_stack_if_needed() {
  [ "${STACK_STOPPED_FOR_BUILD:-0}" = "1" ] || return 0
  [ -f "$INSTALL_DIR/stack/docker-compose.yml" ] || return 0
  echo "ARDTT_WARN|поднимаем прежний стек (сборка не закончена)"
  (
    cd "$INSTALL_DIR/stack" || exit 0
    if [ -f .env ]; then
      COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose --env-file .env up -d
    else
      COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose up -d
    fi
  ) >/dev/null 2>&1 || \
    docker start ardtt ardtt-host >/dev/null 2>&1 || \
    docker start ardtt-provision ardtt-direct ardtt-bypass ardtt-dns ardtt-warp ardtt-telemetry ardtt-cascade >/dev/null 2>&1 || \
    docker start nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry nvpn-cascade >/dev/null 2>&1 || true
  STACK_STOPPED_FOR_BUILD=0
}

find_server_tree() {
  local root="$1"
  local candidate
  if [ -f "$root/docker-compose.yml" ] && [ -d "$root/provision" ]; then
    printf '%s' "$root"
    return 0
  fi
  if [ -f "$root/server/docker-compose.yml" ] && [ -d "$root/server/provision" ]; then
    printf '%s' "$root/server"
    return 0
  fi
  for candidate in "$root"/*; do
    [ -d "$candidate" ] || continue
    if [ -f "$candidate/docker-compose.yml" ] && [ -d "$candidate/provision" ]; then
      printf '%s' "$candidate"
      return 0
    fi
    if [ -f "$candidate/server/docker-compose.yml" ] && [ -d "$candidate/server/provision" ]; then
      printf '%s' "$candidate/server"
      return 0
    fi
  done
  return 1
}

copy_server_tree_to_staging() {
  local tree="$1"
  rm -rf "$STAGING"
  mkdir -p "$STAGING"
  cp -a "$tree"/. "$STAGING"/
}

extract_archive_to_staging() {
  local archive="$1"
  local extract="$INSTALL_DIR/unpack.src"
  rm -rf "$extract" "$STAGING"
  mkdir -p "$extract"
  tar -xzf "$archive" -C "$extract" || die "Не удалось распаковать архив стека"
  local tree
  if ! tree="$(find_server_tree "$extract")"; then
    rm -rf "$extract"
    die "В архиве нет server/ (docker-compose.yml + provision/). Скачайте стек из GitHub."
  fi
  copy_server_tree_to_staging "$tree"
  rm -rf "$extract"
  rm -f "$archive"
}

ensure_fetch_tools() {
  if command -v curl >/dev/null 2>&1 || command -v git >/dev/null 2>&1; then
    return 0
  fi
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y --no-install-recommends ca-certificates curl git
  elif command -v dnf >/dev/null 2>&1; then
    dnf -y install ca-certificates curl git
  else
    die "Нужны curl или git, чтобы скачать стек из GitHub"
  fi
}

github_source_tarball_url() {
  local repo="$1" ref="$2"
  repo="${repo%.git}"
  case "$ref" in
    main|master|HEAD) printf '%s/archive/refs/heads/%s.tar.gz' "$repo" "$ref" ;;
    *) printf '%s/archive/refs/tags/%s.tar.gz' "$repo" "$ref" ;;
  esac
}

fetch_stack_from_git() {
  local ref="$1"
  local repo="$GIT_REPO"
  [ -n "$ref" ] || die "ARDTT_GIT_REF пуст"
  ensure_fetch_tools
  local tmp="$INSTALL_DIR/git.src"
  rm -rf "$tmp"
  mkdir -p "$tmp"
  echo "ARDTT_INFO|скачиваем стек из $repo ($ref)"
  if command -v git >/dev/null 2>&1 && git clone --depth 1 --branch "$ref" "$repo" "$tmp" >/dev/null 2>&1; then
    :
  else
    local url tarball
    url="$(github_source_tarball_url "$repo" "$ref")"
    tarball="$INSTALL_DIR/repo.tar.gz"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    if ! command -v curl >/dev/null 2>&1; then
      die "git clone не удался и нет curl для $url"
    fi
    curl -fsSL --retry 3 --retry-delay 2 "$url" -o "$tarball" || die "Не удалось скачать $url"
    tar -xzf "$tarball" -C "$tmp"
    rm -f "$tarball"
  fi
  local tree
  if ! tree="$(find_server_tree "$tmp")"; then
    rm -rf "$tmp"
    die "В репозитории нет server/ (ref=$ref)"
  fi
  copy_server_tree_to_staging "$tree"
  rm -rf "$tmp"
}

cleanup_stale_deploy_files() {
  # Leftovers from older installer names, failed SSH drops, and agent probes.
  # Do not delete stack.staging here: it is the in-progress unpack.
  # Do not glob /tmp/ardtt-install.*.log: that is the live tee for this run.
  rm -f "$INSTALL_DIR/install-live.log" "$INSTALL_DIR/install-run.log"
  rm -f /var/log/ardtt-build*.log /var/log/ardtt-install.log
  rm -f /tmp/ardtt-entry-* /tmp/ardtt-cascade-* /tmp/ardtt-cascade-probe-*.sh
  rm -rf /tmp/ardtt-provision /tmp/ardtt-data-bak /var/tmp/ardtt-*
  rm -rf "$INSTALL_DIR/stack.old" "$INSTALL_DIR/unpack.src" "$INSTALL_DIR/git.src"
  # Leftover wg-quick conf from kernel-WG WARP. Do not ip-link-del warp0 here:
  # the live ardtt-warp may still own it until compose replaces the container.
  # Do not delete stack/data/warp — that is the live wgcf account.
  rm -f /etc/wireguard/warp0.conf
  rm -f /tmp/ardtt-warp-* "$INSTALL_DIR"/stack/data/warp/*.conf.tmp 2>/dev/null || true
}

reclaim_disk() {
  cleanup_docker_build_junk
  cleanup_host_packages
  cleanup_stale_deploy_files
  if ! foreign_docker_workloads; then
    docker container prune -f >/dev/null 2>&1 || true
  fi
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
  if [ "${COMPOSE_PROFILES:-isolated}" = "hostnet" ]; then
    echo "ardtt-host"
  else
    echo "ardtt"
  fi
}

role_up_services() {
  role_build_services
}

role_image_names() {
  echo "${COMPOSE_PROJECT:-stack}-ardtt"
}

ensure_cascade_keys() {
  local data="$1"
  mkdir -p "$data"
  if [ "$CASCADE_ENABLED" != "1" ] && [ "$ROLE" != "exit" ]; then
    return 0
  fi
  local img="${COMPOSE_PROJECT:-stack}-ardtt:latest"
  if ! docker image inspect "$img" >/dev/null 2>&1; then
    echo "ARDTT_WARN|нет образа $img — ключи каскада создаст контейнер"
    return 0
  fi
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

cleanup_install_artifacts() {
  rm -f "$INSTALL_DIR/stack.tar.gz" "$INSTALL_DIR/repo.tar.gz"
  rm -f /var/log/ardtt-build*.log /var/log/ardtt-install.log
  rm -rf /tmp/ardtt-data-bak "$INSTALL_DIR/stack.staging" "$INSTALL_DIR/stack.old" "$INSTALL_DIR/unpack.src" "$INSTALL_DIR/git.src"
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
  rm -rf /tmp/ardtt-data-bak 2>/dev/null || true
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

prog() { echo "ARDTT_PROGRESS|$1|$2"; }
die() { echo "ARDTT_ERROR|$*" >&2; exit 1; }

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
  # Never rewrite swap on a shared VPS — swapoff can freeze other services.
  if ! foreign_docker_workloads && [ -f /swapfile ] && [ "${file_mb:-0}" -gt $((need_mb + 96)) ] &&
     [ "${avail_mb:-0}" -lt 2200 ] && [ "${used_swap:-0}" -lt 400 ]; then
    echo "ARDTT_INFO|сжимаем swapfile ${file_mb} → ${need_mb} МБ (свободно ${avail_mb} МБ)"
    swapoff /swapfile 2>/dev/null || true
    rm -f /swapfile
    have_mb="$(swap_total_mb)"
    avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
  fi
  # /proc reports ~2047 for a 2G file — allow a small slack so we don't recreate.
  local min_ok=$((need_mb - 64))
  if [ "$min_ok" -lt 512 ]; then min_ok=512; fi
  if [ "${have_mb:-0}" -ge "$min_ok" ] 2>/dev/null; then
    echo "ARDTT_INFO|swap уже ${have_mb} МБ (цель ≥${need_mb})"
    return 0
  fi
  avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
  # Leave room for Docker images; never fill the rootfs with a swapfile.
  local reserve_mb="${MIN_DISK_UPDATE_MB:-500}"
  local max_swap=$(( ${avail_mb:-0} - reserve_mb ))
  if [ "$max_swap" -lt 512 ]; then
    echo "ARDTT_WARN|мало места для swap (свободно ${avail_mb:-0} МБ, нужно оставить ≥${reserve_mb}) — без увеличения"
    return 0
  fi
  if [ "$need_mb" -gt "$max_swap" ]; then
    echo "ARDTT_WARN|swap цель ${need_mb} МБ урезана до ${max_swap} МБ (диск ${avail_mb} МБ)"
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
      echo "ARDTT_WARN|не удалось создать swapfile — продолжаем без swap"
      rm -f "$swapfile"
      return 0
    fi
  fi
  chmod 600 "$swapfile"
  if ! mkswap "$swapfile" >/dev/null 2>&1 || ! swapon "$swapfile" 2>/dev/null; then
    echo "ARDTT_WARN|не удалось включить swap — продолжаем без него"
    rm -f "$swapfile"
    return 0
  fi
  if ! grep -qE "^/swapfile[[:space:]]" /etc/fstab 2>/dev/null; then
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
  fi
  have_mb="$(swap_total_mb)"
  echo "ARDTT_INFO|swap теперь ${have_mb} МБ"
}

stop_stack() {
  local dir="$1"
  if [ -f "$dir/docker-compose.yml" ]; then
    (cd "$dir" && COMPOSE_PROFILES=isolated,hostnet COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker compose down --remove-orphans) 2>/dev/null || \
      (cd "$dir" && COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" docker-compose down --remove-orphans) 2>/dev/null || true
  fi
  # shellcheck disable=SC2086
  docker rm -f $ARDTT_CONTAINER_NAMES >/dev/null 2>&1 || true
}

PROG_ROLE="вход (клиенты)"
[ "$ROLE" = "exit" ] && PROG_ROLE="выход (WARP)"
[ "$CASCADE_ENABLED" = "1" ] && [ "$ROLE" = "entry" ] && PROG_ROLE="вход + каскад на ${CASCADE_PEER_ENDPOINT:-?}"
prog 0.05 "Проверка прав · ${PROG_ROLE}"
if [ "${ARDTT_SKIP_ROOT_CHECK:-${NVPN_SKIP_ROOT_CHECK:-0}}" != "1" ] && [ "$(id -u)" -ne 0 ]; then
  die "Нужен root (или запуск через sudo)"
fi

[ -n "$PUBLIC_HOST" ] || die "ARDTT_PUBLIC_HOST не задан"
if [ "$ROLE" != "entry" ] && [ "$ROLE" != "exit" ]; then
  die "ARDTT_ROLE должен быть entry или exit"
fi

prog 0.10 "Подготовка каталога $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"
cleanup_stale_deploy_files

STAGING="$INSTALL_DIR/stack.staging"
if [ -f "$INSTALL_DIR/stack.tar.gz" ]; then
  # Unpack into staging while the OLD stack (if any) keeps running.
  # Accepts both a packed server/ tree and a GitHub source archive
  # (ARDTT-<tag>/server/…).
  prog 0.15 "Распаковка стека в staging (старый стек не останавливаем)"
  extract_archive_to_staging "$INSTALL_DIR/stack.tar.gz"
elif [ -f "$INSTALL_DIR/repo.tar.gz" ]; then
  prog 0.15 "Распаковка архива репозитория в staging"
  extract_archive_to_staging "$INSTALL_DIR/repo.tar.gz"
elif [ -f "$INSTALL_DIR/stack/docker-compose.yml" ]; then
  # Tar is deleted on every previous run. Allow a re-run against the already
  # unpacked tree so a failed compose build is recoverable without re-upload.
  prog 0.15 "Архив не найден — используем уже распакованный стек"
  rm -rf "$STAGING"
  cp -a "$INSTALL_DIR/stack" "$STAGING"
elif [ -n "$GIT_REF" ]; then
  prog 0.15 "Загрузка стека из GitHub ($GIT_REF)"
  fetch_stack_from_git "$GIT_REF"
else
  die "Нет стека: нужен stack.tar.gz с телефона или ARDTT_GIT_REF для загрузки из GitHub"
fi

[ -f "$STAGING/docker-compose.yml" ] || die "В архиве нет docker-compose.yml"

prog 0.22 "Проверка состава стека"
missing_contexts=""
for context in provision direct bypass dns warp telemetry-upload; do
  if [ ! -d "$STAGING/$context" ]; then
    missing_contexts="$missing_contexts $context"
  fi
done
if [ ! -f "$STAGING/Dockerfile" ] || [ ! -f "$STAGING/entrypoint.sh" ]; then
  missing_contexts="$missing_contexts Dockerfile/entrypoint.sh"
fi
if [ -n "$missing_contexts" ]; then
  die "Неполный архив деплоя, отсутствуют каталоги:${missing_contexts}. Скачайте стек из GitHub (ardtt-stack-*.tar.gz или тег релиза)"
fi

if [ "${ARDTT_DRY_RUN:-${NVPN_DRY_RUN:-0}}" != "1" ]; then
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
  echo "ARDTT_INFO|кэш сборки сброшен, свободно $(df -Pm / 2>/dev/null | awk 'NR==2{print $4}') МБ, RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo '?') МБ"
  mem_mb="$(mem_total_mb)"
  if [ "${mem_mb:-0}" -lt 1800 ] 2>/dev/null; then
    ensure_swap "$(swap_target_mb)"
  fi
fi

prog 0.40 "Запись .env (staging)"
DEPLOY_VERSION="${ARDTT_DEPLOY_VERSION:-${NVPN_DEPLOY_VERSION:-}}"
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
  # Provision treats a non-empty ARDTT_CASCADE_DNS as hop DNS even when
  # ARDTT_CASCADE_ENABLED=0. Keep it blank on a standalone entry.
  CASCADE_DNS=""
fi
if [ "$ROLE" = "exit" ]; then
  WARP_MODE="exit-hideip"
  WARP_DNS_IFACES="cascade0"
  WARP_HIDEIP_URL="http://10.10.0.1:9100/v1/hide-ip-prefixes"
  # Isolated compose publishes ARDTT_DIRECT_PORT. Exit listens on cascade UDP.
  DIRECT_PORT="$CASCADE_LISTEN_PORT"
fi
cat > "$STAGING/.env" <<EOF
ARDTT_PUBLIC_HOST=$PUBLIC_HOST
ARDTT_DIRECT_PORT=$DIRECT_PORT
ARDTT_BYPASS_PORT=$BYPASS_PORT
ARDTT_PROVISION_LISTEN=$PROVISION_LISTEN
ARDTT_PROVISION_PORT=9100
ARDTT_DEPLOY_VERSION=$DEPLOY_VERSION
ARDTT_WARP_GOMEMLIMIT=256MiB
TELEMETRY_LISTEN=0.0.0.0:${TELEMETRY_PORT}
ARDTT_TELEMETRY_LISTEN=0.0.0.0:${TELEMETRY_PORT}
ARDTT_TELEMETRY_PORT=${TELEMETRY_PORT}
ARDTT_SKIP_TELEMETRY=0
ARDTT_ROLE=$ROLE
ARDTT_CASCADE_ROLE=$ROLE
ARDTT_CASCADE_ENABLED=$CASCADE_ENABLED
ARDTT_CASCADE_LISTEN_PORT=$CASCADE_LISTEN_PORT
ARDTT_CASCADE_PEER_ENDPOINT=$CASCADE_PEER_ENDPOINT
ARDTT_CASCADE_PEER_PUBLIC_KEY=$CASCADE_PEER_PUBLIC_KEY
ARDTT_CASCADE_DNS=$CASCADE_DNS
ARDTT_BYPASS_DNS=$BYPASS_DNS
ARDTT_WARP_MODE=$WARP_MODE
ARDTT_WARP_DNS_IIFACES="$WARP_DNS_IFACES"
ARDTT_WARP_HIDEIP_URL=$WARP_HIDEIP_URL
ARDTT_NETWORK_MODE=$NETWORK_MODE
COMPOSE_PROFILES=$COMPOSE_PROFILES
COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT
EOF
load_stack_env "$STAGING/.env"

mkdir -p "$STAGING/data"
printf '%s\n' "$DEPLOY_VERSION" > "$STAGING/data/DEPLOY_VERSION"
chmod 700 "$STAGING/data"

if [ "${ARDTT_DRY_RUN:-${NVPN_DRY_RUN:-0}}" = "1" ]; then
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
  echo "ARDTT_DONE|dry_run=1|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$DEPLOY_VERSION|network_mode=$NETWORK_MODE|direct_port=$DIRECT_PORT|bypass_port=$BYPASS_PORT|cascade_listen_port=$CASCADE_LISTEN_PORT|auto_ports=$AUTO_PORTS"
  exit 0
fi

prog 0.45 "Проверка места перед сборкой образов"
reclaim_disk
echo "ARDTT_INFO|перед сборкой свободно $(df -Pm / 2>/dev/null | awk 'NR==2{print $4}') МБ, RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo '?') МБ"

avail_mb="$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')"
need_mb="$(disk_need_mb)"
echo "ARDTT_INFO|диск: свободно ${avail_mb:-?} МБ, порог обновления ${need_mb} МБ"
if [ -n "${avail_mb:-}" ] && [ "$avail_mb" -lt "$need_mb" ] 2>/dev/null; then
  die "Мало места на диске VPS: свободно ${avail_mb} МБ (нужно ≥${need_mb} МБ). Увеличьте диск или очистите: docker builder prune -af && apt-get clean"
fi
if [ -n "${avail_mb:-}" ] && [ "$avail_mb" -lt "$MIN_DISK_MB" ] 2>/dev/null; then
  echo "ARDTT_WARN|на диске ${avail_mb} МБ — пропускаем compose pull, собираем поверх существующих образов"
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
  if foreign_docker_workloads; then
    echo "ARDTT_WARN|не сбрасываем page cache — на хосте есть другие контейнеры"
  else
    echo 3 >/proc/sys/vm/drop_caches 2>/dev/null || true
  fi
  echo "ARDTT_INFO|после сброса RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo '?') МБ"
  prog 0.50 "Сборка единого образа"
else
  prog 0.48 "Очистка кэша сборки Docker"
  if foreign_docker_workloads; then
    echo "ARDTT_WARN|пропускаем builder prune -af — на хосте есть другие контейнеры"
  else
    docker builder prune -af >/dev/null 2>&1 || true
    docker buildx prune -af >/dev/null 2>&1 || true
  fi
  prog 0.50 "Сборка единого образа (старый стек ещё работает)"
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
  echo "ARDTT_INFO|compose pull пропущен (мало места)"
fi

# One service at a time. Do not builder-prune between images: that poisons the
# next golang Dockerfile (bypass after direct) on Docker 29 overlayfs.
# Never prune unused tagged images here: the new :latest is not used by a
# container yet, so a full prune would delete the image we just built.
export BUILDKIT_MAX_PARALLELISM="${BUILDKIT_MAX_PARALLELISM:-1}"

BUILD_SERVICES="$(role_build_services)"
BUILD_LOG="$(mktemp /tmp/ardtt-compose-build.XXXXXX.log)"
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
    echo "ARDTT_WARN|сборка $svc не удалась — сброс BuildKit и повтор без кэша"
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
  echo "ARDTT_INFO|после $svc свободно $(df -Pm / | awk 'NR==2{print $4}') МБ, RAM avail $(awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo) МБ"
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
cleanup_host_dataplane
modprobe tun 2>/dev/null || true

# Preserve live data from the previous stack.
if [ -d "$STACK/data" ]; then
  rm -rf /tmp/ardtt-data-bak
  cp -a "$STACK/data" /tmp/ardtt-data-bak
fi

rm -rf "$INSTALL_DIR/stack.old"
if [ -d "$STACK" ]; then
  mv "$STACK" "$INSTALL_DIR/stack.old"
fi
mv "$STAGING" "$STACK"
STAGING=""

if [ -d /tmp/ardtt-data-bak ]; then
  mkdir -p "$STACK/data"
  cp -a /tmp/ardtt-data-bak/. "$STACK/data/"
  rm -rf /tmp/ardtt-data-bak
fi
# Carry .env we wrote in staging (already inside $STACK after mv).
chmod 700 "$STACK/data" 2>/dev/null || true
migrate_bypass_volume "$STACK/data"
mkdir -p "$STACK/data/wdtt" "$STACK/data/warp"

if [ "$CASCADE_ENABLED" = "1" ] || [ "$ROLE" = "exit" ]; then
  prog 0.76 "Ключи каскадного AWG"
  ensure_cascade_keys "$STACK/data"
fi

drop_legacy_containers

prog 0.77 "Проверка портов на хосте"
SKIP_TELEMETRY=0
TELEMETRY_HOST_PUBLISH=1
if tcp_listen_port "$TELEMETRY_PORT"; then
  # Distribution nginx typically owns :9200 TLS and proxies /api to :9199.
  if [ "$TELEMETRY_PORT" = "9200" ] && ! tcp_listen_port 9199; then
    TELEMETRY_PORT=9199
    echo "ARDTT_INFO|host :9200 занят (nginx) — telemetry backend на :9199"
  else
    echo "ARDTT_INFO|host :9200 и :9199 заняты — telemetry только внутри контейнера (nginx/socat)"
    drop_telemetry_host_publish "$STACK/docker-compose.yml"
    TELEMETRY_HOST_PUBLISH=0
  fi
fi
if [ "$ROLE" = "exit" ]; then
  sed -i '/ARDTT_BYPASS_PORT.*udp/d' "$STACK/docker-compose.yml" 2>/dev/null || true
  CASCADE_LISTEN_PORT="$(resolve_udp_host_port "$CASCADE_LISTEN_PORT" "каскад AmneziaWG")"
  DIRECT_PORT="$CASCADE_LISTEN_PORT"
else
  DIRECT_PORT="$(resolve_udp_host_port "$DIRECT_PORT" "Direct AmneziaWG")"
  BYPASS_PORT="$(resolve_udp_host_port "$BYPASS_PORT" "Bypass RAW" "$DIRECT_PORT")"
fi
require_host_port tcp 9100 "provision /health"
env_set_key "$STACK/.env" ARDTT_DIRECT_PORT "$DIRECT_PORT"
env_set_key "$STACK/.env" ARDTT_BYPASS_PORT "$BYPASS_PORT"
env_set_key "$STACK/.env" ARDTT_CASCADE_LISTEN_PORT "$CASCADE_LISTEN_PORT"
env_set_key "$STACK/.env" ARDTT_TELEMETRY_PORT "$TELEMETRY_PORT"
export ARDTT_DIRECT_PORT="$DIRECT_PORT"
export ARDTT_BYPASS_PORT="$BYPASS_PORT"
export ARDTT_CASCADE_LISTEN_PORT="$CASCADE_LISTEN_PORT"
export ARDTT_TELEMETRY_PORT="$TELEMETRY_PORT"
if [ "$AUTO_PORTS" = "1" ]; then
  echo "ARDTT_INFO|порты: Direct=${DIRECT_PORT}/udp Bypass=${BYPASS_PORT}/udp cascade_listen=${CASCADE_LISTEN_PORT}/udp (auto=${AUTO_PORTS})"
fi
if [ "$SKIP_TELEMETRY" = 1 ]; then
  sed -i 's/^ARDTT_SKIP_TELEMETRY=.*/ARDTT_SKIP_TELEMETRY=1/' "$STACK/.env"
fi
export ARDTT_SKIP_TELEMETRY="$SKIP_TELEMETRY"

prog 0.78 "Запуск единого контейнера"
cd "$STACK"
load_stack_env "$STACK/.env"
export COMPOSE_PROFILES="${COMPOSE_PROFILES:-isolated}"
UP_SERVICES="$(role_up_services)"
UP_LOG="$(mktemp /tmp/ardtt-compose-up.XXXXXX.log)"
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
if curl -fsS "http://127.0.0.1:9100/health" >/dev/null 2>&1 || \
   docker exec ardtt curl -fsS "http://127.0.0.1:9100/health" >/dev/null 2>&1; then
  prog 0.92 "provision /health OK"
else
  echo "ARDTT_WARN|provision /health пока не ответил — проверьте: docker compose -f $STACK/docker-compose.yml --profile ${COMPOSE_PROFILES} logs"
fi
if [ "$SKIP_TELEMETRY" != 1 ]; then
  inner_telemetry_port="$(env_file_val "$STACK/.env" TELEMETRY_LISTEN)"
  inner_telemetry_port="${inner_telemetry_port##*:}"
  [ -n "$inner_telemetry_port" ] || inner_telemetry_port="$TELEMETRY_PORT"
  if [ "$TELEMETRY_HOST_PUBLISH" = 1 ] && \
     curl -fsS --max-time 3 "http://127.0.0.1:${TELEMETRY_PORT}/health" >/dev/null 2>&1; then
    prog 0.93 "telemetry /health OK"
  elif docker exec ardtt curl -fsS --max-time 3 "http://127.0.0.1:${inner_telemetry_port}/health" >/dev/null 2>&1; then
    prog 0.93 "telemetry /health OK (внутри контейнера)"
  else
    echo "ARDTT_WARN|telemetry не отвечает — логи тестирования не примут"
  fi
fi
if docker inspect -f '{{.State.Running}}' ardtt 2>/dev/null | grep -qx true; then
  prog 0.935 "контейнер ardtt running"
else
  echo "ARDTT_WARN|контейнер ardtt не запущен — cd $STACK && docker compose --env-file .env --profile ${COMPOSE_PROFILES} up -d"
fi
if docker exec ardtt test -s /data/users.json 2>/dev/null; then
  prog 0.94 "provision видит /data/users.json"
else
  echo "ARDTT_WARN|provision не видит /data/users.json — контейнер, скорее всего, на старом inode. Выполните: cd $STACK && docker compose --profile ${COMPOSE_PROFILES} up -d --force-recreate"
fi

host_allow_port() {
  local proto="$1" port="$2"
  if command -v ufw >/dev/null 2>&1; then
    ufw allow "${port}/${proto}" || true
  fi
  if command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --add-port="${port}/${proto}" --permanent || true
  fi
  # Raw iptables -I INPUT only in host netns: isolated mode uses Docker publish.
  if [ "${COMPOSE_PROFILES}" = "hostnet" ] && command -v iptables >/dev/null 2>&1; then
    if [ "$proto" = udp ]; then
      iptables -C INPUT -p udp --dport "$port" -j ACCEPT 2>/dev/null || \
        iptables -I INPUT -p udp --dport "$port" -j ACCEPT || true
    else
      iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null || \
        iptables -I INPUT -p tcp --dport "$port" -j ACCEPT || true
    fi
  fi
}

prog 0.96 "Открытие портов (best-effort)"
if [ "$ROLE" = "exit" ]; then
  host_allow_port udp "$CASCADE_LISTEN_PORT"
else
  host_allow_port udp "$DIRECT_PORT"
  host_allow_port udp "$BYPASS_PORT"
fi
host_allow_port tcp 9100
if [ "$SKIP_TELEMETRY" != 1 ] && [ "${TELEMETRY_HOST_PUBLISH:-1}" = 1 ]; then
  host_allow_port tcp "$TELEMETRY_PORT"
fi
if command -v firewall-cmd >/dev/null 2>&1; then
  firewall-cmd --reload || true
fi

prog 1.00 "Готово"
echo "ARDTT_DONE|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$DEPLOY_VERSION|telemetry_port=$TELEMETRY_PORT|role=$ROLE|cascade=$CASCADE_ENABLED|network_mode=$NETWORK_MODE|direct_port=$DIRECT_PORT|bypass_port=$BYPASS_PORT|cascade_listen_port=$CASCADE_LISTEN_PORT|auto_ports=$AUTO_PORTS"
echo "Создать пользователя: docker exec ardtt provision -cmd create-user -name USER -data /data"

