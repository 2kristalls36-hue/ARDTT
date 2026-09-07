#!/usr/bin/env bash
# summarize_build_failure must surface ENOSPC, not the apt package list.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALLER="$ROOT/server/install.sh"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

eval "$(sed -n '/^summarize_build_failure()/,/^}/p' "$INSTALLER")"
type summarize_build_failure >/dev/null 2>&1 || {
  echo "summarize_build_failure not extracted from $INSTALLER" >&2
  exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat >"$TMP/enospc.log" <<'EOF'
#12 27.10   libsasl2-modules-db libsqlite3-0 libssh2-1
#12 27.55 Get:1 http://deb.debian.org/debian bookworm/main amd64 netbase all 6.4
30.32 E: Write error - ~LZMAFILE (28: No space left on device)
30.32 E: Write error - write (28: No space left on device)
failed to solve: process "/bin/sh -c apt-get update" did not complete successfully: exit code: 100
EOF

got="$(summarize_build_failure "$TMP/enospc.log" "ardtt")"
echo "$got" | grep -q 'не осталось места' || err "ENOSPC must be the headline, got: $got"
echo "$got" | grep -q 'No space left on device' || err "ENOSPC english missing, got: $got"
echo "$got" | grep -q 'libsasl2' && err "must not dump apt package list: $got"
ok "ENOSPC headline"

cat >"$TMP/other.log" <<'EOF'
 => ERROR: failed to solve: snapshot abc does not exist: not found
EOF
got="$(summarize_build_failure "$TMP/other.log" "ardtt")"
echo "$got" | grep -q 'failed to solve' || err "other errors keep failed to solve: $got"
ok "non-ENOSPC keeps solver line"

grep -q 'MIN_DISK_UPDATE_MB:-900' "$INSTALLER" || err "update floor must be 900"
grep -q 'повтор без кэша пропущен — на диске нет места' "$INSTALLER" \
  || err "must skip no-cache on ENOSPC"
grep -q 'не перезапускаем dockerd' "$INSTALLER" \
  || err "foreign hosts must still prune cache"
if grep -q 'пропускаем builder prune -af и restart dockerd' "$INSTALLER"; then
  err "must not skip builder prune just because another container exists"
fi
grep -q 'truncate_docker_json_logs' "$INSTALLER" || err "must truncate huge docker json logs"
grep -q 'reclaim_obsolete_split_images' "$INSTALLER" || err "must drop leftover split images"
grep -q 'reserve_mb="${MIN_DISK_MB:-1100}"' "$INSTALLER" || err "swap must reserve MIN_DISK_MB"
grep -q 'build --no-cache' "$INSTALLER" || err "keep no-cache retry for non-disk failures"
if awk '
  $0 ~ /^restore_live_stack_if_needed\(\)/ { in_fn=1; next }
  in_fn && $0 ~ /^}/ { in_fn=0 }
  in_fn && $0 ~ /\. \.\/\.env/ { found=1 }
  END { exit found ? 0 : 1 }
' "$INSTALLER"; then
  :
else
  err "restore_live_stack_if_needed must reload stack .env so staging version does not leak"
fi
grep -q 'Host marker matches the running stack' "$INSTALLER" \
  || err "host DEPLOY_VERSION must be stamped only after compose up"

if [ "$fail" -ne 0 ]; then
  echo "install disk guard tests failed" >&2
  exit 1
fi
ok "disk / ENOSPC deploy guards"
