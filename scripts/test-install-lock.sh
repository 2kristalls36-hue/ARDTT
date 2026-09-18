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
grep -q 'export -f flock' "$ROOT/server/fetch-and-install.sh" || err "fetch-and-install must no-op nested flock -n for 1.0.53 install.sh"
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

# Phone bootstrap (1.0.54 fetch-and-install) holds install.lock, then runs the
# published package's install.sh. 1.0.53 flocks the same inode and has no
# ARDTT_MUTATION_LOCK_HELD skip → nested flock -n dies «уже выполняется».
python3 - "$ROOT/server/fetch-and-install.sh" "$TMP/run_install.sh" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding="utf-8")
start = text.index("run_install() {")
i = text.index("{", start) + 1
depth = 1
while i < len(text) and depth:
    if text[i] == "{":
        depth += 1
    elif text[i] == "}":
        depth -= 1
    i += 1
Path(sys.argv[2]).write_text(text[start:i] + "\n", encoding="utf-8")
PY
STAGE="$TMP/stage"
OPT="$TMP/opt"
mkdir -p "$STAGE" "$OPT"
cat > "$STAGE/install.sh" <<'EOF'
#!/bin/bash
# Published 1.0.53: flocks install.lock, no parent-lock skip.
set -euo pipefail
INSTALL_DIR="${ARDTT_INSTALL_DIR:-/opt/ardtt}"
exec 9>"$INSTALL_DIR/install.lock"
if ! flock -n 9; then
  echo "ARDTT_ERROR|Другая установка этого экземпляра уже выполняется (${INSTALL_DIR}/install.lock)" >&2
  exit 1
fi
echo "ARDTT_DONE|legacy_ok=1"
EOF
chmod 755 "$STAGE/install.sh"
: >>"$OPT/install.lock"
set +e
NEST_OUT="$TMP/nested.out"
(
  set -euo pipefail
  prog() { :; }
  warn() { printf 'ARDTT_WARN|%s\n' "$*"; }
  LAYER_CACHE=0
  PKG_VER=1.0.53
  INSTALL_DIR="$OPT"
  STAGING="$STAGE"
  # shellcheck disable=SC1090
  . "$TMP/run_install.sh"
  exec 8>"$INSTALL_DIR/install.lock"
  flock -n 8
  export ARDTT_MUTATION_LOCK_HELD=1
  run_install
) >"$NEST_OUT" 2>&1
nested_rc=$?
set -e
[ "$nested_rc" = 0 ] || err "nested 1.0.53 install under fetch lock rc=$nested_rc: $(cat "$NEST_OUT")"
grep -q 'ARDTT_DONE|legacy_ok=1' "$NEST_OUT" || err "nested 1.0.53 install missing DONE: $(cat "$NEST_OUT")"
if grep -q 'уже выполняется' "$NEST_OUT"; then
  err "nested 1.0.53 install hit same-inode BUSY: $(cat "$NEST_OUT")"
elif [ "$nested_rc" = 0 ] && grep -q 'ARDTT_DONE|legacy_ok=1' "$NEST_OUT"; then
  ok "nested 1.0.53 install.sh under fetch lock"
else
  err "nested 1.0.53 install unexpected: rc=$nested_rc $(cat "$NEST_OUT")"
fi

if [ "$fail" -ne 0 ]; then
  echo "lock tests failed" >&2
  exit 1
fi
echo "OK mutation lock"
exit 0
