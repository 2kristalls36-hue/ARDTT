#!/usr/bin/env bash
# Compose CPU/mem must fit tiny VPS (1 vCPU cannot use cpus: 2.0).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

# shellcheck disable=SC1091
source "$ROOT/server/install-lib/common.sh"

host_cpu_count() { echo 1; }
got="$(ARDTT_CPUS=2.0 resolve_ardtt_cpus)"
case "$got" in
  1|1.0|1.00) ok "clamp 2.0→$got on 1 CPU" ;;
  *) err "clamp 2.0→1 on 1 CPU, got $got" ;;
esac

host_cpu_count() { echo 4; }
unset ARDTT_CPUS || true
got2="$(resolve_ardtt_cpus)"
[ "$got2" = "2.0" ] || err "default on ≥2 CPU should be 2.0, got $got2"
ok "default 2.0 on 4 CPU"

host_cpu_count() { echo 1; }
unset ARDTT_CPUS || true
got3="$(resolve_ardtt_cpus)"
[ "$got3" = "1.0" ] || err "default on 1 CPU should be 1.0, got $got3"
ok "default 1.0 on 1 CPU"

grep -q 'resolve_ardtt_cpus' "$ROOT/server/install.sh" || err "install.sh must resolve cpus"
grep -q 'COMPOSE_UP_FAILED' "$ROOT/server/install.sh" || err "compose failures need COMPOSE_UP_FAILED"
grep -q 'hostCpus' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/DeployInstallEnv.kt" \
  || err "phone must pass hostCpus / ARDTT_CPUS"
grep -q 'probeHostCpus' "$ROOT/android/app/src/main/java/com/ardtt/app/deploy/ServerOsProbe.kt" \
  || err "missing probeHostCpus"

# Also clamp in fetch-and-install for CLI / old APK path
if ! grep -q 'ARDTT_CPUS\|nproc' "$ROOT/server/fetch-and-install.sh"; then
  err "fetch-and-install should clamp CPUs before install.sh"
fi

ok "compose cpu clamp"
exit "$fail"
