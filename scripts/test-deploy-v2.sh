#!/usr/bin/env bash
# Deploy v2 (server/deploy/ardtt-deploy.sh) against a real Docker daemon with a
# tiny fake ARDTT image: fresh install, update, rollback on a broken version,
# busy ports, migration of a 1.0.x layout, two instances side by side,
# uninstall — and neighbours stay untouched. Needs root and Docker; pure
# helper checks run without Docker.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/server/deploy/ardtt-deploy.sh"
FAILS=0
ok() { echo "OK $*"; }
err() { echo "FAIL: $*" >&2; FAILS=$((FAILS + 1)); }

# ------------------------------------------------------------- helpers ----
(
  # shellcheck disable=SC1090
  ARDTT_DEPLOY_LIB=1 . "$DEPLOY"
  set +e
  [ "$(cidr_range 172.28.20.7/24)" = "$(cidr_range 172.28.20.0/24)" ] || { echo "cidr_range masks host bits" >&2; exit 1; }
  overlaps 172.28.20.0/24 172.16.0.0/12 || { echo "overlap /24 in /12" >&2; exit 1; }
  overlaps 172.28.20.0/24 172.28.21.0/24 && { echo "adjacent /24 must not overlap" >&2; exit 1; }
  T="$(mktemp -d)"
  printf '{\n  "instanceId": "abc123",\n  "bridgeSubnet": "172.30.4.0/24",\n  "containerName": "ardtt"\n}\n' >"$T/i.json"
  [ "$(json_get "$T/i.json" instanceId)" = abc123 ] || { echo "json_get instanceId" >&2; exit 1; }
  [ "$(json_get "$T/i.json" bridgeSubnet)" = 172.30.4.0/24 ] || { echo "json_get bridgeSubnet" >&2; exit 1; }
  printf 'ARDTT_DIRECT_PORT=51821\nARDTT_CASCADE_DNS="10.10.0.2"\n' >"$T/.env"
  [ "$(kv_get "$T/.env" ARDTT_DIRECT_PORT)" = 51821 ] || { echo "kv_get" >&2; exit 1; }
  [ "$(kv_get "$T/.env" ARDTT_CASCADE_DNS)" = 10.10.0.2 ] || { echo "kv_get strips quotes" >&2; exit 1; }
  rm -rf "$T"
) && ok "helpers: cidr, json_get, kv_get" || err "helpers"

bash -n "$DEPLOY" || err "syntax"
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -S warning "$DEPLOY" && ok "shellcheck" || err "shellcheck"
fi

# The Docker part removes every ARDTT object on the host: CI or a throwaway VM only.
if [ "${ARDTT_TEST_DOCKER:-0}" != 1 ] || [ "$(id -u)" -ne 0 ] || ! docker info >/dev/null 2>&1; then
  echo "SKIP docker scenarios (set ARDTT_TEST_DOCKER=1; need root and a disposable Docker host)"
  [ "$FAILS" -eq 0 ] || exit 1
  exit 0
fi

# ------------------------------------------------------------ fixtures ----
W="$(mktemp -d)"
DIR="$W/opt-ardtt"
cleanup() {
  docker rm -f ardtt ardtt-prev >/dev/null 2>&1 || true
  docker ps -aq --filter label=com.ardtt.test=1 | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker ps -aq --filter label=com.ardtt.owner=ardtt | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network ls -q --filter label=com.ardtt.owner=ardtt | xargs -r docker network rm >/dev/null 2>&1 || true
  docker images --filter label=com.ardtt.owner=ardtt -q | sort -u | xargs -r docker rmi -f >/dev/null 2>&1 || true
  rm -rf "$W"
}
trap cleanup EXIT

