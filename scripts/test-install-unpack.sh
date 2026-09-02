#!/usr/bin/env bash
# Exercise install.sh unpack / data-preserve / re-run-without-tar (no Docker).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

STAGE="$WORKDIR/src"
for context in provision direct bypass dns warp telemetry-upload; do
  mkdir -p "$STAGE/$context"
done
cp "$ROOT/server/docker-compose.yml" "$STAGE/docker-compose.yml"
printf '%s\n' "1.0.6-test" > "$STAGE/DEPLOY_VERSION"
echo "test-readme" > "$STAGE/README.md"
mkdir -p "$STAGE/scripts"
echo '#!/bin/sh' > "$STAGE/scripts/create-user.sh"

tar -czf "$WORKDIR/stack.tar.gz" -C "$STAGE" .

INSTALL="$WORKDIR/opt"
mkdir -p "$INSTALL"
cp "$WORKDIR/stack.tar.gz" "$INSTALL/stack.tar.gz"
cp "$ROOT/server/install.sh" "$INSTALL/install.sh"
chmod +x "$INSTALL/install.sh"

run_install() {
  NVPN_INSTALL_DIR="$INSTALL" \
  NVPN_PUBLIC_HOST="203.0.113.9" \
  NVPN_DIRECT_PORT=51820 \
  NVPN_BYPASS_PORT=56003 \
  NVPN_DEPLOY_VERSION="1.0.6-test" \
  NVPN_SKIP_ROOT_CHECK=1 \
  NVPN_DRY_RUN=1 \
  NVPN_KEEP_INSTALL_LOG=1 \
  bash "$INSTALL/install.sh"
}

out="$(run_install)" || err "first install.sh exited $?"
echo "$out" | grep -q 'NVPN_DONE|dry_run=1' || err "first run missing NVPN_DONE dry_run"
echo "$out" | grep -q 'Распаковка стека' || err "first run did not unpack tar"
[ -f "$INSTALL/stack/docker-compose.yml" ] || err "stack not unpacked"
[ -f "$INSTALL/stack/.env" ] || err "missing .env"
grep -q 'NVPN_PUBLIC_HOST=203.0.113.9' "$INSTALL/stack/.env" || err ".env public host"
grep -q 'NVPN_DEPLOY_VERSION=1.0.6-test' "$INSTALL/stack/.env" || err ".env version"
grep -q 'TELEMETRY_LISTEN=0.0.0.0:9200' "$INSTALL/stack/.env" || err ".env telemetry listen"
grep -q 'NVPN_TELEMETRY_LISTEN=0.0.0.0:9200' "$INSTALL/stack/.env" || err ".env NVPN_TELEMETRY_LISTEN alias"
grep -q 'NVPN_TELEMETRY_PORT=9200' "$INSTALL/stack/.env" || err ".env NVPN_TELEMETRY_PORT"
[ -f "$INSTALL/DEPLOY_VERSION" ] || err "missing host DEPLOY_VERSION"
[ -f "$INSTALL/stack/data/DEPLOY_VERSION" ] || err "missing data/DEPLOY_VERSION"
[ ! -f "$INSTALL/stack.tar.gz" ] || err "tar should be deleted after run"

echo 'keep-me' > "$INSTALL/stack/data/users.json"

# Re-run with a fresh tar: data must survive replace.
cp "$WORKDIR/stack.tar.gz" "$INSTALL/stack.tar.gz"
out2="$(run_install)" || err "second install.sh exited $?"
echo "$out2" | grep -q 'Распаковка стека' || err "second run with tar should unpack"
[ -f "$INSTALL/stack/data/users.json" ] || err "users.json missing after tar update"
grep -qx 'keep-me' "$INSTALL/stack/data/users.json" || err "users.json not preserved across tar update"

# Re-run without tar (archive already deleted): keep existing tree + data.
out3="$(run_install)" || err "third install.sh exited $?"
echo "$out3" | grep -q 'уже распакованный стек' || err "third run should use existing stack"
grep -qx 'keep-me' "$INSTALL/stack/data/users.json" || err "users.json lost on tar-less re-run"
grep -q 'NVPN_PUBLIC_HOST=203.0.113.9' "$INSTALL/stack/.env" || err ".env rewritten on tar-less re-run"

if [ "$fail" -ne 0 ]; then
  echo "install.sh unpack tests failed" >&2
  exit 1
fi
ok "install.sh unpack / preserve / re-run"
