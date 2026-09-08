#!/usr/bin/env bash
# Installer must not touch foreign Docker, host dataplane, or shared caches.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

INSTALLER="$ROOT/server/install.sh"
UNINSTALL_KT="$ROOT/android/app/src/main/java/com/ardtt/app/deploy/ServerUninstall.kt"
ENGINE="$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployEngine.kt"

forbidden=(
  'get.docker.com'
  'reset_docker_buildkit'
  'cleanup_host_dataplane'
  'wipe_dir_best_effort /var/lib/docker/buildkit'
  'systemctl stop docker'
  'killall'
  'pkill dockerd'
  'docker builder prune'
  'docker image prune'
  'docker system prune'
  'journalctl --vacuum'
  '/proc/sys/vm/drop_caches'
  'swapoff /swapfile'
  'fallocate'
  'ufw allow'
  'firewall-cmd --add-port'
  'iptables -F'
  'ip link del docker0'
  'rm -rf /var/lib/docker'
)

for pat in "${forbidden[@]}"; do
  if grep -F "$pat" "$INSTALLER" "$ROOT/server/install-lib/"*.sh >/dev/null 2>&1; then
    err "forbidden pattern still present: $pat"
  else
    ok "absent: $pat"
  fi
done

if grep -q 'apt-get purge' "$UNINSTALL_KT"; then
  err "ServerUninstall.kt still purges Docker"
fi
if grep -q 'rm -rf /var/lib/docker' "$UNINSTALL_KT"; then
  err "ServerUninstall.kt still deletes Docker root"
fi
if grep -q 'ip link del docker0' "$UNINSTALL_KT"; then
  err "ServerUninstall.kt still deletes docker0"
fi
if grep -q 'docker builder prune -af' "$ENGINE"; then
  err "DeployEngine still prunes BuildKit"
fi
if grep -q 'COMPOSE_PROFILES=hostnet' "$INSTALLER"; then
  err "installer still switches to hostnet"
fi
if grep -q 'network_mode: host' "$ROOT/server/docker-compose.yml"; then
  err "production compose still has hostnet"
fi

grep -q 'com.ardtt.owner' "$ROOT/server/install-lib/ownership.sh" || err "ownership labels"
grep -q 'pick_bridge_subnet' "$ROOT/server/install-lib/network.sh" || err "bridge subnet picker"
grep -q 'docker_published_port' "$ROOT/server/install-lib/ports.sh" || err "Docker PortBindings probe"

if [ "$fail" -ne 0 ]; then
  echo "install isolation tests failed" >&2
  exit 1
fi
ok "install isolation contract"
