#!/usr/bin/env bash
# list_mounts_under must list BuildKit executor rootfs deepest-first so umount
# can drop "Device or resource busy" before rm -rf /var/lib/docker/buildkit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALLER="$ROOT/server/install.sh"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

eval "$(sed -n '/^list_mounts_under()/,/^}/p' "$INSTALLER")"
type list_mounts_under >/dev/null 2>&1 || { echo "list_mounts_under not extracted from $INSTALLER" >&2; exit 1; }

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
cat >"$TMP" <<'EOF'
overlay /var/lib/docker/overlay2/xyz/merged overlay rw 0 0
overlay /var/lib/docker/buildkit/executor/viw2xldst3ba47561zhs8sxo4/rootfs overlay rw 0 0
tmpfs /run tmpfs rw 0 0
overlay /var/lib/docker/buildkit overlay rw 0 0
none /var/lib/docker/buildkit-extra none rw 0 0
EOF

got="$(list_mounts_under /var/lib/docker/buildkit "$TMP")"
expect="$(printf '%s\n%s\n' \
  /var/lib/docker/buildkit/executor/viw2xldst3ba47561zhs8sxo4/rootfs \
  /var/lib/docker/buildkit)"

[ "$got" = "$expect" ] || err "deepest-first mounts:
got:
$got
want:
$expect"

none="$(list_mounts_under /var/lib/docker/buildkit-extra "$TMP" || true)"
[ "$none" = "/var/lib/docker/buildkit-extra" ] || err "exact dir must match, got [$none]"

prefix="$(list_mounts_under /var/lib/docker/buildkit "$TMP" | grep -c buildkit-extra || true)"
[ "$prefix" = "0" ] || err "must not match buildkit-extra as a child of buildkit"

if grep -E '^\s*rm -rf /var/lib/docker/buildkit\s*$' "$INSTALLER" >/dev/null; then
  err "bare rm -rf buildkit would abort install.sh under set -e when rootfs is busy"
fi
grep -q 'wipe_dir_best_effort /var/lib/docker/buildkit' "$INSTALLER" \
  || err "reset_docker_buildkit must wipe buildkit via wipe_dir_best_effort"
grep -q 'unmount_tree' "$INSTALLER" || err "installer missing unmount_tree"
grep -q 'docker.socket' "$INSTALLER" || err "stop docker.socket before wiping BuildKit"

if [ "$fail" -ne 0 ]; then
  echo "install buildkit wipe tests failed" >&2
  exit 1
fi
ok "BuildKit mount listing and busy-safe wipe"
