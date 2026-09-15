#!/usr/bin/env bash
# current/previous are atomic symlinks into releases/<id>. Snapshot does not copy trees.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

if grep -E 'cp[[:space:]]+-a[[:space:]]+"\$src"' "$ROOT/server/install-lib/switch.sh"; then
  err "snapshot must not cp -a the live tree"
fi
if grep -q 'drop_current_pointer' "$ROOT/server/install-lib/switch.sh"; then
  err "drop_current_pointer must not exist (window where current is missing)"
fi
grep -q 'atomic-pointer.py' "$ROOT/server/install-lib/switch.sh" || err "must use atomic-pointer.py"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
INSTALL_DIR="$TMP/opt"
mkdir -p "$INSTALL_DIR/releases/1.0.53" "$INSTALL_DIR/data"

echo 'old-compose' > "$INSTALL_DIR/releases/1.0.53/docker-compose.yml"
echo 'ARDTT_DEPLOY_VERSION=1.0.53' > "$INSTALL_DIR/releases/1.0.53/.env"
# Live 1.0.53 is a directory (pre-pointer layout).
mkdir -p "$INSTALL_DIR/current"
cp -a "$INSTALL_DIR/releases/1.0.53/." "$INSTALL_DIR/current/"

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/ownership.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/switch.sh"

snapshot_current_to_previous
[ -L "$INSTALL_DIR/previous" ] || err "previous must be a symlink after snapshot"
[ -f "$INSTALL_DIR/previous/docker-compose.yml" ] || err "previous compose missing"
grep -qx 'old-compose' "$INSTALL_DIR/previous/docker-compose.yml" || err "previous content"
[ -L "$INSTALL_DIR/current" ] || err "legacy current dir must migrate to a symlink"
[ -d "$INSTALL_DIR/state/rollback" ] || err "rollback metadata must live outside the release"
[ ! -f "$INSTALL_DIR/releases/1.0.53/instance.json" ] || err "must not write metadata into the release"

mkdir -p "$INSTALL_DIR/releases/1.0.54"
echo 'new-compose' > "$INSTALL_DIR/releases/1.0.54/docker-compose.yml"
echo 'ARDTT_DEPLOY_VERSION=1.0.54' > "$INSTALL_DIR/releases/1.0.54/.env"
activate_release_tree "$INSTALL_DIR/releases/1.0.54"
[ -L "$INSTALL_DIR/current" ] || err "current must be a symlink after activate"
[ "$(readlink -f "$INSTALL_DIR/current")" = "$(readlink -f "$INSTALL_DIR/releases/1.0.54")" ] \
  || err "current must point at releases/1.0.54"
[ "$(readlink -f "$INSTALL_DIR/previous")" = "$(readlink -f "$INSTALL_DIR/releases/1.0.53")" ] \
  || err "previous must still point at 1.0.53"
grep -qx 'new-compose' "$INSTALL_DIR/current/docker-compose.yml" || err "live compose via symlink"
# Pointers share the release inode; snapshot is not an independent copy.
ino1="$(stat -c %i "$INSTALL_DIR/releases/1.0.53/docker-compose.yml")"
ino2="$(stat -c %i "$INSTALL_DIR/previous/docker-compose.yml")"
[ "$ino1" = "$ino2" ] || err "previous must be the same inode as the release (no copy)"
ok "activate/snapshot are pointers, not copies"

# Mutating an immutable release is a bug in the writer, not a snapshot property.
# Restage of the same version uses .new so the live tree is not replaced in place.
live="$(release_staging_path 1.0.54)"
[ "$live" = "$INSTALL_DIR/releases/1.0.54.new" ] || err "release_staging_path=$live"
ok "release_staging_path uses .new when live"

compose_up_cmd() { :; }
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/uninstall.sh"
restore_previous_release || err "restore after symlink current failed"
[ -L "$INSTALL_DIR/current" ] || err "restored current must stay a symlink"
grep -qx 'old-compose' "$INSTALL_DIR/current/docker-compose.yml" || err "restore did not retarget previous"
grep -qx 'new-compose' "$INSTALL_DIR/releases/1.0.54/docker-compose.yml" || err "restore deleted releases/"
[ "$(readlink -f "$INSTALL_DIR/current")" = "$(readlink -f "$INSTALL_DIR/releases/1.0.53")" ] \
  || err "rollback current is not 1.0.53"
ok "restore retargets current; does not copy or delete releases"

grep -q 'commit-version-after-readiness' "$ROOT/server/install.sh" || err "commit marker missing"

if [ "$fail" -ne 0 ]; then
  echo "install switch tests failed" >&2
  exit 1
fi
echo "OK install switch / atomic pointers"
exit 0
