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

# The lab log is public (GitHub Actions of a public repo): hide tokens and secrets.
redact() {
  sed -u -E 's/((admin|telemetry|cascade|upload|review)_?(token|secret)[=:"]+ *)[A-Za-z0-9+\/=_-]{8,}/\1***/Ig'
}
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
    # Rules of ARDTT's own bridge (network labelled com.ardtt.owner=ardtt) are
    # ARDTT's, not a change to the host: drop them before bridge names are masked.
    local own_br="^$"
    if have_docker; then
      local ids
      ids="$(as_root docker network ls -q --no-trunc --filter label=com.ardtt.owner=ardtt \
        | cut -c1-12 | sed 's/^/br-/' | paste -sd'|' -)"
      [ -n "$ids" ] && own_br="($ids)"
    fi
    as_root iptables-save -t filter 2>/dev/null | grep -v -E '^#|^:' | grep -v -i ardtt \
      | grep -v -E "$own_br" | sed -E 's/br-[0-9a-f]+/br-X/g' | sort
    echo "## docker daemon.json"; cat /etc/docker/daemon.json 2>/dev/null || echo none
    echo "## docker units"; ls /etc/systemd/system/ 2>/dev/null | grep -i -E 'docker|containerd' || echo none
  } >"$out" 2>/dev/null
}

