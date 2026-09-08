#!/usr/bin/env bash
# Exercise install.sh unpack / data-preserve / dry-run (no Docker Engine required).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
esac

PKG="$WORKDIR/ardtt-server-1.0.45-test-linux-${ARCH}.tar.gz"
bash "$ROOT/scripts/make-fake-server-package.sh" "$PKG" "$ARCH" "1.0.45-test"
SHA="$(cat "${PKG}.sha256")"

run_install() {
  local dir="$1"
  shift
  ARDTT_INSTALL_DIR="$dir" \
  ARDTT_PUBLIC_HOST="203.0.113.9" \
  ARDTT_DIRECT_PORT=51820 \
  ARDTT_BYPASS_PORT=56003 \
  ARDTT_DEPLOY_VERSION="1.0.45-test" \
  ARDTT_PACKAGE="$dir/incoming/pkg.tar.gz" \
  ARDTT_PACKAGE_SHA256="$SHA" \
  ARDTT_SKIP_ROOT_CHECK=1 \
  ARDTT_DRY_RUN=1 \
  ARDTT_KEEP_INSTALL_LOG=1 \
  bash "$ROOT/server/install.sh" "$@"
}

INSTALL="$WORKDIR/opt"
mkdir -p "$INSTALL/incoming"
cp "$PKG" "$INSTALL/incoming/pkg.tar.gz"

out="$(run_install "$INSTALL")" || err "first install.sh exited $?"
echo "$out" | grep -q 'ARDTT_DONE|dry_run=1' || err "first run missing ARDTT_DONE dry_run"
echo "$out" | grep -q 'SHA-256' || err "first run did not verify sha256"
[ -f "$INSTALL/.env" ] || [ -f "$INSTALL/current/.env" ] || err "missing .env"
ENVF="$INSTALL/current/.env"
[ -f "$ENVF" ] || ENVF="$INSTALL/.env"
grep -q 'ARDTT_PUBLIC_HOST=203.0.113.9' "$ENVF" || err ".env public host"
grep -q 'ARDTT_DEPLOY_VERSION=1.0.45-test' "$ENVF" || err ".env version"
grep -q 'TELEMETRY_LISTEN=0.0.0.0:9200' "$ENVF" || err ".env telemetry listen inner 9200"
grep -q 'ARDTT_TELEMETRY_PORT=9200' "$ENVF" || err ".env host telemetry port"
grep -q 'ARDTT_ROLE=entry' "$ENVF" || err ".env default role entry"
grep -q 'ARDTT_CASCADE_ROLE=entry' "$ENVF" || err ".env default cascade role entry"
grep -q 'ARDTT_CASCADE_ENABLED=0' "$ENVF" || err ".env cascade off by default"
grep -q '^ARDTT_NETWORK_MODE=isolated$' "$ENVF" || err "default network mode must be isolated"
grep -q '^ARDTT_CASCADE_DNS=$' "$ENVF" || err "standalone first install must leave hop DNS empty"
grep -q 'ARDTT_DATA_DIR=' "$ENVF" || err ".env data dir"
if grep -qi 'PASSWORD=' "$ENVF"; then
  err ".env must not contain PASSWORD"
fi
[ -f "$INSTALL/DEPLOY_VERSION" ] || err "missing host DEPLOY_VERSION"
[ -f "$INSTALL/data/DEPLOY_VERSION" ] || err "missing data/DEPLOY_VERSION"
[ -f "$INSTALL/instance.json" ] || err "missing instance.json"

echo 'keep-me' > "$INSTALL/data/users.json"

# Re-run: data must survive.
out2="$(run_install "$INSTALL")" || err "second install.sh exited $?"
grep -qx 'keep-me' "$INSTALL/data/users.json" || err "users.json not preserved"

