#!/usr/bin/env bash
# Deploy lab for a disposable VPS (self-hosted runner, label ardtt-lab).
# Checks that ARDTT installs as one container and leaves everything else on the
# host alone: foreign containers, networks, images, published ports, iptables,
# ip rules, Docker configuration.
#
# Scenarios: full | snapshot | neighbors | install-published | install-tree |
#            uninstall | cleanup
# Every scenario prints «LAB PASS|…» / «LAB FAIL|…» lines; exit 1 on any FAIL.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LAB="${ARDTT_LAB_DIR:-/var/lib/ardtt-lab}"
INSTALL_DIR=/opt/ardtt
mkdir -p "$LAB"
FAILS=0

pass() { echo "LAB PASS|$*"; }
fail() { echo "LAB FAIL|$*"; FAILS=$((FAILS + 1)); }
step() { echo; echo "===== $* ====="; }

as_root() {
  if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo -E "$@"; fi
}

public_ip() {
  curl -fsS -m 8 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}'
}

have_docker() { command -v docker >/dev/null 2>&1 && as_root docker info >/dev/null 2>&1; }

snapshot() {
  local out="$1"
  {
    echo "## os"; . /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-?} kernel=$(uname -r)"
    echo "## containers (non-ardtt)"
    have_docker && as_root docker ps -a --format '{{.ID}} {{.Names}} {{.Status}} {{.Ports}}' \
      | grep -v -i ardtt | sed -E 's/Up [^(]*/Up/; s/\(healthy\)|\(health: starting\)//' | sort
    echo "## networks (non-ardtt)"
    have_docker && as_root docker network ls --format '{{.ID}} {{.Name}} {{.Driver}}' | grep -v -i ardtt | sort
    echo "## images (non-ardtt)"
    have_docker && as_root docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' \
      | grep -v -E '^(ardtt|stack-ardtt)' | sort
    echo "## host ip rules"; ip rule show
    echo "## host routes (non-docker)"; ip route show | grep -v -E 'br-|docker'
    echo "## host links (non-veth/br)"; ip -o link show | awk -F': ' '{print $2}' | grep -v -E '^(veth|br-)' | sed 's/@.*//' | sort
    echo "## sysctl"; sysctl -n net.ipv4.ip_forward 2>/dev/null
    echo "## iptables filter (non-ardtt)"
    as_root iptables-save -t filter 2>/dev/null | grep -v -E '^#|^:' | grep -v -i ardtt \
      | sed -E 's/br-[0-9a-f]+/br-X/g' | sort
    echo "## docker daemon.json"; cat /etc/docker/daemon.json 2>/dev/null || echo none
    echo "## docker units"; ls /etc/systemd/system/ 2>/dev/null | grep -i -E 'docker|containerd' || echo none
  } >"$out" 2>/dev/null
}

# Neighbours: the kind of services a shared VPS already runs.
neighbors() {
  have_docker || { fail "neighbors: Docker не работает"; return; }
  as_root docker rm -f lab-web lab-wg lab-db >/dev/null 2>&1 || true
  as_root docker network rm lab-net >/dev/null 2>&1 || true
  as_root docker run -d --name lab-web -p 8080:80 --restart unless-stopped busybox:1.36 \
    sh -c 'mkdir -p /w && echo lab-ok > /w/index.html && httpd -f -p 80 -h /w' >/dev/null \
    || fail "neighbors: lab-web"
  # Takes the ARDTT Direct port on purpose: the installer must pick another one.
  as_root docker run -d --name lab-wg -p 51820:51820/udp --restart unless-stopped busybox:1.36 \
    sh -c 'nc -lu -p 51820 >/dev/null; sleep 1000000' >/dev/null || fail "neighbors: lab-wg"
  as_root docker network create --subnet 10.112.0.0/24 lab-net >/dev/null || fail "neighbors: lab-net"
  as_root docker run -d --name lab-db --network lab-net --restart unless-stopped busybox:1.36 \
    sleep 1000000 >/dev/null || fail "neighbors: lab-db"
  # A foreign Compose project named «stack», like the legacy ARDTT layout.
  mkdir -p "$LAB/stack"
  cat >"$LAB/stack/docker-compose.yml" <<'EOF'
services:
  app:
    image: busybox:1.36
    command: sleep 1000000
    restart: unless-stopped
EOF
  (cd "$LAB/stack" && as_root env COMPOSE_PROJECT_NAME=stack docker compose up -d >/dev/null 2>&1) \
    || fail "neighbors: compose stack"
  sleep 3
  if curl -fsS -m 5 http://127.0.0.1:8080/ | grep -q lab-ok; then pass "neighbors: веб-сосед отвечает"; else fail "neighbors: веб-сосед не отвечает"; fi
}

