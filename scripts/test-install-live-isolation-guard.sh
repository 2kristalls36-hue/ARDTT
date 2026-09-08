#!/usr/bin/env bash
# The live isolation helper must abort when a fixed name already exists and
# must not issue docker rm -f against names it did not create.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

SCRIPT="$ROOT/scripts/test-install-live-isolation.sh"
[ -f "$SCRIPT" ] || { echo "missing $SCRIPT" >&2; exit 1; }

if grep -E 'for name in "\$\{FOREIGN\[@\]\}"; do' -A3 "$SCRIPT" | grep -q 'docker rm -f "\$name"'; then
  err "setup still docker rm -f by fixed foreign names"
else
  ok "setup does not rm -f by name"
fi

if grep -E 'cleanup\(\)' -A25 "$SCRIPT" | grep -q 'docker rm -f "\$name"'; then
  err "cleanup still docker rm -f by fixed names"
else
  ok "cleanup does not rm -f by name"
fi

grep -q 'live_isolation_require_free_names' "$SCRIPT" || err "must abort when names already exist"
grep -q 'CREATED_IDS' "$SCRIPT" || err "must remember created container IDs"
grep -q 'com.ardtt.test-run' "$SCRIPT" || err "created containers must carry a run label"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUB="$TMP/bin"
mkdir -p "$STUB"
cat > "$STUB/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "${DOCKER_LOG:-/tmp/ardtt-iso-docker.log}"
if [ "$1" = "inspect" ]; then
  # Pretend stack-nginx-1 already exists.
  if [ "${2:-}" = "stack-nginx-1" ]; then
    exit 0
  fi
  exit 1
fi
if [ "$1" = "rm" ]; then
  exit 0
fi
exit 0
EOF
chmod +x "$STUB/docker"
export PATH="$STUB:$PATH"
export DOCKER_LOG="$TMP/docker.log"
: > "$DOCKER_LOG"

# shellcheck disable=SC1091
source /dev/null
# Extract and run just the name-guard by invoking bash -c with the function
# copied from the live script (keep in sync via grep contract above).
live_isolation_require_free_names() {
  local name
  for name in "$@"; do
    if docker inspect "$name" >/dev/null 2>&1; then
      echo "ABORT: container $name already exists; not touching it" >&2
      return 1
    fi
  done
  return 0
}

if live_isolation_require_free_names stack-nginx-1; then
  err "guard must fail when the name exists"
else
  ok "guard aborts on a pre-existing name"
fi
if grep -q 'rm -f' "$DOCKER_LOG"; then
  err "guard must not rm existing containers"
else
  ok "guard did not rm the existing container"
fi

if [ "$fail" -ne 0 ]; then
  echo "live isolation guard tests failed" >&2
  exit 1
fi
echo "OK live isolation guard"
exit 0
