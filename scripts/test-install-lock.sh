#!/usr/bin/env bash
# Concurrent deploy: only one owner of the mutation lock; second is DEPLOY_IN_PROGRESS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

grep -q 'DEPLOY_IN_PROGRESS' "$ROOT/server/install.sh" || err "install.sh must emit DEPLOY_IN_PROGRESS"
grep -q 'DEPLOY_IN_PROGRESS' "$ROOT/server/fetch-and-install.sh" || err "fetch-and-install must emit DEPLOY_IN_PROGRESS"
grep -q 'ARDTT_MUTATION_LOCK_HELD' "$ROOT/server/install.sh" || err "nested install must skip second flock"
if grep -q 'rm -f "${INSTALL_DIR}/install.lock"' "$ROOT/server/install-lib/uninstall.sh"; then
  err "uninstall must not unlink install.lock"
fi
ok "lock files are not unlinked on uninstall"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
LOCK="$TMP/install.lock"
: >>"$LOCK"
(
  exec 9>"$LOCK"
  flock -n 9 || exit 1
  echo owner > "$TMP/owner"
  sleep 2
) &
sleep 0.2
set +e
(
  exec 9>"$LOCK"
  flock -n 9 || exit 1
  echo second > "$TMP/second"
)
rc=$?
set -e
wait || true
[ "$rc" -ne 0 ] || err "second flock succeeded"
[ -f "$TMP/owner" ] || err "owner did not run"
[ ! -f "$TMP/second" ] || err "second owner wrote"
ok "concurrent flock: only one owner"

if [ "$fail" -ne 0 ]; then
  echo "lock tests failed" >&2
  exit 1
fi
echo "OK mutation lock"
exit 0
