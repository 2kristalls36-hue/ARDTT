#!/usr/bin/env bash
# Disk / RAM preflight must fail closed without pruning the host by default.
# Opt-in ARDTT_DISK_CLEANUP=1 may reclaim safe caches/logs only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

grep -q 'MIN_DISK_MB' "$ROOT/server/install.sh" || err "disk floor"
grep -q 'INSUFFICIENT_DISK' "$ROOT/server/install-lib/disk-budget.sh" || err "must emit INSUFFICIENT_DISK"
grep -q 'ARDTT_DISK_CLEANUP' "$ROOT/server/install.sh" || err "must offer ARDTT_DISK_CLEANUP"
grep -q 'ardtt_disk_cleanup' "$ROOT/server/install-lib/disk-budget.sh" || err "must call opt-in cleanup"
grep -q 'Глобальная очистка сервера не выполняется' "$ROOT/server/install.sh" || err "must still say global wipe is not done"
grep -q 'Swap хоста не создаём' "$ROOT/server/install.sh" || err "must not create swap"
[ -f "$ROOT/server/install-lib/disk-cleanup.sh" ] || err "missing disk-cleanup.sh"
grep -q 'ardtt_truncate_docker_json_logs' "$ROOT/server/install-lib/disk-cleanup.sh" && err "must not truncate all docker json logs"
grep -q 'docker system prune' "$ROOT/server/install-lib/disk-cleanup.sh" && err "must not docker system prune"
grep -q 'docker volume prune' "$ROOT/server/install-lib/disk-cleanup.sh" && err "must not docker volume prune"
grep -q 'docker image prune' "$ROOT/server/install-lib/disk-cleanup.sh" && err "must not docker image prune"
grep -q 'rm -rf /var/lib/docker' "$ROOT/server/install-lib/disk-cleanup.sh" && err "must not wipe docker root"
if grep -E 'rm[[:space:]].*install\.lock' "$ROOT/server/install-lib/disk-cleanup.sh"; then
  err "disk-cleanup must not unlink install.lock"
fi
# Cleanup must only run when DISK_CLEANUP flag is set (guarded).
if ! grep -B8 'ardtt_disk_cleanup' "$ROOT/server/install-lib/disk-budget.sh" | grep -q 'DISK_CLEANUP'; then
  err "cleanup must be gated on DISK_CLEANUP"
fi
if grep -q 'summarize_build_failure' "$ROOT/server/install.sh"; then
  err "build-on-VPS failure helper must be gone"
fi
# Truncate / prune must not live in install.sh.
if grep -q 'truncate_docker_json_logs\|:-json.log' "$ROOT/server/install.sh"; then
  err "json-log truncate must not appear in install.sh"
fi
ok "disk / RAM preflight offers opt-in cleanup, no host wipe"
exit "$fail"
