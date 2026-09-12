#!/usr/bin/env bash
# compose_up_cmd must interpolate from the release .env only: the caller's
# exported ARDTT_* (ports requested by the phone) must not override the
# auto-resolved values written to .env.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }

# Fake compose binary: dump the environment it received and the args.
mkdir -p "$TMP/bin" "$TMP/current"
# env -i strips the caller's variables (that is the point), so the output path is baked in.
cat > "$TMP/bin/docker-compose" <<EOF
#!/bin/sh
env | sort > "$TMP/env.txt"
printf '%s\n' "\$@" >> "$TMP/env.txt.args"
EOF
chmod +x "$TMP/bin/docker-compose"
printf 'ARDTT_BYPASS_PORT=56004\nARDTT_DIRECT_PORT=51821\n' > "$TMP/current/.env"
touch "$TMP/current/docker-compose.yml"

# Source only what compose_up_cmd needs.
# shellcheck disable=SC1091
source "$ROOT/server/install-lib/common.sh"
die() { echo "die: $*" >&2; exit 1; }
eval "$(sed -n '/^compose_up_cmd() {/,/^}/p' "$ROOT/server/install.sh")"
ROLE=entry
COMPOSE_PROJECT=ardtttest
ARDTT_COMPOSE_BIN="$TMP/bin/docker-compose"
INSTALL_DIR="$TMP"

FAKE_OUT="$TMP/env.txt"
# The phone exports the *requested* ports; .env has the resolved ones.
export ARDTT_BYPASS_PORT=56003 ARDTT_DIRECT_PORT=51820 ARDTT_CPUS=2.0 ARDTT_PUBLIC_HOST=203.0.113.9
( cd "$TMP/current" && compose_up_cmd up -d --no-build --pull never )

grep -q '^ARDTT_BYPASS_PORT=' "$FAKE_OUT" && err "requested ARDTT_BYPASS_PORT leaked into compose env (would override .env 56004)"
grep -q '^ARDTT_DIRECT_PORT=' "$FAKE_OUT" && err "requested ARDTT_DIRECT_PORT leaked into compose env"
grep -q '^ARDTT_CPUS=' "$FAKE_OUT" && err "ARDTT_CPUS leaked into compose env"
grep -q '^ARDTT_PUBLIC_HOST=' "$FAKE_OUT" && err "ARDTT_PUBLIC_HOST leaked into compose env"
grep -q '^COMPOSE_PROJECT_NAME=ardtttest$' "$FAKE_OUT" || err "COMPOSE_PROJECT_NAME must be passed"
grep -q '^ARDTT_NETWORK_MODE=isolated$' "$FAKE_OUT" || err "ARDTT_NETWORK_MODE=isolated must be passed"
grep -q '^PATH=' "$FAKE_OUT" || err "PATH must be passed"
grep -q -- '--env-file' "$FAKE_OUT.args" || err "compose must read the release .env"
grep -q '^up$' "$FAKE_OUT.args" || err "compose args lost"

# DOCKER_* connection settings do pass through.
export DOCKER_HOST=unix:///run/other.sock
( cd "$TMP/current" && compose_up_cmd down )
grep -q '^DOCKER_HOST=unix:///run/other.sock$' "$FAKE_OUT" || err "DOCKER_HOST must pass through to compose"

# First-install failure path must clean its own container/network, not just try a restore.
grep -q 'remove_owned_networks' "$ROOT/server/install.sh" || err "compose-up failure must remove the orphan network on a first install"
grep -q 'remove_owned_networks()' "$ROOT/server/install-lib/ownership.sh" || err "ownership.sh must define remove_owned_networks"

[ "$fail" -eq 0 ] || { echo "compose env isolation failed" >&2; exit 1; }
echo "OK compose interpolation reads .env only (requested ports do not override auto-ports)"
