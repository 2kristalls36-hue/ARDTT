#!/usr/bin/env bash
# Disk budget formula: fake sizes, same/different fs, cache hit/miss, floor.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

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

# docker image already loaded (missingDockerLayerBytes=0) on small docker fs is ok
out="$(run '{"installAvailableBytes": 3221225472, "dockerAvailableBytes": 10485760, "minDiskBytes": 0, "installFsDev": "1", "dockerFsDev": "2", "safelyReclaimableArdttBytes": 0, "candidateReleaseBytes": 8000000, "missingDockerLayerBytes": 0, "dockerImportBytesRaw": 0}')"
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ok"] is True, d' || err "loaded image should pass docker fs"
ok "loaded image: no docker-store demand"

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

# reclaimable is advisory only: it must not turn a short disk into ok=true
out="$(run '{"installAvailableBytes": 900000000, "dockerAvailableBytes": 900000000, "minDiskBytes": 0, "installFsDev": "1", "dockerFsDev": "1", "candidateReleaseBytes": 100, "missingDockerLayerBytes": 0, "safelyReclaimableArdttBytes": 800000000, "safetyMarginBytes": 0, "stateAndLogOverheadBytes": 0, "extractionOrStagingOverhead": 0}')" || true
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["reclaimableArdttBytes"]==800000000, d' || err "reclaimable accounted"
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["requiredBytes"] >= d["candidateReleaseBytes"], d' || err "reclaimable must not shrink required before cleanup"
ok "reclaimable bytes accounted as advisory"

# available=0 is fail-closed, never ok=true
set +e
out="$(run '{"installAvailableBytes": 0, "dockerAvailableBytes": 0, "minDiskBytes": 0, "installFsDev": "1", "dockerFsDev": "1", "candidateReleaseBytes": 100, "missingDockerLayerBytes": 0}')"
rc=$?
set -e
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ok"] is False, d' || err "available=0 must not be ok"
[ "$rc" -ne 0 ] || err "available=0 must be non-zero exit"
ok "available=0 fail-closed"

# missing / unparsed available → DISK_MEASUREMENT_FAILED
set +e
out="$(run '{"minDiskBytes": 0, "installFsDev": "1", "dockerFsDev": "1", "candidateReleaseBytes": 100}')"
set -e
echo "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ok"] is False and d.get("code") in ("DISK_MEASUREMENT_FAILED","INSUFFICIENT_DISK"), d' || err "missing available must fail"
ok "missing available fail-closed"

# cached compressed layer does not zero docker raw import
INSTALL_DIR="$TMP/opt"
mkdir -p "$INSTALL_DIR/cache/layers" "$INSTALL_DIR/images"
python3 - "$INSTALL_DIR" <<'PY'
import json, os, sys
root = sys.argv[1]
layout = {
  "layers": [{
    "diffId": "sha256:" + "ab"*32,
    "gzSize": 1048576,
    "rawSize": 1073741824,
  }]
}
os.makedirs(os.path.join(root, "images"), exist_ok=True)
json.dump(layout, open(os.path.join(root, "images", "layout.json"), "w"))
open(os.path.join(root, "cache", "layers", "abababababababab.tar.gz"), "wb").write(b"x")
PY
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/disk-budget.sh"
ARDTT_LAYER_CACHE_DIR="$INSTALL_DIR/cache/layers"
PKG_DIR="$INSTALL_DIR"
ARDTT_IMAGE=""
missing="$(_missing_layer_bytes "$INSTALL_DIR/images")"
[ "${missing:-0}" -gt 100000000 ] || err "cached gz 1MiB must not zero rawSize 1GiB docker budget (got $missing)"
ok "cached gz does not zero docker raw budget"

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
