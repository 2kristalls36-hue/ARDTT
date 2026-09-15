#!/usr/bin/env bash
# Critical state writes must not be swallowed. Corrupt/unknown schema is recovery.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

# Best-effort helper must not be what install.sh uses for mutating phases.
if grep -n 'ardtt_state_set ' "$ROOT/server/install.sh" | grep -v 'ardtt_state_set_required' | grep -v 'ardtt_state_set_best_effort'; then
  err "install.sh still calls best-effort ardtt_state_set on the install path"
else
  ok "install.sh uses required state writes"
fi

grep -q 'STATE_WRITE_FAILED' "$ROOT/server/install-lib/protocol.sh" \
  || err "protocol must emit STATE_WRITE_FAILED"
ok "STATE_WRITE_FAILED code exists"

# protocol.sh: required write with a failing binary must not succeed.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
INSTALL_DIR="$TMP/opt"
mkdir -p "$INSTALL_DIR" "$TMP/bin"
cat > "$TMP/bin/ardttctl" <<'EOF'
#!/bin/sh
exit 42
EOF
chmod +x "$TMP/bin/ardttctl"
SCRIPT_DIR="$TMP/bin"
PKG_DIR=""
SELF_DIR=""
set +e
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/protocol.sh"
out="$(ardtt_state_set_required --phase stage --desired 1.0.54 2>&1)"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "ardtt_state_set_required swallowed exit 42"
echo "$out" | grep -q 'STATE_WRITE_FAILED' || err "required write must mention STATE_WRITE_FAILED (got $out)"
ok "required state write fails closed"

# --force must not idle out of recovery without a verified pointer set.
cd "$ROOT/server/ardttctl"
bin="$(mktemp)"
GOOS="$(go env GOOS)" GOARCH="$(go env GOARCH)" CGO_ENABLED=0 go build -o "$bin" .
mkdir -p "$INSTALL_DIR/state"
cat > "$INSTALL_DIR/state/deploy.json" <<'EOF'
{
  "schemaVersion": 1,
  "phase": "recovery_required",
  "controlProtocolVersion": 2
}
EOF
if ARDTT_INSTALL_DIR="$INSTALL_DIR" "$bin" state set --phase idle --force >/dev/null 2>"$TMP/force.err"; then
  err "state set --force idle over recovery must fail without verified pointers"
else
  ok "state set --force idle over recovery refused"
fi

# unknown schemaVersion is recovery, not silently current
mkdir -p "$TMP/schema/state"
cat > "$TMP/schema/state/deploy.json" <<'EOF'
{"schemaVersion": 99, "phase": "idle", "controlProtocolVersion": 2}
EOF
got="$(ARDTT_INSTALL_DIR="$TMP/schema" "$bin" state get)"
echo "$got" | grep -q 'recovery_required' || err "unknown schemaVersion must be recovery_required (got $got)"
ok "unknown schemaVersion is recovery_required"

if [ "$fail" -ne 0 ]; then
  echo "state-write tests failed" >&2
  exit 1
fi
echo "OK state write / recovery"
exit 0