docker image inspect busybox:1.36 >/dev/null 2>&1 || docker pull -q busybox:1.36 >/dev/null || { err "pull busybox"; exit 1; }
make_image() { # version ready(ok|bad)
  local v="$1" ready="$2" d="$W/img-$1"
  mkdir -p "$d"
  if [ "$ready" = ok ]; then
    printf '#!/bin/sh\n[ -f /tmp/started ] && echo ready || { echo "not ready: starting" >&2; exit 1; }\n' >"$d/ready.sh"
  else
    printf '#!/bin/sh\necho "not ready: broken on purpose" >&2\nexit 1\n' >"$d/ready.sh"
  fi
  printf '#!/bin/sh\ncase "$1" in genkey) echo PRIVKEYTEST=;; pubkey) cat >/dev/null; echo PUBKEYTEST=;; esac\n' >"$d/awg"
  printf '#!/bin/sh\necho "{\\"ok\\":true,\\"provisionCertFp\\":\\"fp-%s\\"}"\n' "$v" >"$d/curl"
  printf '#!/bin/sh\ntouch /tmp/started\nexec sleep 1000000\n' >"$d/entrypoint.sh"
  # busybox has no bash applet; ready.sh is run as «bash /opt/ardtt/ready.sh».
  printf '#!/bin/sh\nexec /bin/sh "$@"\n' >"$d/bash"
  cat >"$d/Dockerfile" <<EOF
FROM busybox:1.36
COPY --chmod=755 ready.sh /opt/ardtt/ready.sh
COPY --chmod=755 awg /usr/local/bin/awg
COPY --chmod=755 curl /usr/local/bin/curl
COPY --chmod=755 entrypoint.sh /entrypoint.sh
COPY --chmod=755 bash /bin/bash
LABEL com.ardtt.owner=ardtt com.ardtt.deploy-version=$v
ENTRYPOINT ["/entrypoint.sh"]
EOF
  docker build -q -t "ardtt/server:$v" "$d" >/dev/null || return 1
  docker save "ardtt/server:$v" | gzip -1 >"$W/ardtt-server-$v-linux-$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/').image.tar.gz"
  docker rmi "ardtt/server:$v" >/dev/null
}
make_image 9.0.1 ok && make_image 9.0.2 ok && make_image 9.0.3 bad || { err "build fake images"; exit 1; }
img() { echo "$W/ardtt-server-$1-linux-$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/').image.tar.gz"; }

# Neighbours: a web service, one holding UDP 51820, a foreign compose-labelled project.
docker run -d --name lab-v2-web --label com.ardtt.test=1 -p 18080:80 busybox:1.36 sh -c 'echo ok > /i; httpd -f -p 80 -h /' >/dev/null
docker run -d --name lab-v2-udp --label com.ardtt.test=1 -p 51820:51820/udp busybox:1.36 sleep 1000000 >/dev/null
docker run -d --name lab-v2-stack --label com.ardtt.test=1 --label com.docker.compose.project=stack busybox:1.36 sleep 1000000 >/dev/null
neighbours_alive() {
  local n
  for n in lab-v2-web lab-v2-udp lab-v2-stack; do
    [ "$(docker inspect -f '{{.State.Running}}' "$n" 2>/dev/null)" = true ] || { err "$1: neighbour $n stopped"; return; }
  done
  ok "$1: neighbours alive"
}

run() { # env… → output (stdout+stderr) in $out, exit code in $RC
  RC=0
  out="$(env ARDTT_INSTALL_DIR="$DIR" ARDTT_PUBLIC_HOST=203.0.113.5 ARDTT_ROLE=entry "$@" bash "$DEPLOY" 2>&1)" || RC=$?
}
field() { sed -n "s/^ARDTT_DONE|.*|$1=\([^|]*\).*/\1/p" | head -n 1; }

# ------------------------------------------------------------ scenarios ----
run ARDTT_DEPLOY_VERSION=9.0.1 ARDTT_IMAGE_FILE="$(img 9.0.1)" ARDTT_AUTO_PORTS=1
[ "$RC" -eq 0 ] && grep -q '^ARDTT_DONE|' <<<"$out" && ok "fresh install" || { err "fresh install"; echo "$out"; }
[ "$(field direct_port <<<"$out")" = 51821 ] && ok "busy 51820 → 51821" || err "auto port: $(field direct_port <<<"$out")"
grep -q '|admin_token=' <<<"$out" && ok "new admin token reported" || err "admin token missing on fresh install"
[ "$(field provision_cert_fp <<<"$out")" = fp-9.0.1 ] && ok "provision fp from /health" || err "fp"
SUBNET1="$(sed -n 's/^SUBNET=//p' "$DIR/instance.env")"
neighbours_alive install

