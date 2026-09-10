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
  'docker system prune'
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

# Opt-in disk reclaim may prune dangling images / vacuum journal — only in disk-cleanup.sh.
for pat in 'docker image prune' 'journalctl --vacuum'; do
  if grep -F "$pat" "$INSTALLER" >/dev/null 2>&1; then
    err "$pat must not appear in install.sh (only disk-cleanup.sh)"
  fi
  hits="$(grep -lF "$pat" "$ROOT/server/install-lib/"*.sh 2>/dev/null || true)"
  for f in $hits; do
    base="$(basename "$f")"
    if [ "$base" != "disk-cleanup.sh" ]; then
      err "$pat forbidden outside disk-cleanup.sh (found in $base)"
    fi
  done
done
grep -q 'docker image prune' "$ROOT/server/install-lib/disk-cleanup.sh" || err "disk-cleanup should prune dangling images"
grep -q 'ARDTT_DISK_CLEANUP' "$INSTALLER" || err "install.sh must gate cleanup on ARDTT_DISK_CLEANUP"

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
grep -q 'group=root' "$ROOT/server/dns/dnsmasq.conf.tmpl" || err "dnsmasq must not drop to group dip under cap_drop ALL"
grep -q 'SETGID' "$ROOT/server/docker-compose.yml" || err "compose must SETGID so dnsmasq can drop groups"
grep -q 'docker_published_port' "$ROOT/server/install-lib/ports.sh" || err "Docker PortBindings probe"
grep -q 'inspect_json_has_host_port' "$ROOT/server/install-lib/ports.sh" || err "HostPort inspect helper"

if [ "$fail" -ne 0 ]; then
  echo "install isolation tests failed" >&2
  exit 1
fi
ok "install isolation contract"
