#!/usr/bin/env bash
# Disk budget formula: fake sizes, same/different fs, cache hit/miss, floor.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

PY="$ROOT/server/install-lib/disk-budget.py"
run() {
  python3 "$PY" compute --json - <<<"$1"
}

# 0.8 GiB free, need ~1.6 GiB floor → fail
out="$(run '{"installAvailableBytes": 858993459, "dockerAvailableBytes": 858993459, "minDiskBytes": 1677721600, "installFsDev": "1", "dockerFsDev": "1", "safelyReclaimableArdttBytes": 0, "candidateReleaseBytes": 8000000, "missingDockerLayerBytes": 0, "installMount": "/opt/ardtt"}')" || true
echo "$out" | python3 -c 'import json,sys,os; d=json.load(sys.stdin); assert d["ok"] is False, d' || err "0.8G must fail floor"
ok "0.8 GiB < 1600 MiB floor"

# 3 GiB free, small candidate → ok
out="$(run '{"installAvailableBytes": 3221225472, "dockerAvailableBytes": 3221225472, "minDiskBytes": 1677721600, "installFsDev": "1", "dockerFsDev": "1", "safelyReclaimableArdttBytes": 0, "candidateReleaseBytes": 8000000, "missingDockerLayerBytes": 0}')" || err "3G should pass"
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ok"] is True, d' || err "3G ok flag"
ok "3 GiB sufficient"

# cache miss: 80 MiB missing layers on docker fs separate from 2 GiB install
out="$(run '{"installAvailableBytes": 2147483648, "dockerAvailableBytes": 10485760, "minDiskBytes": 0, "installFsDev": "1", "dockerFsDev": "2", "safelyReclaimableArdttBytes": 0, "candidateReleaseBytes": 8000000, "missingDockerLayerBytes": 83886080, "dockerMount": "/var/lib/docker"}')" || true
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ok"] is False and d["phase"]=="docker-store", d' || err "separate docker fs miss"
ok "different filesystem: docker store short"

# cache hit: missingDockerLayerBytes=0 on small docker fs is ok if install has space
out="$(run '{"installAvailableBytes": 3221225472, "dockerAvailableBytes": 10485760, "minDiskBytes": 0, "installFsDev": "1", "dockerFsDev": "2", "safelyReclaimableArdttBytes": 0, "candidateReleaseBytes": 8000000, "missingDockerLayerBytes": 0}')"
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ok"] is True, d' || err "cache hit should pass docker fs"
ok "cache hit: no docker-store demand"

# 1.0 / 1.5 / 2 GiB vs 1600 MiB floor
for pair in "1073741824:fail" "1610612736:fail" "2147483648:pass"; do
  bytes="${pair%%:*}"
  want="${pair##*:}"
  set +e
  out="$(run "{\"installAvailableBytes\": $bytes, \"dockerAvailableBytes\": $bytes, \"minDiskBytes\": 1677721600, \"installFsDev\": \"1\", \"dockerFsDev\": \"1\", \"safelyReclaimableArdttBytes\": 0, \"candidateReleaseBytes\": 8000000, \"missingDockerLayerBytes\": 0}")"
  rc=$?
  set -e
  if [ "$want" = "fail" ] && [ "$rc" -eq 0 ]; then
    err "${bytes} should fail floor"
  fi
  if [ "$want" = "pass" ] && [ "$rc" -ne 0 ]; then
    err "${bytes} should pass floor"
  fi
done
ok "1.0 / 1.5 / 2 GiB vs 1600 MiB floor"

# inode exhaustion
out="$(run '{"installAvailableBytes": 3221225472, "dockerAvailableBytes": 3221225472, "minDiskBytes": 0, "installInodesAvailable": 10, "inodeNeed": 2000, "installFsDev": "1", "dockerFsDev": "1", "candidateReleaseBytes": 1}')" || true
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ok"] is False and d["phase"]=="inodes", d' || err "inodes"
ok "inode exhaustion"

# reclaimable only if passed as safelyReclaimable
out="$(run '{"installAvailableBytes": 900000000, "dockerAvailableBytes": 900000000, "minDiskBytes": 0, "installFsDev": "1", "dockerFsDev": "1", "candidateReleaseBytes": 100, "missingDockerLayerBytes": 0, "safelyReclaimableArdttBytes": 800000000, "safetyMarginBytes": 0, "stateAndLogOverheadBytes": 0, "extractionOrStagingOverhead": 0}')"
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["reclaimableArdttBytes"]==800000000, d' || err "reclaimable accounted"
ok "reclaimable bytes accounted"

# Same fs: cannot double-count
out="$(run '{"installAvailableBytes": 3221225472, "dockerAvailableBytes": 3221225472, "minDiskBytes": 0, "installFsDev": "8", "dockerFsDev": "1", "candidateReleaseBytes": 100, "missingDockerLayerBytes": 50}')"
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["sameInstallDockerFs"] is False, d' || err "different fs flag"
ok "sameInstallDockerFs false when devices differ"

grep -q 'INSUFFICIENT_DISK' "$ROOT/server/install-lib/disk-budget.sh" || err "emit INSUFFICIENT_DISK"
grep -q 'MIN_DISK_MB:-${NVPN_MIN_DISK_MB:-1600}' "$ROOT/server/install.sh" || err "1600 floor default"

# Live peak measurement (loop device / tiny tmpfs) is NOT RUN here.
ok "formula cases (live peak NOT RUN: no isolated loop device in this job)"

if [ "$fail" -ne 0 ]; then
  echo "disk budget tests failed" >&2
  exit 1
fi
echo "OK disk budget"
exit 0
