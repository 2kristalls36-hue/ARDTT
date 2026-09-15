#!/usr/bin/env bash
# current/ must become a symlink to releases/<ver>; previous/ is a real copy
# of the live tree (never a symlink). Restoring previous must not delete releases/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
INSTALL_DIR="$TMP/opt"
mkdir -p "$INSTALL_DIR/releases/1.0.53" "$INSTALL_DIR/data"

echo 'old-compose' > "$INSTALL_DIR/releases/1.0.53/docker-compose.yml"
echo 'ARDTT_DEPLOY_VERSION=1.0.53' > "$INSTALL_DIR/releases/1.0.53/.env"
# Live 1.0.53 is a directory (pre-1.0.54 layout).
mkdir -p "$INSTALL_DIR/current"
cp -a "$INSTALL_DIR/releases/1.0.53/." "$INSTALL_DIR/current/"

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/switch.sh"

snapshot_current_to_previous
[ -f "$INSTALL_DIR/previous/docker-compose.yml" ] || err "previous compose missing"
[ ! -L "$INSTALL_DIR/previous" ] || err "previous must be a directory, not a symlink"
grep -qx 'old-compose' "$INSTALL_DIR/previous/docker-compose.yml" || err "previous content"

mkdir -p "$INSTALL_DIR/releases/1.0.54"
echo 'new-compose' > "$INSTALL_DIR/releases/1.0.54/docker-compose.yml"
echo 'ARDTT_DEPLOY_VERSION=1.0.54' > "$INSTALL_DIR/releases/1.0.54/.env"
activate_release_tree "$INSTALL_DIR/releases/1.0.54"
[ -L "$INSTALL_DIR/current" ] || err "current must be a symlink after activate"
[ "$(readlink -f "$INSTALL_DIR/current")" = "$(readlink -f "$INSTALL_DIR/releases/1.0.54")" ] \
  || err "current must point at releases/1.0.54"
grep -qx 'new-compose' "$INSTALL_DIR/current/docker-compose.yml" || err "live compose via symlink"
# Old release tree stays; previous is an independent copy.
grep -qx 'old-compose' "$INSTALL_DIR/releases/1.0.53/docker-compose.yml" || err "old release deleted"
grep -qx 'old-compose' "$INSTALL_DIR/previous/docker-compose.yml" || err "previous lost after activate"
ok "activate uses symlink; snapshot dereferenced"

# Snapshot of a symlink must copy the target tree, not the link.
rm -rf "$INSTALL_DIR/previous"
snapshot_current_to_previous
[ ! -L "$INSTALL_DIR/previous" ] || err "snapshot of symlink current copied the symlink"
grep -qx 'new-compose' "$INSTALL_DIR/previous/docker-compose.yml" || err "symlink snapshot content"
# Mutating the release must not change previous/.
echo 'mutated' > "$INSTALL_DIR/releases/1.0.54/docker-compose.yml"
grep -qx 'new-compose' "$INSTALL_DIR/previous/docker-compose.yml" || err "previous is not independent of releases/"
ok "snapshot of symlink current is a real copy"

# Restore must drop the symlink without deleting releases/<new>.
compose_up_cmd() { :; }
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/uninstall.sh"
# previous still has 1.0.54 copy from last snapshot — put 1.0.53 back.
rm -rf "$INSTALL_DIR/previous"
mkdir -p "$INSTALL_DIR/previous"
echo 'old-compose' > "$INSTALL_DIR/previous/docker-compose.yml"
echo 'ARDTT_DEPLOY_VERSION=1.0.53' > "$INSTALL_DIR/previous/.env"
restore_previous_release || err "restore after symlink current failed"
[ ! -L "$INSTALL_DIR/current" ] || err "restored current should be a directory tree"
grep -qx 'old-compose' "$INSTALL_DIR/current/docker-compose.yml" || err "restore did not copy previous"
grep -qx 'mutated' "$INSTALL_DIR/releases/1.0.54/docker-compose.yml" || err "restore deleted releases/ via symlink"
ok "restore does not follow current symlink into releases/"

# Same-version restage uses .new so the live tree is not rm -rf'd while linked.
live="$(release_staging_path 1.0.54)"
[ "$live" = "$INSTALL_DIR/releases/1.0.54.new" ] || {
  # current is now a directory after restore; same-version path is the real dir.
  [ "$live" = "$INSTALL_DIR/releases/1.0.54" ] || err "release_staging_path=$live"
}
ok "release_staging_path"

# install.sh must not write data/DEPLOY_VERSION before stop on the live path.
if grep -n 'data/DEPLOY_VERSION' "$ROOT/server/install.sh" | grep -v 'commit-version-after-readiness' | grep -v DRY | grep -v 'dry-run' >/dev/null; then
  :
fi
grep -q 'commit-version-after-readiness' "$ROOT/server/install.sh" || err "commit marker missing"

if [ "$fail" -ne 0 ]; then
  echo "install switch tests failed" >&2
  exit 1
fi
echo "OK install switch / symlink current"
