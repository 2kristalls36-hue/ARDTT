# shellcheck shell=bash
# Peak disk budget from actual sizes. Emits INSUFFICIENT_DISK (legacy ARDTT_ERROR|code=).

_disk_budget_py() {
  local d
  d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  printf '%s' "$d/disk-budget.py"
}

fs_dev() {
  local p="${1:-/}"
  mkdir -p "$p" 2>/dev/null || true
  stat -c '%d' "$p" 2>/dev/null || stat -f '%d' "$p" 2>/dev/null || echo 0
}

fs_mount() {
  local p="${1:-/}"
  df -P "$p" 2>/dev/null | awk 'NR==2 {print $6}'
}

disk_avail_bytes() {
  local p="${1:-/}"
  df -PB1 "$p" 2>/dev/null | awk 'NR==2 {print $4}'
}

disk_inodes_avail() {
  local p="${1:-/}"
  df -Pi "$p" 2>/dev/null | awk 'NR==2 {print $4}'
}

dir_bytes() {
  local p="$1" n
  [ -e "$p" ] || { echo 0; return 0; }
  n="$(du -sb "$p" 2>/dev/null | awk '{print $1}')"
  echo "${n:-0}"
}

# Bytes we may delete *before* deploy: stale incoming (not this package) and
# staging that is not this run. Never count current/previous/cache-in-use.
ardtt_predeploy_reclaimable_bytes() {
  python3 - "${INSTALL_DIR:-/opt/ardtt}" "${ARDTT_PACKAGE:-}" "${PKG_DIR:-}" <<'PY'
import os, sys
root, keep_pkg, keep_dir = sys.argv[1], sys.argv[2], sys.argv[3]
total = 0

def add(path):
    global total
    if not os.path.exists(path):
        return
    if os.path.isfile(path):
        try:
            total += os.path.getsize(path)
        except OSError:
            pass
        return
    for dp, dirs, files in os.walk(path, followlinks=False):
        for f in files:
            fp = os.path.join(dp, f)
            try:
                if not os.path.islink(fp):
                    total += os.path.getsize(fp)
            except OSError:
                pass

incoming = os.path.join(root, "incoming")
keep_abs = os.path.realpath(keep_pkg) if keep_pkg else ""
if os.path.isdir(incoming):
    for name in os.listdir(incoming):
        p = os.path.join(incoming, name)
        if keep_abs and os.path.realpath(p) == keep_abs:
            continue
        add(p)
staging = os.path.join(root, "staging")
if os.path.isdir(staging):
    st_abs = os.path.realpath(staging)
    kd = os.path.realpath(keep_dir) if keep_dir else ""
    if not kd or st_abs != kd:
        add(staging)
print(total)
PY
}

_layout_gz_bytes() {
  local layout="$1"
  [ -f "$layout" ] || { echo 0; return 0; }
  python3 - "$layout" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
print(sum(int((x or {}).get("gzSize") or 0) for x in (d.get("layers") or [])))
PY
}

