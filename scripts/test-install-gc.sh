#!/usr/bin/env bash
# GC/cleanup may only touch ARDTT-owned paths; never foreign Docker or /var/log.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

CLEAN="$ROOT/server/install-lib/disk-cleanup.sh"
for pat in 'docker image prune' 'docker volume prune' 'docker container prune' \
           'docker system prune' 'ardtt_truncate_docker_json_logs' \
           'ardtt_purge_unused_linux_headers' 'journalctl --vacuum' \
           'find /var/log' 'apt-get -y purge'; do
  if grep -F "$pat" "$CLEAN" >/dev/null 2>&1; then
    err "cleanup still contains global op: $pat"
  fi
done
ok "disk-cleanup.sh has no global prune/truncate/headers"

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$CLEAN"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
INSTALL_DIR="$TMP/opt"
mkdir -p "$INSTALL_DIR/releases/keep-current" "$INSTALL_DIR/releases/keep-prev" \
  "$INSTALL_DIR/releases/obsolete" "$INSTALL_DIR/incoming" "$INSTALL_DIR/data" \
  "$TMP/foreign"

echo 'c' > "$INSTALL_DIR/releases/keep-current/docker-compose.yml"
echo 'p' > "$INSTALL_DIR/releases/keep-prev/docker-compose.yml"
echo 'o' > "$INSTALL_DIR/releases/obsolete/docker-compose.yml"
echo 'stale' > "$INSTALL_DIR/incoming/old.tar.gz"
echo 'keep-data' > "$INSTALL_DIR/data/users.json"
echo 'foreign' > "$TMP/foreign/keep-me"

ln -sfn "releases/keep-current" "$INSTALL_DIR/current"
ln -sfn "releases/keep-prev" "$INSTALL_DIR/previous"

# Before health gate: obsolete must stay.
ARDTT_ALLOW_RELEASE_GC=0 ardtt_gc_releases
[ -d "$INSTALL_DIR/releases/obsolete" ] || err "GC before health deleted a release"
ok "GC no-op before health gate"

ARDTT_ALLOW_RELEASE_GC=1 ardtt_gc_releases
[ -d "$INSTALL_DIR/releases/keep-current" ] || err "GC deleted current release"
[ -d "$INSTALL_DIR/releases/keep-prev" ] || err "GC deleted previous release"
[ ! -d "$INSTALL_DIR/releases/obsolete" ] || err "GC did not remove obsolete release"
grep -qx 'keep-data' "$INSTALL_DIR/data/users.json" || err "GC touched data/"
ok "GC after health protects current/previous, drops obsolete"

# Unsafe paths
if ardtt_safe_rm_rf / 2>/dev/null; then err "safe-rm /"; else ok "refuse /"; fi
if ardtt_safe_rm_rf /opt 2>/dev/null; then err "safe-rm /opt"; else ok "refuse /opt"; fi
if ardtt_safe_rm_rf "$INSTALL_DIR/releases" 2>/dev/null; then err "safe-rm releases root"; else ok "refuse releases root"; fi
if ardtt_safe_rm_rf "$TMP/foreign" 2>/dev/null; then err "safe-rm foreign"; else ok "refuse foreign path"; fi
[ -f "$TMP/foreign/keep-me" ] || err "foreign file deleted"
ok "foreign file intact"

# Symlink escape
ln -sfn "$TMP/foreign" "$INSTALL_DIR/releases/escape"
if ardtt_safe_rm_rf "$INSTALL_DIR/releases/escape" 2>/dev/null; then
  err "safe-rm followed symlink escape"
else
  ok "refuse symlink escape"
fi
[ -f "$TMP/foreign/keep-me" ] || err "escape deleted foreign target"

# Leftovers: stale incoming, keep current package
ARDTT_PACKAGE="$INSTALL_DIR/incoming/keep.tar.gz"
echo 'keep-pkg' > "$ARDTT_PACKAGE"
echo 'stale2' > "$INSTALL_DIR/incoming/stale.tar.gz"
echo 'part' > "$INSTALL_DIR/incoming/x.partial"
ardtt_cleanup_ardtt_leftovers
[ -f "$ARDTT_PACKAGE" ] || err "cleanup deleted the in-use package"
[ ! -f "$INSTALL_DIR/incoming/stale.tar.gz" ] || err "stale incoming remains"
[ ! -f "$INSTALL_DIR/incoming/x.partial" ] || err "partial remains"
ok "incoming leftovers"

# Repeat GC is safe
ARDTT_ALLOW_RELEASE_GC=1 ardtt_gc_releases
ok "repeat GC"

# Static: no docker prune by name, owner label required in ownership.sh
grep -q 'com.ardtt.owner' "$ROOT/server/install-lib/ownership.sh" || err "owner label"
if grep -q 'docker volume prune' "$ROOT/server/install.sh" "$ROOT/server/install-lib/"*.sh; then
  err "volume prune present"
fi
ok "no docker volume prune"

if [ "$fail" -ne 0 ]; then
  echo "GC/cleanup tests failed" >&2
  exit 1
fi
echo "OK install GC / cleanup safety"
exit 0