run ARDTT_DEPLOY_VERSION=9.0.2 ARDTT_IMAGE_FILE="$(img 9.0.2)" ARDTT_AUTO_PORTS=1
[ "$RC" -eq 0 ] && [ "$(field deploy_version <<<"$out")" = 9.0.2 ] && ok "update 9.0.1 → 9.0.2" || { err "update"; echo "$out"; }
grep -q '|admin_token=' <<<"$out" && err "update must not re-issue tokens" || ok "update keeps tokens"
[ "$(sed -n 's/^SUBNET=//p' "$DIR/instance.env")" = "$SUBNET1" ] && ok "update keeps subnet" || err "subnet changed"
[ "$(field direct_port <<<"$out")" = 51821 ] && ok "update keeps own port" || err "update port $(field direct_port <<<"$out")"
docker container inspect ardtt-prev >/dev/null 2>&1 && err "ardtt-prev left behind" || ok "no ardtt-prev after success"
neighbours_alive update

run ARDTT_DEPLOY_VERSION=9.0.3 ARDTT_IMAGE_FILE="$(img 9.0.3)" ARDTT_AUTO_PORTS=1
[ "$RC" -ne 0 ] && grep -q 'code=READINESS_FAILED' <<<"$out" && ok "broken version refused" || { err "broken version"; echo "$out"; }
[ "$(docker inspect -f '{{index .Config.Labels "com.ardtt.deploy-version"}} {{.State.Running}}' ardtt 2>/dev/null)" = "9.0.2 true" ] \
  && ok "rollback to 9.0.2 running" || err "rollback: $(docker inspect -f '{{.Config.Image}} {{.State.Status}}' ardtt 2>&1)"
grep -q '^VERSION=9.0.2$' "$DIR/instance.env" && ok "state still 9.0.2" || err "state after rollback"
[ "$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' ardtt)" = unless-stopped ] && ok "restored restart policy" || err "restart policy"

docker run -d --name lab-v2-tcp --label com.ardtt.test=1 -p 127.0.0.1:19300:80 busybox:1.36 sleep 1000000 >/dev/null
run ARDTT_DEPLOY_VERSION=9.0.2 ARDTT_IMAGE_FILE="$(img 9.0.2)" ARDTT_TELEMETRY_PORT=19300 ARDTT_AUTO_PORTS=0
[ "$RC" -ne 0 ] && grep -q 'code=PORT_BUSY' <<<"$out" && ok "busy port without auto → PORT_BUSY" || { err "PORT_BUSY"; echo "$out"; }
[ "$(docker inspect -f '{{.State.Running}}' ardtt)" = true ] && ok "PORT_BUSY leaves running version" || err "PORT_BUSY stopped ardtt"

run ARDTT_ACTION=status
grep -q '^ARDTT_STATUS|installed=1|.*|version=9.0.2|.*|state=running' <<<"$out" && ok "status" || { err "status"; echo "$out"; }

# Second instance in another directory: other name, ports and subnet.
out="$(env ARDTT_INSTALL_DIR="$W/opt-exit" ARDTT_PUBLIC_HOST=203.0.113.6 ARDTT_ROLE=exit ARDTT_DEPLOY_VERSION=9.0.2 \
  ARDTT_IMAGE_FILE="$(img 9.0.2)" ARDTT_AUTO_PORTS=1 bash "$DEPLOY" 2>&1)"
EXIT_C="$(field container <<<"$out")"
[ -n "$EXIT_C" ] && [ "$EXIT_C" != ardtt ] && grep -q '^ARDTT_CASCADE_PUBLIC_KEY|PUBKEYTEST=' <<<"$out" \
  && ok "exit instance beside entry ($EXIT_C)" || { err "exit instance"; echo "$out"; }
