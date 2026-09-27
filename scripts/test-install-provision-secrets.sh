#!/usr/bin/env bash
# Token files are 0600, not rotated, host bind defaults to 127.0.0.1,
# ARDTT_DONE prints admin_token only on first create.
# An upgrade keeps a publish that was already reachable from the network.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/secrets.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }

INSTALL_DIR="$TMP/opt"
ROLE=entry
CASCADE_ENABLED=0
mkdir -p "$INSTALL_DIR/data"

ensure_install_secrets "$INSTALL_DIR/data"
[ "$ADMIN_TOKEN_NEW" = 1 ] || err "first admin token should be new"
[ "${#ADMIN_TOKEN}" -eq 64 ] || err "admin token hex length ${#ADMIN_TOKEN}"
[ -s "$INSTALL_DIR/data/admin.token" ] || err "admin.token missing"
mode="$(stat -c '%a' "$INSTALL_DIR/data/admin.token" 2>/dev/null || stat -f '%OLp' "$INSTALL_DIR/data/admin.token")"
[ "$mode" = "600" ] || err "admin.token mode $mode"
first="$ADMIN_TOKEN"

ensure_install_secrets "$INSTALL_DIR/data"
[ "$ADMIN_TOKEN_NEW" = 0 ] || err "second call rotated admin token"
[ "$ADMIN_TOKEN" = "$first" ] || err "admin token changed"

bind="$(host_publish_bind)"
[ "$bind" = "127.0.0.1" ] || err "default bind $bind"
ARDTT_PROVISION_PUBLIC=1
bind="$(host_publish_bind)"
[ "$bind" = "0.0.0.0" ] || err "public bind $bind"
unset ARDTT_PROVISION_PUBLIC

grep -q 'ARDTT_PROVISION_BIND:-127.0.0.1' "$ROOT/server/docker-compose.yml" \
  || err "compose must bind provision to 127.0.0.1 by default"
grep -q 'ARDTT_TELEMETRY_BIND:-127.0.0.1' "$ROOT/server/docker-compose.yml" \
  || err "compose must bind telemetry to 127.0.0.1 by default"
grep -q 'ARDTT_PROVISION_BIND:-127.0.0.1' "$ROOT/server/docker-compose.exit.yml" \
  || err "exit compose must bind provision to 127.0.0.1 by default"
grep -q 'host_publish_bind' "$ROOT/server/install.sh" \
  || err "install.sh must set host publish bind"
grep -q 'inherit_provision_publish' "$ROOT/server/install.sh" \
  || err "install.sh must inherit an already public provision publish"
grep -q 'ensure_install_secrets' "$ROOT/server/install.sh" \
  || err "install.sh must generate secrets"
grep -q 'INSTALL_LIB_DIR/secrets.sh' "$ROOT/server/install.sh" \
  || err "install.sh must source secrets.sh"
grep -q 'done_secret_fields' "$ROOT/server/install.sh" \
  || err "install.sh must emit secret fields via done_secret_fields"
grep -q 'admin_token=' "$ROOT/server/install-lib/secrets.sh" \
  || err "secrets.sh must emit admin_token on first DONE"
grep -q 'provision_cert_fp=' "$ROOT/server/install-lib/secrets.sh" \
  || err "secrets.sh must emit provision_cert_fp"
grep -q 'Authorization' "$ROOT/server/install.sh" \
  || err "cascade peer push must send Authorization"
grep -q 'Authorization: Bearer' "$ROOT/server/warp/entrypoint.sh" \
  || err "warp hide-ip poll must send cascade bearer"

# Tokens must not land in the .env heredoc.
if grep -n 'ADMIN_TOKEN\|admin.token\|CASCADE_SECRET\|telemetry.token' "$ROOT/server/install.sh" | grep -q write_env_file; then
  err "secrets must not be written inside write_env_file"
fi
if grep -q 'ARDTT_ADMIN_TOKEN=\$ADMIN_TOKEN' "$ROOT/server/install.sh"; then
  err "admin token must not be interpolated into .env"
