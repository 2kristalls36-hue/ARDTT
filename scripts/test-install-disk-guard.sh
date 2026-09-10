#!/usr/bin/env bash
# Disk / RAM preflight must fail closed without pruning the host by default.
# Opt-in ARDTT_DISK_CLEANUP=1 may reclaim safe caches/logs only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

grep -q 'MIN_DISK_MB' "$ROOT/server/install.sh" || err "disk floor"
grep -q 'DISK_FULL' "$ROOT/server/install.sh" || err "must emit DISK_FULL code"
grep -q 'ARDTT_DISK_CLEANUP' "$ROOT/server/install.sh" || err "must offer ARDTT_DISK_CLEANUP"
grep -q 'ardtt_disk_cleanup' "$ROOT/server/install.sh" || err "must call opt-in cleanup"
grep -q 'Глобальная очистка сервера не выполняется' "$ROOT/server/install.sh" || err "must still say global wipe is not done"
grep -q 'Swap хоста не создаём' "$ROOT/server/install.sh" || err "must not create swap"
[ -f "$ROOT/server/install-lib/disk-cleanup.sh" ] || err "missing disk-cleanup.sh"
grep -q 'ardtt_truncate_docker_json_logs' "$ROOT/server/install-lib/disk-cleanup.sh" || err "cleanup truncates large docker logs"
grep -q 'docker system prune' "$ROOT/server/install-lib/disk-cleanup.sh" && err "must not docker system prune"
grep -q 'rm -rf /var/lib/docker' "$ROOT/server/install-lib/disk-cleanup.sh" && err "must not wipe docker root"
# Cleanup must only run when DISK_CLEANUP flag is set (guarded).
if ! grep -A2 'DISK_CLEANUP' "$ROOT/server/install.sh" | grep -q 'ardtt_disk_cleanup'; then
  err "cleanup must be gated on DISK_CLEANUP"
fi
if grep -q 'ardtt_disk_cleanup' "$ROOT/server/install.sh" && ! grep -B5 'ardtt_disk_cleanup' "$ROOT/server/install.sh" | grep -q 'DISK_CLEANUP'; then
  err "ardtt_disk_cleanup call must sit behind DISK_CLEANUP check"
fi
if grep -q 'summarize_build_failure' "$ROOT/server/install.sh"; then
  err "build-on-VPS failure helper must be gone"
fi
# Truncate only behind the opt-in helper, not scattered in install.sh.
if grep -q 'truncate_docker_json_logs\|:-json.log' "$ROOT/server/install.sh"; then
  err "json-log truncate must live in disk-cleanup.sh only"
fi
ok "disk / RAM preflight offers opt-in cleanup, no host wipe"
exit "$fail"