_missing_layer_bytes() {
  local layout_dir="${1:-}"
  local layout="${layout_dir}/layout.json"
  [ -f "$layout" ] || { echo 0; return 0; }
  python3 - "$layout" "${ARDTT_IMAGE:-}" <<'PY'
import json, sys, subprocess
layout, image = sys.argv[1], sys.argv[2]
d=json.load(open(layout,encoding="utf-8"))
# Cached compressed blobs save download/cache bytes only. Docker still needs
# rawSize unless the image is already loaded in DockerRootDir.
if image:
    try:
        r = subprocess.run(["docker", "image", "inspect", image], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if r.returncode == 0:
            print(0)
            raise SystemExit(0)
    except OSError:
        pass
missing = 0
for layer in d.get("layers") or []:
    raw = int((layer or {}).get("rawSize") or 0)
    if raw <= 0:
        raw = int((layer or {}).get("gzSize") or 0)
    missing += max(0, raw)
print(max(0, missing))
PY
}

emit_insufficient_disk() {
  local fs="$1" req="$2" avail="$3" reclaim="$4" margin="$5" phase="$6" msg="$7" code="${8:-INSUFFICIENT_DISK}"
  if declare -F ardtt_emit >/dev/null && ardtt_emit error --code "$code" --message "$msg" --phase "$phase" \
      --filesystem "$fs" --required-bytes "$req" --available-bytes "$avail" \
      --reclaimable-bytes "$reclaim" --safety-margin-bytes "$margin"; then
    :
  else
    echo "ARDTT_ERROR|code=${code}|$msg" >&2
  fi
  exit 1
}

emit_disk_measurement_failed() {
  emit_insufficient_disk "${1:-/opt/ardtt}" "${2:-0}" "${3:-0}" "${4:-0}" "${5:-0}" "${6:-measure}" \
    "${7:-Не удалось измерить свободное место на диске.}" "DISK_MEASUREMENT_FAILED"
}

preflight_space_budget() {
  local install_dir="${INSTALL_DIR:-/opt/ardtt}"
  mkdir -p "$install_dir"
  local docker_root tmpdir
  docker_root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)"
  tmpdir="${TMPDIR:-/tmp}"
  local candidate download extract missing rollback reclaim
  candidate=0
  if [ -n "${PKG_DIR:-}" ] && [ -d "$PKG_DIR" ]; then
    candidate="$(dir_bytes "$PKG_DIR/docker-compose.yml")"
    candidate=$(( candidate + $(dir_bytes "$PKG_DIR/install.sh") + $(dir_bytes "$PKG_DIR/install-lib") + $(dir_bytes "$PKG_DIR/scripts") + $(dir_bytes "$PKG_DIR/ardttctl") ))
  fi
  [ "$candidate" -gt 1048576 ] 2>/dev/null || candidate=$(( 8 * 1024 * 1024 ))
  download=0
  if [ -n "${ARDTT_PACKAGE:-}" ] && [ -f "${ARDTT_PACKAGE}" ]; then
    download=0
  elif [ -n "${ARDTT_PACKAGE:-}" ]; then
    download="$(dir_bytes "${ARDTT_PACKAGE}" 2>/dev/null || echo 0)"
  fi
  extract=$(( 32 * 1024 * 1024 ))
  missing="$(_missing_layer_bytes "${PKG_DIR:-}/images")"
  if [ ! -f "${PKG_DIR:-}/images/layout.json" ] && [ -f "${PKG_DIR:-}/images/ardtt.tar" ]; then
    missing="$(dir_bytes "${PKG_DIR}/images/ardtt.tar")"
  fi
  # Pointer model: previous is a symlink, not a tree copy.
  rollback=0
  reclaim="$(ardtt_predeploy_reclaimable_bytes)"
  local min_bytes
  min_bytes=$(( ${MIN_DISK_MB:-1600} * 1024 * 1024 ))
  local payload out rc
  local avail_i avail_d avail_t inodes_i
  avail_i="$(disk_avail_bytes "$install_dir")"
  avail_d="$(disk_avail_bytes "$docker_root")"
  avail_t="$(disk_avail_bytes "$tmpdir")"
  inodes_i="$(disk_inodes_avail "$install_dir")"
  if [ -z "${avail_i:-}" ] || [ -z "${avail_d:-}" ]; then
    emit_disk_measurement_failed "$install_dir" "$min_bytes" 0 "$reclaim" 0 "preflight" \
      "Не удалось измерить свободное место (df) на ${install_dir} или DockerRootDir."
  fi
  missing="${missing:-0}"
  reclaim="${reclaim:-0}"
  candidate="${candidate:-0}"
  download="${download:-0}"
  payload="$(python3 - <<PY
import json
print(json.dumps({
  "candidateReleaseBytes": $candidate,
  "downloadOrIncomingBytes": $download,
  "extractionOrStagingOverhead": $extract,
  "missingDockerLayerBytes": $missing,
  "dockerImportBytesRaw": $missing,
  "rollbackReserveBytes": $rollback,
  "stateAndLogOverheadBytes": 64*1024*1024,
  "safelyReclaimableArdttBytes": $reclaim,
  "actuallyReclaimedBytes": 0,
  "minDiskBytes": $min_bytes,
  "installFsDev": "$(fs_dev "$install_dir")",
  "dockerFsDev": "$(fs_dev "$docker_root")",
  "tmpFsDev": "$(fs_dev "$tmpdir")",
  "installAvailableBytes": $avail_i,
  "dockerAvailableBytes": $avail_d,
  "tmpAvailableBytes": ${avail_t:-0},
  "installInodesAvailable": ${inodes_i:-0},
  "inodeNeed": 2000,
  "installPath": "$install_dir",
  "installMount": "$(fs_mount "$install_dir")",
  "dockerRoot": "$docker_root",
  "dockerMount": "$(fs_mount "$docker_root")",
  "tmpMount": "$(fs_mount "$tmpdir")",
  "phase": "preflight",
  "estimationMethod": "layout-rawSize-and-local-du",
}))
PY
)"
  set +e
  out="$(printf '%s' "$payload" | python3 "$(_disk_budget_py)" compute --json -)"
  rc=$?
  set -e
  if [ -z "$out" ]; then
    emit_disk_measurement_failed "$install_dir" "$min_bytes" "${avail_i:-0}" "$reclaim" 0 "preflight" \
      "Не удалось вычислить disk budget."
  fi
  echo "ARDTT_INFO|disk budget ${out}"
  if [ "$rc" -ne 0 ]; then
    local fs req avail reclaim_b margin phase msg code
    fs="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("filesystem",""))' "$out")"
    req="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("requiredBytes",0))' "$out")"
    avail="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("availableBytes",0))' "$out")"
    reclaim_b="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("reclaimableArdttBytes",0))' "$out")"
    margin="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("safetyMarginBytes",0))' "$out")"
    phase="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("phase",""))' "$out")"
    code="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("code") or "INSUFFICIENT_DISK")' "$out")"
    msg="Мало места на ${fs}: свободно ${avail} Б (нужно ≥${req} Б, запас ${margin} Б, можно освободить хвосты ARDTT до ${reclaim_b} Б — это оценка, не вычитается заранее). Повторите с ARDTT_DISK_CLEANUP=1 для хвостов ARDTT. Глобальная очистка сервера не выполняется."
    if [ "$code" = "DISK_MEASUREMENT_FAILED" ]; then
      emit_disk_measurement_failed "$fs" "$req" "$avail" "$reclaim_b" "$margin" "$phase" "$msg"
    fi
    if [ "${DISK_CLEANUP:-0}" = "1" ] || [ "${DISK_CLEANUP:-}" = "yes" ] || [ "${DISK_CLEANUP:-}" = "true" ]; then
      :
    else
      emit_insufficient_disk "$fs" "$req" "$avail" "$reclaim_b" "$margin" "$phase" "$msg" "$code"
    fi
    local before_avail="$avail_i"
    ardtt_disk_cleanup
    preflight_space_budget_recheck "$payload" "$before_avail"
  fi
}

