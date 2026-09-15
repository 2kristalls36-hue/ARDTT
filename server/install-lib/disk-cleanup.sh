# shellcheck shell=bash
# Opt-in reclaim of ARDTT-owned leftovers only. Never wipe foreign Docker,
# host logs, kernel packages, or /opt/ardtt/data.

_cleanup_lib_dir() {
  local d
  d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  printf '%s' "$d"
}

ardtt_safe_rm_rf() {
  local root="${INSTALL_DIR:-/opt/ardtt}" p
  [ -n "$root" ] || return 1
  python3 "$(_cleanup_lib_dir)/safe-rm.py" "$root" "$@"
}

# Drop failed-install leftovers under INSTALL_DIR without touching the active
# package/staging of this run. Never unlink flock files (stable inode).
ardtt_cleanup_ardtt_leftovers() {
  local root="${INSTALL_DIR:-/opt/ardtt}"
  local keep_pkg="${ARDTT_PACKAGE:-}"
  local keep_dir="${PKG_DIR:-${ARDTT_PKG_DIR:-}}"
  local f

  mkdir -p "${root}/incoming" 2>/dev/null || true
  shopt -s nullglob
  for f in "${root}/incoming/"*.partial; do
    [ -f "$f" ] || continue
    ardtt_safe_rm_rf "$f" 2>/dev/null || rm -f "$f" 2>/dev/null || true
  done
  for f in "${root}/incoming/"*.tar.gz; do
    [ -f "$f" ] || continue
    if [ -n "$keep_pkg" ] && [ "$f" -ef "$keep_pkg" ]; then
      continue
    fi
    if [ -n "$keep_pkg" ] && [ "$(readlink -f "$f" 2>/dev/null || echo "$f")" = "$(readlink -f "$keep_pkg" 2>/dev/null || echo "$keep_pkg")" ]; then
      continue
    fi
    ardtt_safe_rm_rf "$f" 2>/dev/null || rm -f "$f" 2>/dev/null || true
  done
  shopt -u nullglob

  if [ -d "${root}/staging" ]; then
    if [ -z "$keep_dir" ] || [ "$(readlink -f "${root}/staging" 2>/dev/null || echo "${root}/staging")" != "$(readlink -f "$keep_dir" 2>/dev/null || echo "$keep_dir")" ]; then
      ardtt_safe_rm_rf "${root}/staging" 2>/dev/null || true
    fi
  fi
}

_pointer_real() {
  local name="$1"
  if [ -L "${INSTALL_DIR}/${name}" ] || [ -d "${INSTALL_DIR}/${name}" ]; then
    readlink -f "${INSTALL_DIR}/${name}" 2>/dev/null || true
  fi
}

# Protected realpaths: current, previous, and an in-flight staging release.
ardtt_protected_release_paths() {
  local p staged="${1:-}"
  for p in "$(_pointer_real current)" "$(_pointer_real previous)" "$(_pointer_real current-release)"; do
    [ -n "$p" ] || continue
    printf '%s\n' "$p"
  done
  if [ -n "$staged" ]; then
    readlink -f "$staged" 2>/dev/null || printf '%s\n' "$staged"
  fi
  if [ -n "${DEPLOY_VERSION:-}" ]; then
    local want="${INSTALL_DIR}/releases/${DEPLOY_VERSION}"
    [ -d "$want" ] && readlink -f "$want"
    [ -d "${want}.new" ] && readlink -f "${want}.new"
  fi
}

