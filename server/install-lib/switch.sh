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

# If live current already points at releases/<ver>, stage into .new so we
# do not mutate the running immutable tree.
release_staging_path() {
  local ver="$1"
  case "$ver" in
    ''|*/*|*..*) return 1 ;;
  esac
  printf '%s' "$ver" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$' || return 1
  local want="${INSTALL_DIR}/releases/${ver}"
  local live want_abs
  live="$(current_real_path)"
  want_abs="$(readlink -f "$want" 2>/dev/null || echo "$want")"
  if [ -n "$live" ] && [ "$live" = "$want_abs" ]; then
    printf '%s' "${want}.new"
    return 0
  fi
  printf '%s' "$want"
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
  local cur_ver=""
  cur_ver="$(env_file_val "$src/.env" ARDTT_DEPLOY_VERSION)"
  # Retry after the candidate pointer was already switched: do not retarget
  # previous onto the candidate and lose the last good rollback release.
  if [ -n "${DEPLOY_VERSION:-}" ] && [ -n "$cur_ver" ] && [ "$cur_ver" = "$DEPLOY_VERSION" ]; then
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
