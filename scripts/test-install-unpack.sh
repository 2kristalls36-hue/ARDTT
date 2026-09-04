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

echo leftover-live > "$INSTALL/install-live.log"
echo leftover-run > "$INSTALL/install-run.log"
mkdir -p "$INSTALL/stack.old" "$INSTALL/stack.staging"
echo stale-old > "$INSTALL/stack.old/junk"
echo keep-staging-until-success > "$INSTALL/stack.staging/junk"

# Isolated leftover cleanup: drop old logs / stack.old, keep in-progress staging.
leftover_root="$(mktemp -d)"
mkdir -p "${leftover_root}/stack.old" "${leftover_root}/stack.staging"
echo old > "${leftover_root}/stack.old/a"
echo staging > "${leftover_root}/stack.staging/b"
echo live > "${leftover_root}/install-live.log"
echo run > "${leftover_root}/install-run.log"
(
  set -euo pipefail
  INSTALL_DIR="$leftover_root"
  eval "$(sed -n '/^cleanup_stale_deploy_files()/,/^}/p' "$ROOT/server/install.sh")"
  cleanup_stale_deploy_files
)
[ ! -e "${leftover_root}/install-live.log" ] || err "cleanup_stale left install-live.log"
[ ! -e "${leftover_root}/install-run.log" ] || err "cleanup_stale left install-run.log"
[ ! -d "${leftover_root}/stack.old" ] || err "cleanup_stale left stack.old"
[ -f "${leftover_root}/stack.staging/b" ] || err "cleanup_stale must keep stack.staging"
rm -rf "${leftover_root}"

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
grep -q 'NVPN_ROLE=entry' "$INSTALL/stack/.env" || err ".env default role entry"
grep -q 'NVPN_CASCADE_ENABLED=0' "$INSTALL/stack/.env" || err ".env cascade off by default"
grep -q '^NVPN_CASCADE_DNS=$' "$INSTALL/stack/.env" || err "standalone first install must leave hop DNS empty"
if grep -qi 'PASSWORD=' "$INSTALL/stack/.env"; then
  err ".env must not contain PASSWORD"
fi
[ -f "$INSTALL/DEPLOY_VERSION" ] || err "missing host DEPLOY_VERSION"
[ -f "$INSTALL/stack/data/DEPLOY_VERSION" ] || err "missing data/DEPLOY_VERSION"
[ ! -f "$INSTALL/stack.tar.gz" ] || err "tar should be deleted after run"
[ ! -f "$INSTALL/install-live.log" ] || err "install-live.log leftover after dry-run"
[ ! -f "$INSTALL/install-run.log" ] || err "install-run.log leftover after dry-run"
[ ! -d "$INSTALL/stack.staging" ] || err "stack.staging leftover after successful dry-run"
[ ! -d "$INSTALL/stack.old" ] || err "stack.old leftover after successful dry-run"

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
grep -q '^NVPN_CASCADE_DNS=$' "$INSTALL/stack/.env" || err "standalone .env must not set hop DNS"

# Live cascade flags on entry must survive an update that omits them.
sed -i 's/^NVPN_CASCADE_ENABLED=.*/NVPN_CASCADE_ENABLED=1/' "$INSTALL/stack/.env"
sed -i 's/^NVPN_CASCADE_PEER_ENDPOINT=.*/NVPN_CASCADE_PEER_ENDPOINT=2.26.125.160:51820/' "$INSTALL/stack/.env"
sed -i 's/^NVPN_CASCADE_PEER_PUBLIC_KEY=.*/NVPN_CASCADE_PEER_PUBLIC_KEY=abc+DEF\/123=/' "$INSTALL/stack/.env"
mkdir -p "$INSTALL/stack/data"
printf '%s\n' '2.26.125.160:51820' > "$INSTALL/stack/data/cascade.peer.endpoint"
printf '%s\n' 'abc+DEF/123=' > "$INSTALL/stack/data/cascade.peer.pub"
printf '%s\n' 'fake-priv' > "$INSTALL/stack/data/cascade.priv"
cp "$WORKDIR/stack.tar.gz" "$INSTALL/stack.tar.gz"
out_preserve="$(run_install)" || err "preserve-cascade install.sh exited $?"
echo "$out_preserve" | grep -q 'каскад сохранён с прошлого деплоя' || err "missing live-cascade preserve warning"
grep -q '^NVPN_CASCADE_ENABLED=1$' "$INSTALL/stack/.env" || err "cascade flag not preserved"
grep -q '^NVPN_CASCADE_PEER_ENDPOINT=2.26.125.160:51820$' "$INSTALL/stack/.env" || err "cascade peer endpoint not preserved"
grep -q '^NVPN_CASCADE_PEER_PUBLIC_KEY=abc+DEF/123=$' "$INSTALL/stack/.env" || err "cascade peer key not preserved"
grep -q '^NVPN_CASCADE_DNS=10.10.0.2$' "$INSTALL/stack/.env" || err "cascade DNS not restored when hop is on"
grep -q '^NVPN_WARP_MODE=passthrough$' "$INSTALL/stack/.env" || err "cascade entry must not WARP locally"

run_exit() {
  NVPN_INSTALL_DIR="$INSTALL" \
  NVPN_PUBLIC_HOST="203.0.113.10" \
  NVPN_ROLE=exit \
  NVPN_DEPLOY_VERSION="1.0.6-test" \
  NVPN_SKIP_ROOT_CHECK=1 \
  NVPN_DRY_RUN=1 \
  NVPN_KEEP_INSTALL_LOG=1 \
  bash "$INSTALL/install.sh"
}
out4="$(run_exit)" || err "exit-role install.sh exited $?"
echo "$out4" | grep -q 'NVPN_DONE|dry_run=1' || err "exit dry-run missing NVPN_DONE"
grep -q 'NVPN_ROLE=exit' "$INSTALL/stack/.env" || err ".env role exit"
grep -q 'NVPN_CASCADE_ENABLED=1' "$INSTALL/stack/.env" || err "exit forces cascade enabled"
grep -q 'NVPN_WARP_MODE=exit-hideip' "$INSTALL/stack/.env" || err "exit warp mode exit-hideip"
grep -q 'NVPN_WARP_HIDEIP_URL=http://10.10.0.1:9100/v1/hide-ip-prefixes' "$INSTALL/stack/.env" || err "exit hideIp URL"
grep -Eq 'NVPN_WARP_DNS_IIFACES=.*cascade0' "$INSTALL/stack/.env" || err "exit DNS iif cascade0"
if grep -qi 'PASSWORD=' "$INSTALL/stack/.env"; then
  err "exit .env must not contain PASSWORD"
fi

if [ "$fail" -ne 0 ]; then
  echo "install.sh unpack tests failed" >&2
  exit 1
fi
ok "install.sh unpack / preserve / re-run"