_ardtt_gc_plan() {
  python3 - "$INSTALL_DIR" "${ARDTT_GC_DRY_RUN:-0}" "${ARDTT_ALLOW_RELEASE_GC:-0}" <<'PY'
import os, sys, json
install, dry, allow = sys.argv[1], sys.argv[2] == "1", sys.argv[3] == "1"
releases = os.path.join(install, "releases")
report = {"examined": 0, "freed": 0, "skipped": 0, "reasons": [], "bytes": 0, "dryRun": dry, "allowed": allow}

def real(p):
    try:
        return os.path.realpath(p)
    except OSError:
        return ""

protected = set()
for name in ("current", "previous", "current-release"):
    p = os.path.join(install, name)
    if os.path.lexists(p):
        r = real(p)
        if r:
            protected.add(r)

extra = os.environ.get("ARDTT_GC_KEEP", "")
for line in extra.splitlines():
    line = line.strip()
    if line:
        protected.add(real(line) or line)

def du(path):
    total = 0
    for root, dirs, files in os.walk(path, followlinks=False):
        for f in files:
            fp = os.path.join(root, f)
            try:
                if os.path.islink(fp):
                    continue
                total += os.path.getsize(fp)
            except OSError:
                pass
    return total

if not os.path.isdir(releases):
    json.dump(report, sys.stdout)
    sys.exit(0)

for ent in sorted(os.listdir(releases)):
    path = os.path.join(releases, ent)
    report["examined"] += 1
    r = real(path)
    if r in protected:
        report["skipped"] += 1
        report["reasons"].append({"id": ent, "reason": "protected_pointer"})
        continue
    if not os.path.isdir(path) or os.path.islink(path):
        report["skipped"] += 1
        report["reasons"].append({"id": ent, "reason": "not_a_release_dir"})
        continue
    if not allow:
        report["skipped"] += 1
        report["reasons"].append({"id": ent, "reason": "before_health_gate"})
        continue
    size = du(path)
    report["reasons"].append({"id": ent, "reason": "obsolete", "bytes": size})
    if dry:
        report["freed"] += 1
        report["bytes"] += size
        continue
    # actual delete is done by bash via safe-rm
    print(f"DELETE\t{path}\t{size}", file=sys.stderr)
    report["freed"] += 1
    report["bytes"] += size
json.dump(report, sys.stdout)
PY
}

# GC obsolete releases. No-op until ARDTT_ALLOW_RELEASE_GC=1 (after health).
# Does not docker prune. Does not touch data/, current, or previous.
ardtt_gc_releases() {
  local root="${INSTALL_DIR:-/opt/ardtt}"
  local keep="" r
  keep="$(ardtt_protected_release_paths "${1:-}")"
  local report del path size
  ARDTT_GC_KEEP="$keep" report="$(_ardtt_gc_plan 2>"${TMPDIR:-/tmp}/ardtt-gc-del.$$")" || true
  if [ -n "${report:-}" ]; then
    echo "ARDTT_INFO|gc releases ${report}"
  fi
  if [ "${ARDTT_ALLOW_RELEASE_GC:-0}" != "1" ]; then
    rm -f "${TMPDIR:-/tmp}/ardtt-gc-del.$$" 2>/dev/null || true
    return 0
  fi
  if [ "${ARDTT_GC_DRY_RUN:-0}" = "1" ]; then
    rm -f "${TMPDIR:-/tmp}/ardtt-gc-del.$$" 2>/dev/null || true
    return 0
  fi
  if [ -f "${TMPDIR:-/tmp}/ardtt-gc-del.$$" ]; then
    while IFS=$'\t' read -r del path size; do
      [ "$del" = "DELETE" ] || continue
      [ -n "$path" ] || continue
      ardtt_safe_rm_rf "$path" || echo "ARDTT_WARN|gc skip ${path}"
    done < "${TMPDIR:-/tmp}/ardtt-gc-del.$$"
    rm -f "${TMPDIR:-/tmp}/ardtt-gc-del.$$"
  fi
}

ardtt_gc_logs() {
  local root="${INSTALL_DIR:-/opt/ardtt}/logs"
  [ -d "$root" ] || return 0
  # Retention: gzip'd / rotated files older than 14 days under our logs/ only.
  find "$root" -type f \( -name '*.gz' -o -name '*.old' \) -mtime +14 -print0 2>/dev/null \
    | while IFS= read -r -d '' f; do
        ardtt_safe_rm_rf "$f" 2>/dev/null || true
      done
}

ardtt_disk_cleanup_hint() {
  echo "ARDTT_INFO|рекомендация (не выполняется автоматически): при нехватке места на хосте можно вручную сократить логи Docker, apt-кэш и journal. ARDTT чистит только свои incoming/staging/releases/cache/logs."
}

# Safe opt-in reclaim. Caller must set ARDTT_DISK_CLEANUP=1 intentionally.
ardtt_disk_cleanup() {
  local before after
  before="$(disk_avail_mb "${INSTALL_DIR:-/}")"
  echo "ARDTT_INFO|очистка диска ARDTT: incoming/staging-хвосты; свободно было ${before:-?} МБ"
  ardtt_cleanup_ardtt_leftovers
  ardtt_disk_cleanup_hint
  after="$(disk_avail_mb "${INSTALL_DIR:-/}")"
  echo "ARDTT_INFO|очистка ARDTT завершена: свободно ${after:-?} МБ (было ${before:-?} МБ)"
}