neighbors_alive() {
  local tag="$1" n
  for n in lab-web lab-wg lab-db stack-app-1; do
    if [ "$(as_root docker inspect -f '{{.State.Running}}' "$n" 2>/dev/null)" = "true" ]; then
      pass "${tag}: ${n} работает"
    else
      fail "${tag}: ${n} не работает"
    fi
  done
  if curl -fsS -m 5 http://127.0.0.1:8080/ | grep -q lab-ok; then pass "${tag}: веб-сосед отвечает"; else fail "${tag}: веб-сосед не отвечает"; fi
}

compare() {
  local tag="$1" a="$2" b="$3"
  if diff -u "$a" "$b" >"$LAB/diff-${tag}.txt"; then
    pass "${tag}: состояние хоста вне ARDTT не изменилось"
  else
    fail "${tag}: состояние хоста изменилось"
    cat "$LAB/diff-${tag}.txt"
  fi
}

ardtt_ready() {
  local tag="$1" name
  name="$(grep -s '^ARDTT_CONTAINER_NAME=' "$INSTALL_DIR/.env" | cut -d= -f2)"
  name="${name:-ardtt}"
  if as_root docker exec "$name" bash /opt/ardtt/ready.sh; then
    pass "${tag}: контейнер ${name} готов"
  else
    fail "${tag}: контейнер ${name} не готов"
    as_root docker logs --tail 60 "$name" 2>&1 | tail -60
  fi
  local count
  count="$(as_root docker ps -a --filter label=com.ardtt.owner=ardtt --format '{{.Names}}' | wc -l)"
  if [ "$count" = "1" ]; then pass "${tag}: ровно один контейнер ARDTT"; else fail "${tag}: контейнеров ARDTT: ${count}"; fi
}

run_logged() {
  local log="$1"; shift
  "$@" 2>&1 | tee "$log" | grep -E --line-buffered 'ARDTT_(PROGRESS|WARN|ERROR|DONE)|^\{"protocol":2,"type":"error"'
  return "${PIPESTATUS[0]}"
}

install_published() {
  local ip; ip="$(public_ip)"
  cp "$ROOT/server/fetch-and-install.sh" "$LAB/fetch-and-install.sh"
  if run_logged "$LAB/install-published.log" as_root env ARDTT_PUBLIC_HOST="$ip" ARDTT_AUTO_PORTS=1 \
      ARDTT_ROLE=entry ARDTT_CASCADE_ENABLED=0 bash "$LAB/fetch-and-install.sh"; then
    pass "install-published: опубликованный стек установлен"
    ardtt_ready install-published
  else
    fail "install-published: установка опубликованного стека не прошла"
  fi
}

ensure_buildx() {
  as_root docker buildx version >/dev/null 2>&1 && return 0
  local arch=amd64 v=v0.19.3
  [ "$(uname -m)" = aarch64 ] && arch=arm64
  as_root mkdir -p /usr/local/lib/docker/cli-plugins
  as_root curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-buildx \
    "https://github.com/docker/buildx/releases/download/${v}/buildx-${v}.linux-${arch}" \
    && as_root chmod 755 /usr/local/lib/docker/cli-plugins/docker-buildx
}

install_tree() {
  have_docker || { fail "install-tree: Docker не работает"; return; }
  local ver ws pkg sha ip
  ip="$(public_ip)"
  ws="$LAB/tree"
  as_root rm -rf "$ws"; cp -a "$ROOT" "$ws"
  ver="$(tr -d '[:space:]' <"$ws/server/DEPLOY_VERSION")-lab"
  printf '%s\n' "$ver" >"$ws/server/DEPLOY_VERSION"
  ensure_buildx || { fail "install-tree: нет buildx"; return; }
  echo "build ${ver}…"
  if ! as_root bash "$ws/scripts/build-server-image.sh" >"$LAB/build.log" 2>&1; then
    fail "install-tree: сборка образа"; tail -40 "$LAB/build.log"; return
  fi
  pkg="$LAB/ardtt-server-${ver}.tar.gz"
  if ! as_root bash "$ws/scripts/pack-server-package.sh" "$pkg" >"$LAB/pack.log" 2>&1; then
    fail "install-tree: упаковка"; tail -40 "$LAB/pack.log"; return
  fi
  sha="$(sha256sum "$pkg" | awk '{print $1}')"
  if run_logged "$LAB/install-tree.log" as_root env ARDTT_PUBLIC_HOST="$ip" ARDTT_AUTO_PORTS=1 \
      ARDTT_ROLE=entry ARDTT_CASCADE_ENABLED=0 ARDTT_PACKAGE="$pkg" ARDTT_PACKAGE_SHA256="$sha" \
      bash "$ws/server/install.sh"; then
    pass "install-tree: стек из ветки установлен (${ver})"
    ardtt_ready install-tree
  else
    fail "install-tree: установка стека из ветки не прошла"
  fi
}

