# shellcheck shell=bash
# current and previous are atomic pointers (symlinks) into releases/<id>.
# Snapshot and rollback never copy a release tree.

_switch_py() {
  local d
  d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  printf '%s' "$d/atomic-pointer.py"
}

_switch_py_run() {
  python3 "$(_switch_py)" "$@"
}

current_real_path() {
  if [ -L "${INSTALL_DIR}/current" ] || [ -d "${INSTALL_DIR}/current" ]; then
    readlink -f "${INSTALL_DIR}/current" 2>/dev/null || printf '%s' "${INSTALL_DIR}/current"
  fi
}

ensure_release_pointers() {
  mkdir -p "${INSTALL_DIR}/releases" "${INSTALL_DIR}/state"
  _switch_py_run recover "$INSTALL_DIR" || true
}

new_deployment_id() {
  python3 -c 'import os,time; print("d{:x}-{}".format(int(time.time()), os.urandom(6).hex()))'
}

release_is_pointer_target() {
  local path="$1" abs live name
  abs="$(readlink -f "$path" 2>/dev/null || echo "$path")"
  [ -n "$abs" ] || return 1
  for name in current previous current-release; do
    live="$(readlink -f "${INSTALL_DIR}/${name}" 2>/dev/null || true)"
    if [ -n "$live" ] && [ "$live" = "$abs" ]; then
      return 0
    fi
  done
  return 1
}

# Unique releases/<deploymentId> for every attempt. Version is metadata, not the id.
release_staging_path() {
  local ver="${1:-}"
  local id path
  mkdir -p "${INSTALL_DIR}/releases"
  id="$(new_deployment_id)"
  path="${INSTALL_DIR}/releases/${id}"
  while [ -e "$path" ] || release_is_pointer_target "$path"; do
    id="$(new_deployment_id)"
    path="${INSTALL_DIR}/releases/${id}"
  done
  printf '%s' "$path"
}

# Keep a restaged tree as its own immutable id (.new). Never swap over a live
# pointer target and never drop current while doing so.
commit_release_staging() {
  local staged="$1"
  printf '%s' "$staged"
}

# Point previous at the live current release. Does not copy the tree.
snapshot_current_to_previous() {
  ensure_release_pointers
  local src=""
  if [ -L "${INSTALL_DIR}/current" ]; then
    src="$(readlink -f "${INSTALL_DIR}/current" 2>/dev/null || true)"
    [ -n "$src" ] && [ -d "$src" ] || return 0
  elif [ -d "${INSTALL_DIR}/current" ]; then
    _switch_py_run migrate "$INSTALL_DIR" current || return 1
    src="$(readlink -f "${INSTALL_DIR}/current" 2>/dev/null || true)"
  else
    return 0
  fi
  [ -f "$src/docker-compose.yml" ] || return 0
  # Same deployVersion is normal for restage. Skip only if current already
  # points at this attempt's own candidate (snapshot invoked after activate).
  local cur_id=""
  cur_id="$(basename "$src")"
  if [ -n "${DEPLOYMENT_ID:-}" ] && [ -n "$cur_id" ] && [ "$cur_id" = "$DEPLOYMENT_ID" ]; then
    if [ -f "${INSTALL_DIR}/previous/docker-compose.yml" ]; then
      if declare -F snapshot_confirmed_metadata >/dev/null; then
        snapshot_confirmed_metadata "${INSTALL_DIR}/state/rollback"
      fi
      return 0
    fi
  fi
  local rel
  rel="$(_switch_py_run relpath "$INSTALL_DIR" "$src")" || return 1
  if declare -F snapshot_confirmed_metadata >/dev/null; then
    snapshot_confirmed_metadata "${INSTALL_DIR}/state/rollback"
  fi
  _switch_py_run replace "$INSTALL_DIR" previous "$rel"
}

activate_release_tree() {
  local release="$1" rel
  [ -d "$release" ] || return 1
  [ -f "$release/docker-compose.yml" ] || return 1
  ensure_release_pointers
  rel="$(_switch_py_run relpath "$INSTALL_DIR" "$release")" || return 1
  _switch_py_run replace "$INSTALL_DIR" current "$rel" || return 1
  _switch_py_run replace "$INSTALL_DIR" current-release "$rel" 2>/dev/null || true
}

# Retarget current at the previous release. Never copies the tree.
restore_current_from_previous_tree() {
  local prev="${INSTALL_DIR}/previous" rel src
  ensure_release_pointers
  if [ -d "$prev" ] && [ ! -L "$prev" ]; then
    _switch_py_run migrate "$INSTALL_DIR" previous || return 1
  fi
  [ -f "$prev/docker-compose.yml" ] || return 1
  src="$(readlink -f "$prev" 2>/dev/null || true)"
  [ -n "$src" ] && [ -d "$src" ] || return 1
  rel="$(_switch_py_run relpath "$INSTALL_DIR" "$src")" || return 1
  _switch_py_run replace "$INSTALL_DIR" current "$rel"
}
