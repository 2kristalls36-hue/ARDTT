#!/usr/bin/env bash
# restore_previous_release must copy previous/ (a compose FILE) back to current/
# and restore DEPLOY_VERSION. [ -d previous/docker-compose.yml ] is always false.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
INSTALL_DIR="$TMP/opt"
COMPOSE_LOG="$TMP/compose.log"
mkdir -p "$INSTALL_DIR/previous" "$INSTALL_DIR/current" "$INSTALL_DIR/data"

echo 'old-compose' > "$INSTALL_DIR/previous/docker-compose.yml"
echo 'old-exit' > "$INSTALL_DIR/previous/docker-compose.exit.yml"
cat > "$INSTALL_DIR/previous/.env" <<EOF
ARDTT_IMAGE=ardtt/server:1.0.44
ARDTT_DEPLOY_VERSION=1.0.44
COMPOSE_PROJECT_NAME=ardttabc
EOF
echo 'new-compose' > "$INSTALL_DIR/current/docker-compose.yml"
echo '1.0.45' > "$INSTALL_DIR/data/DEPLOY_VERSION"
echo '1.0.45' > "$INSTALL_DIR/DEPLOY_VERSION"

compose_up_cmd() {
  echo "$*" >> "$COMPOSE_LOG"
  return 0
}

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/uninstall.sh"

if [ -d "$INSTALL_DIR/previous/docker-compose.yml" ]; then
  err "sanity: docker-compose.yml is a file, [ -d ] must be false"
fi
[ -f "$INSTALL_DIR/previous/docker-compose.yml" ] || err "sanity: previous compose exists"

restore_previous_release || err "restore_previous_release failed"
grep -qx 'old-compose' "$INSTALL_DIR/current/docker-compose.yml" || err "current compose not restored from previous"
grep -qx 'old-exit' "$INSTALL_DIR/current/docker-compose.exit.yml" || err "exit override not restored"
grep -qx '1.0.44' "$INSTALL_DIR/data/DEPLOY_VERSION" || err "data/DEPLOY_VERSION not restored"
grep -qx '1.0.44' "$INSTALL_DIR/DEPLOY_VERSION" || err "host DEPLOY_VERSION not restored"
grep -qx 'old-compose' "$INSTALL_DIR/previous/docker-compose.yml" || err "previous/ must remain for a later rollback"
grep -q 'up -d --no-build --pull never' "$COMPOSE_LOG" || err "restore must compose up --no-build --pull never"
ok "restore_previous_release copies previous/ and DEPLOY_VERSION"

rm -rf "$INSTALL_DIR/previous"
if restore_previous_release; then
  err "missing previous compose file must fail"
else
  ok "missing previous fails closed"
fi

if grep -q '\[ -d "$INSTALL_DIR/previous/docker-compose.yml" \]' "$ROOT/server/install.sh"; then
  err "install.sh used [ -d previous/docker-compose.yml ] — rollback would never run"
fi
grep -q 'restore_previous_release' "$ROOT/server/install.sh" || err "install.sh must restore previous on failed switch"
grep -q 'stop_owned_stack' "$ROOT/server/install.sh" || err "install.sh must stop a failed first install"
grep -q 'restore_previous_release' "$ROOT/server/install-lib/uninstall.sh" || err "restore helper missing"
grep -q 'load_instance_from_env' "$ROOT/server/install-lib/uninstall.sh" || err "uninstall must read current/.env if instance.json is missing"
grep -q 'load_instance_from_env' "$ROOT/server/install-lib/ownership.sh" || err "load_instance_from_env helper missing"

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/ownership.sh"
ENV_ONLY="$TMP/env-only"
mkdir -p "$ENV_ONLY/current"
cat > "$ENV_ONLY/current/.env" <<EOF
ARDTT_INSTANCE_ID=deadbeef
COMPOSE_PROJECT_NAME=ardttdeadbeef
ARDTT_CONTAINER_NAME=ardtt-deadbeef
ARDTT_NETWORK_NAME=ardtt-deadbeef
ARDTT_BRIDGE_SUBNET=10.112.0.0/24
ARDTT_IMAGE=ardtt/server:1.0.45
EOF
INSTALL_DIR="$ENV_ONLY"
if load_instance; then
  err "load_instance must fail without instance.json"
else
  ok "load_instance ignores missing instance.json"
fi
load_instance_from_env || err "load_instance_from_env failed"
[ "$ARDTT_CONTAINER_NAME" = "ardtt-deadbeef" ] || err "env fallback container name"
[ "$INSTANCE_ID" = "deadbeef" ] || err "env fallback instance id"
ok "uninstall identity from current/.env"

if [ "$fail" -ne 0 ]; then
  echo "rollback restore tests failed" >&2
  exit 1
fi
echo "OK rollback restore"
