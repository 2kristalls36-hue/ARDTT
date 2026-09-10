#!/usr/bin/env bash
# Replaces BuildKit-wipe tests: the installer must not stop Docker.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

if grep -q 'reset_docker_buildkit' "$ROOT/server/install.sh"; then
  err "installer still has reset_docker_buildkit"
fi
if grep -q 'systemctl stop docker' "$ROOT/server/install.sh" "$ROOT/server/install-lib/"*.sh; then
  err "installer still stops docker"
fi
if grep -q '/var/lib/docker/buildkit' "$ROOT/server/install.sh" "$ROOT/server/install-lib/"*.sh; then
  err "installer still touches BuildKit dir"
fi
grep -q 'ensure_docker_engine' "$ROOT/server/install.sh" || err "must install bundled Engine when missing"
if grep -q 'get.docker.com' "$ROOT/server/install.sh" "$ROOT/server/install-lib/"*.sh; then
  err "must not fetch Engine from the network"
fi
ok "installer does not stop Docker or wipe BuildKit"
exit 0
