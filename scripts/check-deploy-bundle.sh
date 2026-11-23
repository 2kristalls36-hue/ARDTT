#!/usr/bin/env bash
# Deploy v2 contract (no Docker image build): the APK copy of the installer and
# the stack version match the tree, and the installer never does host-wide
# operations that would hurt other containers on the VPS.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/server/deploy/ardtt-deploy.sh"
ASSETS="$ROOT/android/app/src/main/assets/deploy"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

cmp -s "$DEPLOY" "$ASSETS/ardtt-deploy.sh" && ok "APK installer = server/deploy/ardtt-deploy.sh" \
  || err "android assets/deploy/ardtt-deploy.sh differs: run scripts/sync-deploy-assets.sh"
[ "$(tr -d '[:space:]' < "$ASSETS/DEPLOY_VERSION")" = "$VER" ] && ok "APK DEPLOY_VERSION $VER" \
  || err "assets/deploy/DEPLOY_VERSION != server/DEPLOY_VERSION ($VER)"
for f in fetch-and-install.sh ardtt-hostfiles-overlay.tar.gz; do
  [ ! -e "$ASSETS/$f" ] || err "deploy 1.0.x asset $f is still in the APK"
done
fallback="$(sed -n 's/.*FALLBACK_VERSION = "\(.*\)".*/\1/p' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployBundle.kt" | head -n 1)"
[ "$fallback" = "$VER" ] && ok "DeployBundle.FALLBACK_VERSION $VER" || err "DeployBundle.FALLBACK_VERSION=$fallback, stack $VER"

bash -n "$DEPLOY" || err "installer syntax"
# Host-wide operations are forbidden: other services share this Docker.
code="$(grep -v '^[[:space:]]*#' "$DEPLOY")"
for pat in 'system prune' 'image prune' 'volume prune' 'network prune' 'builder prune' 'compose down' \
           'daemon.json' 'iptables -F' 'iptables -X' 'sysctl -w' 'systemctl restart docker' 'rm -rf /var/lib/docker'; do
  if grep -qF -- "$pat" <<<"$code"; then err "installer contains host-wide «$pat»"; fi
done
ok "no host-wide operations"
grep -q 'ARDTT_DONE|install_dir=' "$DEPLOY" && ok "DONE line" || err "no ARDTT_DONE"

bash "$ROOT/scripts/test-deploy-v2.sh" || err "test-deploy-v2"

[ "$fail" -eq 0 ] || { echo "deploy bundle check failed" >&2; exit 1; }
echo "OK deploy bundle (stack $VER)"