# Neighbours: the kind of services a shared VPS already runs.
neighbors() {
  have_docker || { fail "neighbors: Docker не работает"; return; }
  as_root docker rm -f lab-web lab-wg lab-db stack-app-1 >/dev/null 2>&1 || true
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
  # A foreign Compose project named «stack», like the legacy ARDTT layout. The host
  # has no Compose plugin (ARDTT keeps its Compose CLI private), so the container
  # carries the labels Compose would set: that is all the installer looks at.
  as_root docker run -d --name stack-app-1 --restart unless-stopped \
    --label com.docker.compose.project=stack --label com.docker.compose.service=app \
    --label com.docker.compose.container-number=1 --label com.docker.compose.oneoff=False \
    busybox:1.36 sleep 1000000 >/dev/null || fail "neighbors: compose stack"
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

DEPLOY="$ROOT/server/deploy/ardtt-deploy.sh"
# Deploy 1.0.x as users have it today (the last published fetcher): the v2
# installer must migrate this install in place.
V1_FETCHER_URL="https://raw.githubusercontent.com/2kristalls36-hue/ARDTT/2d63079a7c766b679114ce0d0c90bbd24dac1585/server/fetch-and-install.sh"

ardtt_ready() {
  local tag="$1" name
  name="$(sed -n 's/^CONTAINER=//p' "$INSTALL_DIR/instance.env" 2>/dev/null)"
  [ -n "$name" ] || name="$(grep -s '^ARDTT_CONTAINER_NAME=' "$INSTALL_DIR/.env" | cut -d= -f2)"
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
  "$@" 2>&1 | redact | tee "$log" | grep -E --line-buffered 'ARDTT_(PROGRESS|WARN|ERROR|DONE|UNINSTALLED)|^\{"protocol":2,"type":"error"'
  return "${PIPESTATUS[0]}"
}

done_field() { sed -n "s/^ARDTT_DONE|.*|$2=\([^|]*\).*/\1/p" "$1" | head -n 1; }

install_published_v1() {
  local ip; ip="$(public_ip)"
  curl -fsSL -o "$LAB/fetch-and-install.sh" "$V1_FETCHER_URL" || { fail "install-v1: нет установщика 1.0.x"; return; }
  if run_logged "$LAB/install-v1.log" as_root env ARDTT_PUBLIC_HOST="$ip" ARDTT_AUTO_PORTS=1 \
      ARDTT_ROLE=entry ARDTT_CASCADE_ENABLED=0 ARDTT_DEPLOY_VERSION=1.0.57 bash "$LAB/fetch-and-install.sh"; then
    pass "install-v1: опубликованный стек 1.0.57 установлен"
    ardtt_ready install-v1
  else
    fail "install-v1: установка 1.0.57 не прошла"
  fi
}

lab_image() {
  local pkg="${ARDTT_LAB_PACKAGE:-}"
  [ -n "$pkg" ] && [ -f "$pkg" ] || return 1
  printf '%s' "$pkg"
}

lab_version() { basename "$1" | sed -n 's/^ardtt-server-\(.*\)-linux-[a-z0-9]*\.image\.tar\.gz$/\1/p'; }

# install_v2 <tag>: the branch image file with this tree's ardtt-deploy.sh.
install_v2() {
  have_docker || { fail "${1}: Docker не работает"; return; }
  local tag="$1" pkg ver ip
  pkg="$(lab_image)" || { fail "${tag}: нет образа из ветки (ARDTT_LAB_PACKAGE)"; return; }
  ver="$(lab_version "$pkg")"
  ip="$(public_ip)"
  if run_logged "$LAB/${tag}.log" as_root env ARDTT_PUBLIC_HOST="$ip" ARDTT_AUTO_PORTS=1 ARDTT_ROLE=entry \
      ARDTT_CASCADE_ENABLED=0 ARDTT_DEPLOY_VERSION="$ver" ARDTT_IMAGE_FILE="$pkg" bash "$DEPLOY"; then
    pass "${tag}: стек ${ver} установлен"
    ardtt_ready "$tag"
  else
    fail "${tag}: установка ${ver} не прошла"
  fi
}

uninstall() {
  if run_logged "$LAB/uninstall.log" as_root env ARDTT_ACTION=uninstall ARDTT_PURGE_DATA=1 bash "$DEPLOY"; then
    pass "uninstall: ARDTT снят"
  else
    fail "uninstall: снятие не прошло"
  fi
  local left
  left="$(as_root docker ps -a --filter label=com.ardtt.owner=ardtt -q | wc -l)"
  [ "$left" = "0" ] && pass "uninstall: контейнеров ARDTT не осталось" || fail "uninstall: осталось контейнеров ARDTT: ${left}"
  [ ! -e "$INSTALL_DIR" ] && pass "uninstall: ${INSTALL_DIR} удалён" || fail "uninstall: ${INSTALL_DIR} остался"
}

ardtt_subnet() {
  local id
  id="$(as_root docker network ls -q --filter label=com.ardtt.owner=ardtt | head -1)"
  [ -n "$id" ] || return 0
  as_root docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' "$id" 2>/dev/null
}

cleanup() {
  as_root docker rm -f lab-web lab-wg lab-db stack-app-1 >/dev/null 2>&1 || true
  as_root docker network rm lab-net >/dev/null 2>&1 || true
  pass "cleanup: соседи сняты"
  # Lab leftovers on this dedicated test VPS (never part of an ARDTT deploy):
  # BuildKit cache of the first run that built the image here, old tree copies.
  as_root docker builder prune -af >/dev/null 2>&1 || true
  rm -rf "$LAB/tree" "$LAB/stack" "$LAB/build.log" "$LAB/pack.log" 2>/dev/null || true
  local keep
  keep="$(cd "$ROOT" && pwd -P)"
  for d in "$LAB"/src-*; do
    [ -d "$d" ] || continue
    [ "$(cd "$d" && pwd -P)" = "$keep" ] && continue
    rm -rf "$d"
  done
  pass "cleanup: хвосты лабы убраны ($(df -h / | awk 'NR==2 {print $4}') свободно)"
}

# Read-only check of a live install (the log is public: no keys, tokens or
# public addresses — only counters, states and private addresses).
mask() {
  redact | sed -E 's/\b([0-9]{1,3}\.){3}[0-9]{1,3}\b/<ip>/g; s#[A-Za-z0-9+/]{42,44}=#<key>#g'
}
keep_private() {
  # Every IPv4 address outside 10/8, 172.16/12, 192.168/16, 127/8 becomes <ip>.
  redact | perl -pe 's/\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b/
    ($1==10 || $1==127 || ($1==172 && $2>=16 && $2<=31) || ($1==192 && $2==168) || ($1==0)) ? "$1.$2.$3.$4" : "<ip>"/gex;
    s#[A-Za-z0-9+\/]{42,44}=#<key>#g'
}
vpn_diag() {
  local c
  c="$(sed -n 's/^CONTAINER=//p' "$INSTALL_DIR/instance.env" 2>/dev/null)"; c="${c:-ardtt}"
  echo "## instance.env (ports/role)"; grep -E '^(VERSION|ROLE|DIRECT_PORT|BYPASS_PORT|PROVISION_PORT|PROVISION_PUBLIC|CASCADE_ENABLED|SUBNET)=' "$INSTALL_DIR/instance.env" 2>/dev/null
  echo "## container"; as_root docker ps -a --filter "name=^${c}$" --format '{{.Names}} {{.Status}} {{.Image}} {{.Ports}}' | mask
  echo "## ready.sh"; as_root docker exec "$c" bash /opt/ardtt/ready.sh 2>&1 | tail -3
  echo "## awg0 (counts only)"
  as_root docker exec "$c" sh -c '
    awg show awg0 listen-port 2>&1 | sed "s/^/listen-port /"
    echo "peers $(awg show awg0 peers 2>/dev/null | wc -l)"
    awg show awg0 latest-handshakes 2>/dev/null | awk "{ if (\$2 > 0) h++ } END { print \"peers-with-handshake \" (h+0) }"
    awg show awg0 transfer 2>/dev/null | awk "{ rx += \$2; tx += \$3 } END { print \"transfer rx=\" rx+0 \" tx=\" tx+0 }"
    awg show awg0 2>/dev/null | grep -E "^ *(jc|jmin|jmax|s1|s2|h1|h2|h3|h4|i[0-9]?)" | sed "s/^ */param /"
    ip -br addr show awg0 2>&1
  ' 2>&1 | keep_private
  echo "## users.json (counts only)"
  as_root docker exec "$c" sh -c 'f=/data/users.json; [ -f "$f" ] && echo "users $(grep -o "\"name\"" "$f" | wc -l)"; ls -la /data | head -30' 2>&1 | keep_private
  echo "## host: who listens on UDP"; as_root ss -ulnp 2>/dev/null | awk 'NR>1 {print $5, $7}' | keep_private | sort -u
  echo "## host NAT for the published ports (packet counters)"
  as_root iptables -t nat -L DOCKER -v -n 2>/dev/null | grep -E 'dpt:(51820|51821|56003|9100)' | keep_private
  echo "## host filter DOCKER counters"
  as_root iptables -L DOCKER -v -n 2>/dev/null | grep -E 'dpt:(51820|56003|9100)' | keep_private
  echo "## host firewall"
  as_root sh -c 'command -v ufw >/dev/null && ufw status 2>&1 | head -5; nft list ruleset 2>/dev/null | grep -c . | sed "s/^/nft-lines /"; iptables -S INPUT 2>/dev/null | head -10' | keep_private
  echo "## capture 15 s of UDP to the Direct port on the host (counts)"
  if command -v tcpdump >/dev/null 2>&1; then
    local dp; dp="$(sed -n 's/^DIRECT_PORT=//p' "$INSTALL_DIR/instance.env")"
    as_root timeout 15 tcpdump -ni any -c 50 "udp port ${dp:-51821}" 2>&1 | keep_private | tail -15
  else
    echo "no tcpdump"
  fi
  echo "## container log (last 120, masked)"; as_root docker logs --tail 120 "$c" 2>&1 | keep_private
}

diag() {
  echo "## uptime"; uptime
  echo "## memory"; free -m
  echo "## disk"; df -h / /var/lib 2>/dev/null
  echo "## disk: biggest dirs"
  as_root du -xsh /var/lib/docker /var/lib/containerd /var/lib/ardtt* /opt/ardtt* /opt/* \
    /home/*/actions-runner /home/*/actions-runner/_work /root /var/log /var/cache/apt /tmp /usr /snap 2>/dev/null \
    | sort -rh | head -25
  if have_docker; then
    echo "## docker system df"; as_root docker system df 2>/dev/null
    echo "## docker images -a"; as_root docker images -a --format '{{.Repository}}:{{.Tag}} {{.ID}} {{.Size}} {{.CreatedSince}}' 2>/dev/null
    echo "## docker root"; as_root docker info -f '{{.DockerRootDir}}' 2>/dev/null
  fi
  echo "## oom / kills (dmesg)"; as_root dmesg -T 2>/dev/null | grep -i -E 'oom|killed process|out of memory' | tail -20
  echo "## docker"; as_root systemctl status docker --no-pager 2>&1 | head -12
  have_docker && as_root docker ps -a --format '{{.Names}} {{.Status}} {{.Image}}'
  echo "## runner journal"; as_root journalctl -u 'actions.runner.*' --no-pager -n 40 2>/dev/null
  echo "## /opt/ardtt"; ls -la /opt/ardtt 2>/dev/null; redact </opt/ardtt/install.log 2>/dev/null | tail -60
  echo "## lab logs"; ls -la "$LAB" 2>/dev/null
  if [ -f "$LAB/last-run.log" ]; then
    echo "### last detached run"; redact <"$LAB/last-run.log" | tail -120
  fi
  local f
  for f in "$LAB"/install-published.log "$LAB"/install-tree.log "$LAB"/update.log; do
    [ -f "$f" ] || continue
    echo "### $f"; grep -v '^{"protocol"' "$f" | redact | tail -50
  done
}

scenario="${1:-full}"
case "$scenario" in
  snapshot) snapshot "$LAB/snap.txt"; cat "$LAB/snap.txt" ;;
  neighbors) neighbors ;;
  install-published) install_published_v1 ;;
  install-tree) install_v2 install-v2 ;;
  uninstall) uninstall ;;
  cleanup) cleanup ;;
  diag) diag ;;
  vpn-diag) vpn_diag ;;
  full)
    step "0. Снять ARDTT и соседей прошлого прогона"
    [ -e "$INSTALL_DIR" ] && uninstall >/dev/null 2>&1
    cleanup
    step "1. Соседи (один держит UDP 51820)"
    neighbors
    snapshot "$LAB/1-before.txt"
    step "2. Деплой 1.0.57, как он стоит у пользователей"
    install_published_v1
    v1_instance="$(json_inst="$(cat "$INSTALL_DIR/instance.json" 2>/dev/null)"; printf '%s' "$json_inst" | tr -d '\n' | sed -n 's/.*"instanceId"[^"]*"\([^"]*\)".*/\1/p')"
    v1_port="$(done_field "$LAB/install-v1.log" direct_port)"
    step "3. Миграция 1.0.57 → v2 (установщик из ветки)"
    install_v2 migrate
    [ "$(done_field "$LAB/migrate.log" instance)" = "$v1_instance" ] && [ -n "$v1_instance" ] \
      && pass "migrate: тот же экземпляр ${v1_instance}" || fail "migrate: экземпляр $(done_field "$LAB/migrate.log" instance) ≠ ${v1_instance:-?}"
    [ "$(done_field "$LAB/migrate.log" direct_port)" = "$v1_port" ] \
      && pass "migrate: тот же порт Direct ${v1_port}" || fail "migrate: порт $(done_field "$LAB/migrate.log" direct_port) ≠ ${v1_port:-?}"
    grep -q '|admin_token=' "$LAB/migrate.log" && fail "migrate: токен администратора перевыпущен" || pass "migrate: токены сохранены"
    for leftover in releases current staging incoming cache instance.json .env; do
      [ -e "$INSTALL_DIR/$leftover" ] && fail "migrate: остался ${leftover} от 1.0.x"
    done
    snapshot "$LAB/2-after-migrate.txt"
    compare migrate "$LAB/1-before.txt" "$LAB/2-after-migrate.txt"
    neighbors_alive migrate
    net_before="$(ardtt_subnet)"
    step "4. Повторное развёртывание v2 (обновление)"
    install_v2 update
    grep -q 'уже загружен\|Загрузка образа в Docker' "$LAB/update.log" && pass "update: образ из файла" || true
    snapshot "$LAB/3-after-update.txt"
    compare update "$LAB/1-before.txt" "$LAB/3-after-update.txt"
    neighbors_alive update
    net_after="$(ardtt_subnet)"
    if [ -n "$net_before" ] && [ "$net_before" = "$net_after" ]; then
      pass "update: подсеть ARDTT сохранена (${net_after})"
    else
      fail "update: подсеть ARDTT сменилась (${net_before:-?} → ${net_after:-?})"
    fi
    step "5. Удаление"
    uninstall
    snapshot "$LAB/4-after-uninstall.txt"
    compare uninstall "$LAB/1-before.txt" "$LAB/4-after-uninstall.txt"
    neighbors_alive uninstall
    step "6. Чистая установка v2 и удаление"
    install_v2 fresh
    grep -q '|admin_token=' "$LAB/fresh.log" && pass "fresh: новые токены выданы" || fail "fresh: нет admin_token в DONE"
    neighbors_alive fresh
    uninstall
    snapshot "$LAB/5-end.txt"
    compare end "$LAB/1-before.txt" "$LAB/5-end.txt"
    ;;
  *) echo "unknown scenario ${scenario}" >&2; exit 2 ;;
esac

echo
echo "LAB SUMMARY|failures=${FAILS}"
[ "$FAILS" -eq 0 ]