fi

# Upgrade must not hide a port the phone already probes.
ARDTT_CONTAINER_NAME="ardtt-inherit-missing"
unset ARDTT_PROVISION_PUBLIC || true

INSTALL_DIR="$TMP/fresh"
inherit_provision_publish
[ "${ARDTT_PROVISION_PUBLIC:-}" = "0" ] || err "fresh install must stay private (got ${ARDTT_PROVISION_PUBLIC:-})"
[ "$(host_publish_bind)" = "127.0.0.1" ] || err "fresh bind"

unset ARDTT_PROVISION_PUBLIC
INSTALL_DIR="$TMP/listen-only"
mkdir -p "$INSTALL_DIR"
printf 'ARDTT_PROVISION_LISTEN=0.0.0.0:9100\n' >"$INSTALL_DIR/.env"
inherit_provision_publish
[ "${ARDTT_PROVISION_PUBLIC:-}" = "0" ] || err "in-container listen must not imply a public host bind"

unset ARDTT_PROVISION_PUBLIC
INSTALL_DIR="$TMP/recorded-public"
mkdir -p "$INSTALL_DIR"
printf 'ARDTT_PROVISION_BIND=0.0.0.0\nARDTT_PROVISION_PUBLIC=1\n' >"$INSTALL_DIR/.env"
inherit_provision_publish
[ "${ARDTT_PROVISION_PUBLIC:-}" = "1" ] || err "recorded 0.0.0.0 bind must stay public"
[ "$(host_publish_bind)" = "0.0.0.0" ] || err "recorded public bind"

unset ARDTT_PROVISION_PUBLIC
INSTALL_DIR="$TMP/legacy-upgrade"
mkdir -p "$INSTALL_DIR/current" "$INSTALL_DIR/previous"
printf 'ARDTT_PROVISION_BIND=127.0.0.1\nARDTT_PROVISION_PUBLIC=0\nARDTT_PROVISION_LISTEN=0.0.0.0:9100\n' >"$INSTALL_DIR/.env"
printf '%s\n' '- "${ARDTT_PROVISION_BIND:-127.0.0.1}:${ARDTT_PROVISION_PORT:-9100}:9100/tcp"' \
  >"$INSTALL_DIR/current/docker-compose.yml"
printf '%s\n' '- "${ARDTT_PROVISION_PORT:-9100}:9100/tcp"' \
  >"$INSTALL_DIR/previous/docker-compose.yml"
inherit_provision_publish
[ "${ARDTT_PROVISION_PUBLIC:-}" = "1" ] || err "legacy compose publish must stay public across upgrade"

ARDTT_PROVISION_PUBLIC=0
inherit_provision_publish
[ "${ARDTT_PROVISION_PUBLIC:-}" = "0" ] || err "explicit ARDTT_PROVISION_PUBLIC=0 must win"
[ "$(host_publish_bind)" = "127.0.0.1" ] || err "explicit private bind"
unset ARDTT_PROVISION_PUBLIC

INSTALL_DIR="$TMP/locked"
mkdir -p "$INSTALL_DIR/current" "$INSTALL_DIR/previous"
printf 'ARDTT_PROVISION_BIND=127.0.0.1\nARDTT_PROVISION_PUBLIC=0\n' >"$INSTALL_DIR/.env"
printf '%s\n' '- "${ARDTT_PROVISION_BIND:-127.0.0.1}:${ARDTT_PROVISION_PORT:-9100}:9100/tcp"' \
  >"$INSTALL_DIR/current/docker-compose.yml"
cp "$INSTALL_DIR/current/docker-compose.yml" "$INSTALL_DIR/previous/docker-compose.yml"
inherit_provision_publish
[ "${ARDTT_PROVISION_PUBLIC:-}" = "0" ] || err "localhost install with new compose must stay private"

[ "$fail" -eq 0 ] || { echo "provision secrets tests failed" >&2; exit 1; }
echo "OK install secrets and localhost bind"
