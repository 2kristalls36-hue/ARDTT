#!/usr/bin/env bash
# Disk / RAM preflight must fail closed without pruning the host.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

grep -q 'MIN_DISK_MB' "$ROOT/server/install.sh" || err "disk floor"
grep -q 'Глобальная очистка сервера не выполняется' "$ROOT/server/install.sh" || err "must not tell the user to prune the host"
grep -q 'Swap хоста не создаём' "$ROOT/server/install.sh" || err "must not create swap"
if grep -q 'summarize_build_failure' "$ROOT/server/install.sh"; then
  err "build-on-VPS failure helper must be gone"
fi
if grep -q 'truncate_docker_json_logs' "$ROOT/server/install.sh"; then
  err "must not truncate foreign json logs"
fi
ok "disk / RAM preflight does not reclaim the host"
exit 0