# Live cascade flags on entry must survive an update that omits them.
sed -i 's/^ARDTT_CASCADE_ENABLED=.*/ARDTT_CASCADE_ENABLED=1/' "$ENVF"
sed -i 's/^ARDTT_CASCADE_PEER_ENDPOINT=.*/ARDTT_CASCADE_PEER_ENDPOINT=2.26.125.160:51820/' "$ENVF"
sed -i 's/^ARDTT_CASCADE_PEER_PUBLIC_KEY=.*/ARDTT_CASCADE_PEER_PUBLIC_KEY=abc+DEF\/123=/' "$ENVF"
mkdir -p "$INSTALL/data"
printf '%s\n' '2.26.125.160:51820' > "$INSTALL/data/cascade.peer.endpoint"
printf '%s\n' 'abc+DEF/123=' > "$INSTALL/data/cascade.peer.pub"
printf '%s\n' 'fake-priv' > "$INSTALL/data/cascade.priv"
# Installer reads legacy env from INSTALL/.env or current/.env
cp -f "$ENVF" "$INSTALL/.env"
out_preserve="$(run_install "$INSTALL")" || err "preserve-cascade install.sh exited $?"
echo "$out_preserve" | grep -q 'каскад сохранён с прошлого деплоя' || err "missing live-cascade preserve warning"
ENVF2="$INSTALL/current/.env"
[ -f "$ENVF2" ] || ENVF2="$INSTALL/.env"
grep -q '^ARDTT_CASCADE_ENABLED=1$' "$ENVF2" || err "cascade flag not preserved"
grep -q '^ARDTT_CASCADE_PEER_ENDPOINT=2.26.125.160:51820$' "$ENVF2" || err "cascade peer endpoint not preserved"
grep -q '^ARDTT_CASCADE_DNS=10.10.0.2$' "$ENVF2" || err "cascade DNS not restored when hop is on"
grep -q '^ARDTT_WARP_MODE=passthrough$' "$ENVF2" || err "cascade entry must not WARP locally"

run_exit() {
  ARDTT_INSTALL_DIR="$INSTALL" \
  ARDTT_PUBLIC_HOST="203.0.113.10" \
  ARDTT_ROLE=exit \
  ARDTT_PACKAGE="$INSTALL/incoming/pkg.tar.gz" \
  ARDTT_PACKAGE_SHA256="$SHA" \
  ARDTT_DEPLOY_VERSION="1.0.45-test" \
  ARDTT_SKIP_ROOT_CHECK=1 \
  ARDTT_DRY_RUN=1 \
  ARDTT_KEEP_INSTALL_LOG=1 \
  bash "$ROOT/server/install.sh"
}
out4="$(run_exit)" || err "exit-role install.sh exited $?"
echo "$out4" | grep -q 'ARDTT_DONE|dry_run=1' || err "exit dry-run missing ARDTT_DONE"
grep -q 'ARDTT_ROLE=exit' "$INSTALL/current/.env" "$INSTALL/.env" 2>/dev/null | head -1 || true
if ! grep -q 'ARDTT_ROLE=exit' "$INSTALL/current/.env" 2>/dev/null && ! grep -q 'ARDTT_ROLE=exit' "$INSTALL/.env"; then
  err ".env role exit"
fi
if ! grep -q 'ARDTT_WARP_MODE=exit-hideip' "$INSTALL/current/.env" 2>/dev/null && ! grep -q 'ARDTT_WARP_MODE=exit-hideip' "$INSTALL/.env"; then
  err "exit warp mode exit-hideip"
fi

# Wrong SHA must fail before ARDTT_DONE.
set +e
bad="$(
  ARDTT_INSTALL_DIR="$WORKDIR/opt-bad" \
  ARDTT_PUBLIC_HOST="203.0.113.9" \
  ARDTT_PACKAGE="$PKG" \
  ARDTT_PACKAGE_SHA256="0000000000000000000000000000000000000000000000000000000000000000" \
  ARDTT_SKIP_ROOT_CHECK=1 \
  ARDTT_DRY_RUN=1 \
  bash "$ROOT/server/install.sh" 2>&1
)"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "wrong sha must fail"
echo "$bad" | grep -q 'ARDTT_ERROR|' || err "wrong sha must emit ARDTT_ERROR"
echo "$bad" | grep -q 'ARDTT_DONE' && err "wrong sha must not emit ARDTT_DONE"
ok "wrong digest rejected before switch"

# Missing package
set +e
missing="$(
  ARDTT_INSTALL_DIR="$WORKDIR/opt-miss" \
  ARDTT_PUBLIC_HOST="203.0.113.9" \
  ARDTT_PACKAGE_SHA256="$SHA" \
  ARDTT_SKIP_ROOT_CHECK=1 \
  ARDTT_DRY_RUN=1 \
  bash "$ROOT/server/install.sh" 2>&1
)"
rc=$?
set -e
[ "$rc" -ne 0 ] || err "missing package must fail"
echo "$missing" | grep -q 'Нет пакета' || err "missing package message"

# hostnet env must not be written
if grep -q 'COMPOSE_PROFILES=hostnet' "$INSTALL/.env" "$INSTALL/current/.env" 2>/dev/null; then
  err "hostnet profile written"
fi

if [ "$fail" -ne 0 ]; then
  echo "install.sh unpack tests failed" >&2
  exit 1
fi
ok "install.sh unpack / preserve / sha / exit role"
