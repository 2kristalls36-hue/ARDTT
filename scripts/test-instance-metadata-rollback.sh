#!/usr/bin/env bash
# Failed update must restore instance.json together with DEPLOY_VERSION.
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
cat > "$INSTALL_DIR/previous/.env" <<EOF
ARDTT_IMAGE=ardtt/server:1.0.44
ARDTT_DEPLOY_VERSION=1.0.44
COMPOSE_PROJECT_NAME=ardttabc
EOF
cat > "$INSTALL_DIR/previous/instance.json" <<EOF
{
  "instanceId": "abc",
  "composeProject": "ardttabc",
  "containerName": "ardtt",
  "networkName": "ardtt-abc",
  "bridgeSubnet": "10.112.0.0/24",
  "imageId": "sha256:oldimage",
  "imageTag": "ardtt/server:1.0.44",
  "deployVersion": "1.0.44",
  "role": "entry"
}
EOF
cp "$INSTALL_DIR/previous/.env" "$INSTALL_DIR/previous/root.env"
echo 'new-compose' > "$INSTALL_DIR/current/docker-compose.yml"
echo '1.0.45' > "$INSTALL_DIR/data/DEPLOY_VERSION"
echo '1.0.45' > "$INSTALL_DIR/DEPLOY_VERSION"
cat > "$INSTALL_DIR/instance.json" <<EOF
{
  "instanceId": "abc",
  "deployVersion": "1.0.45",
  "imageId": "sha256:newimage",
  "imageTag": "ardtt/server:1.0.45"
}
EOF
echo 'NEWENV=1' > "$INSTALL_DIR/.env"

compose_up_cmd() {
  echo "$*" >> "$COMPOSE_LOG"
  return 0
}

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/uninstall.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/ownership.sh"

restore_previous_release || err "restore_previous_release failed"
grep -qx '1.0.44' "$INSTALL_DIR/DEPLOY_VERSION" || err "DEPLOY_VERSION not restored"
python3 - "$INSTALL_DIR/instance.json" <<'PY' || err "instance.json not restored"
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
assert d["deployVersion"]=="1.0.44", d
assert d["imageId"]=="sha256:oldimage", d
PY
grep -qx 'ARDTT_IMAGE=ardtt/server:1.0.44' "$INSTALL_DIR/.env" || err "root .env not restored"
ok "restore_previous_release restores instance.json and root .env"

# Confirmed metadata is written only after readiness in install.sh.
if grep -n 'write_instance' "$ROOT/server/install.sh" | grep -v 'write_pending' >/dev/null; then
  :
fi
if awk '
  /write_env_file "\$release\/\.env"/ {seen=1}
  /write_instance$/ && seen && !done { early=1 }
  /wait_readiness/ {ready=1}
  /write_instance/ && ready {done=1}
  END { exit(early?1:0) }
' "$ROOT/server/install.sh"; then
  ok "install.sh does not commit instance.json before readiness"
else
  err "install.sh still writes confirmed instance.json before readiness"
fi
grep -q 'write_pending_instance' "$ROOT/server/install.sh" || err "first install must record pending ownership"
grep -q 'snapshot_confirmed_metadata' "$ROOT/server/install.sh" "$ROOT/server/install-lib/switch.sh" \
  || err "update must snapshot confirmed instance.json"
grep -q 'clear_pending_instance' "$ROOT/server/install.sh" || err "success must drop pending metadata"

# P0.5: snapshot of confirmed current must happen before candidate .env writes.
if ! awk '
  /snapshot_current_to_previous/ { snap=NR }
  /write_env_file "\$release\/\.env"/ { rel=NR }
  /write_env_file "\$INSTALL_DIR\/\.env"/ { root=NR }
  END {
    if (!snap) { print "no snapshot"; exit 1 }
    if (rel && snap > rel) { print "snapshot after release .env"; exit 1 }
    if (root && snap > root) { print "snapshot after root .env"; exit 1 }
  }
' "$ROOT/server/install.sh"; then
  err "install.sh snapshots rollback metadata after writing candidate .env"
else
  ok "install.sh snapshots confirmed metadata before candidate .env"
fi

# Behavioral: snapshot_confirmed_metadata must copy current release .env, not mutated root.
SNAP="$TMP/snap"
INSTALL_DIR="$SNAP"
mkdir -p "$SNAP/releases/old" "$SNAP/state"
echo 'ARDTT_DEPLOY_VERSION=1.0.53' > "$SNAP/releases/old/.env"
echo 'ARDTT_DIRECT_PORT=51820' >> "$SNAP/releases/old/.env"
echo 'GOOD' > "$SNAP/releases/old/docker-compose.yml"
ln -sfn "releases/old" "$SNAP/current"
echo 'ARDTT_DEPLOY_VERSION=1.0.54' > "$SNAP/.env"
echo 'ARDTT_DIRECT_PORT=51899' >> "$SNAP/.env"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/ownership.sh"
snapshot_confirmed_metadata "$SNAP/state/rollback"
grep -qx 'ARDTT_DEPLOY_VERSION=1.0.53' "$SNAP/state/rollback/root.env" \
  || err "rollback root.env must be the confirmed current release, not candidate root .env"
grep -q 'ARDTT_DIRECT_PORT=51820' "$SNAP/state/rollback/root.env" \
  || err "rollback port must be the old Direct port"
ok "snapshot uses current release .env, not mutated root"

if [ "$fail" -ne 0 ]; then
  echo "instance metadata rollback tests failed" >&2
  exit 1
fi
echo "OK instance metadata rollback"
exit 0