preflight_space_budget_recheck() {
  local payload="$1" before_avail="${2:-0}" out rc
  payload="$(python3 - "$payload" "${INSTALL_DIR}" "$before_avail" <<'PY'
import json,sys,subprocess
d=json.loads(sys.argv[1])
install=sys.argv[2]
before=int(sys.argv[3] or 0)
def avail(p):
    try:
        out=subprocess.check_output(["df","-PB1",p], text=True).splitlines()[1].split()[3]
        return int(out)
    except Exception:
        return None
after=avail(install)
if after is None:
    d.pop("installAvailableBytes", None)
else:
    d["installAvailableBytes"]=after
    d["actuallyReclaimedBytes"]=max(0, after-before)
d["safelyReclaimableArdttBytes"]=0
print(json.dumps(d))
PY
)"
  set +e
  out="$(printf '%s' "$payload" | python3 "$(_disk_budget_py)" compute --json -)"
  rc=$?
  set -e
  if [ -z "$out" ]; then
    emit_disk_measurement_failed "${INSTALL_DIR}" 0 0 0 0 "preflight" "Не удалось пересчитать disk budget после очистки."
  fi
  echo "ARDTT_INFO|disk budget after cleanup ${out}"
  if [ "$rc" -ne 0 ]; then
    local fs req avail reclaim_b margin phase msg code
    fs="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("filesystem",""))' "$out")"
    req="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("requiredBytes",0))' "$out")"
    avail="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("availableBytes",0))' "$out")"
    reclaim_b="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("actuallyReclaimedBytes",0))' "$out")"
    margin="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("safetyMarginBytes",0))' "$out")"
    phase="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("phase",""))' "$out")"
    code="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("code") or "INSUFFICIENT_DISK")' "$out")"
    msg="Мало места на ${fs}: свободно ${avail} Б (нужно ≥${req} Б) даже после очистки хвостов ARDTT (фактически освобождено ${reclaim_b} Б). Освободите место вручную."
    emit_insufficient_disk "$fs" "$req" "$avail" "$reclaim_b" "$margin" "$phase" "$msg" "$code"
  fi
}