env ARDTT_INSTALL_DIR="$W/opt-exit" ARDTT_ACTION=uninstall ARDTT_PURGE_DATA=1 bash "$DEPLOY" >/dev/null 2>&1
[ "$(docker inspect -f '{{.State.Running}}' ardtt)" = true ] && ok "exit uninstall leaves entry" || err "exit uninstall touched entry"

run ARDTT_ACTION=uninstall
grep -q '^ARDTT_UNINSTALLED$' <<<"$out" && ok "uninstall" || err "uninstall"
[ -z "$(docker ps -aq --filter label=com.ardtt.owner=ardtt)" ] && ok "no ARDTT containers" || err "containers left"
[ -d "$DIR/data" ] && [ -s "$DIR/data/admin.token" ] && ok "data kept without purge" || err "data removed"
neighbours_alive uninstall

# Migration: a 1.0.x layout with a compose-labelled container and committed metadata.
rm -rf "$DIR"
mkdir -p "$DIR/releases/r1" "$DIR/data" "$DIR/logs" "$DIR/cache/layers" "$DIR/state"
ln -s releases/r1 "$DIR/current"
echo v1-admin >"$DIR/data/admin.token"
printf '{"instanceId": "v1inst0000000001", "containerName": "ardtt", "networkName": "ardtt-v1inst0000000001", "bridgeSubnet": "172.30.9.0/24"}\n' >"$DIR/instance.json"
printf 'ARDTT_PUBLIC_HOST=203.0.113.5\nARDTT_DEPLOY_VERSION=1.0.57\nARDTT_DIRECT_PORT=51833\nARDTT_BYPASS_PORT=56033\nARDTT_PROVISION_PORT=9133\nARDTT_TELEMETRY_PORT=9233\nARDTT_ROLE=entry\nARDTT_PROVISION_BIND=127.0.0.1\n' >"$DIR/.env"
docker load -i "$(img 9.0.1)" >/dev/null && docker tag ardtt/server:9.0.1 ardtt/server:1.0.57
docker network create --subnet 172.30.9.0/24 --label com.ardtt.owner=ardtt --label com.ardtt.instance=v1inst0000000001 \
  --label com.docker.compose.network=ardtt ardtt-v1inst0000000001 >/dev/null
docker run -d --name ardtt --network ardtt-v1inst0000000001 --label com.ardtt.owner=ardtt \
  --label com.ardtt.instance=v1inst0000000001 --label com.docker.compose.project=ardttv1inst0000000001 \
  --restart unless-stopped -p 51833:51820/udp -p 56033:56003/udp -p 127.0.0.1:9133:9100 -p 127.0.0.1:9233:9200 \
  ardtt/server:1.0.57 >/dev/null
run ARDTT_DEPLOY_VERSION=9.0.2 ARDTT_IMAGE_FILE="$(img 9.0.2)" ARDTT_AUTO_PORTS=0
if [ "$RC" -eq 0 ] && [ "$(field instance <<<"$out")" = v1inst0000000001 ] && [ "$(field direct_port <<<"$out")" = 51833 ] \
   && [ "$(field provision_port <<<"$out")" = 9133 ]; then
  ok "1.0.x migration keeps instance and ports"
else
  err "1.0.x migration"; echo "$out"
fi
grep -q '|admin_token=' <<<"$out" && err "migration re-issued admin token" || ok "migration keeps admin token"
[ "$(cat "$DIR/data/admin.token")" = v1-admin ] && ok "admin.token untouched" || err "admin.token changed"
[ "$(sed -n 's/^SUBNET=//p' "$DIR/instance.env")" = 172.30.9.0/24 ] && ok "migration keeps subnet" || err "migration subnet"
for p in releases current cache state instance.json .env; do
  [ -e "$DIR/$p" ] || [ -L "$DIR/$p" ] && err "1.0.x leftover $p"
done
ok "1.0.x leftovers removed"
neighbours_alive migration

if [ "$FAILS" -ne 0 ]; then
  echo "deploy v2: $FAILS failure(s)" >&2
  exit 1
fi
ok "deploy v2"