uninstall() {
  local sh="$INSTALL_DIR/current/install.sh"
  [ -f "$sh" ] || sh="$ROOT/server/install.sh"
  if run_logged "$LAB/uninstall.log" as_root env ARDTT_ACTION=uninstall ARDTT_PURGE_DATA=1 bash "$sh"; then
    pass "uninstall: ARDTT снят"
  else
    fail "uninstall: снятие не прошло"
  fi
  local left
  left="$(as_root docker ps -a --filter label=com.ardtt.owner=ardtt -q | wc -l)"
  [ "$left" = "0" ] && pass "uninstall: контейнеров ARDTT не осталось" || fail "uninstall: осталось контейнеров ARDTT: ${left}"
}

cleanup() {
  as_root docker rm -f lab-web lab-wg lab-db >/dev/null 2>&1 || true
  (cd "$LAB/stack" 2>/dev/null && as_root env COMPOSE_PROJECT_NAME=stack docker compose down >/dev/null 2>&1) || true
  as_root docker network rm lab-net >/dev/null 2>&1 || true
  pass "cleanup: соседи сняты"
}

diag() {
  echo "## uptime"; uptime
  echo "## memory"; free -m
  echo "## disk"; df -h / /var/lib 2>/dev/null
  echo "## oom / kills (dmesg)"; as_root dmesg -T 2>/dev/null | grep -i -E 'oom|killed process|out of memory' | tail -20
  echo "## docker"; as_root systemctl status docker --no-pager 2>&1 | head -12
  have_docker && as_root docker ps -a --format '{{.Names}} {{.Status}} {{.Image}}'
  echo "## runner journal"; as_root journalctl -u 'actions.runner.*' --no-pager -n 40 2>/dev/null
  echo "## /opt/ardtt"; ls -la /opt/ardtt 2>/dev/null; cat /opt/ardtt/install.log 2>/dev/null | tail -60
  echo "## lab logs"; ls -la "$LAB" 2>/dev/null
  local f
  for f in "$LAB"/install-published.log "$LAB"/install-tree.log; do
    [ -f "$f" ] || continue
    echo "### $f"; grep -v '^{"protocol"' "$f" | tail -50
  done
}

scenario="${1:-full}"
case "$scenario" in
  snapshot) snapshot "$LAB/snap.txt"; cat "$LAB/snap.txt" ;;
  neighbors) neighbors ;;
  install-published) install_published ;;
  install-tree) install_tree ;;
  uninstall) uninstall ;;
  cleanup) cleanup ;;
  diag) diag ;;
  full)
    step "1. Чистый сервер"
    snapshot "$LAB/0-clean.txt"; cat "$LAB/0-clean.txt"
    step "2. Установка опубликованного стека на чистый сервер"
    install_published
    step "3. Соседи"
    neighbors
    snapshot "$LAB/1-before.txt"
    step "4. Обновление стеком из ветки (порты заняты соседями)"
    install_tree
    snapshot "$LAB/2-after-update.txt"
    compare update "$LAB/1-before.txt" "$LAB/2-after-update.txt"
    neighbors_alive update
    step "5. Удаление ARDTT"
    uninstall
    snapshot "$LAB/3-after-uninstall.txt"
    compare uninstall "$LAB/1-before.txt" "$LAB/3-after-uninstall.txt"
    neighbors_alive uninstall
    ;;
  *) echo "unknown scenario ${scenario}" >&2; exit 2 ;;
esac

echo
echo "LAB SUMMARY|failures=${FAILS}"
[ "$FAILS" -eq 0 ]
