#!/usr/bin/env bash
# Token files are 0600, not rotated, host bind defaults to 127.0.0.1,
# ARDTT_DONE prints admin_token only on first create.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
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

[ "$fail" -eq 0 ] || { echo "provision secrets tests failed" >&2; exit 1; }
echo "OK install secrets and localhost bind"
