# shellcheck shell=bash
# Atomic-ish current/ as a symlink to releases/<ver>. Snapshot previous/
# by copying the real tree (never the symlink itself).

current_real_path() {
  if [ -L "${INSTALL_DIR}/current" ] || [ -d "${INSTALL_DIR}/current" ]; then
    readlink -f "${INSTALL_DIR}/current" 2>/dev/null || printf '%s' "${INSTALL_DIR}/current"
  fi
}

# If live current already points at releases/<ver>, stage into .new so we
# do not rm -rf the running tree while compose is still up.
release_staging_path() {
  local ver="$1"
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

commit_release_staging() {
  local staged="$1" ver="$2"
  local want="${INSTALL_DIR}/releases/${ver}"
  if [ "$staged" = "$want" ]; then
    printf '%s' "$want"
    return 0
  fi
  drop_current_pointer
  rm -rf "$want"
  mv "$staged" "$want"
  printf '%s' "$want"
}

# Remove current as a pointer. Never follow a symlink into releases/.
drop_current_pointer() {
  local current="${INSTALL_DIR}/current"
  if [ -L "$current" ]; then
    rm -f "$current"
  elif [ -e "$current" ]; then
    rm -rf "$current"
  fi
}

# Copy the live tree into previous/ as a real directory (dereference).
snapshot_current_to_previous() {
  local src=""
  if [ -L "${INSTALL_DIR}/current" ]; then
    src="$(readlink -f "${INSTALL_DIR}/current" 2>/dev/null || true)"
    [ -n "$src" ] && [ -d "$src" ] || return 0
  elif [ -d "${INSTALL_DIR}/current" ]; then
    src="${INSTALL_DIR}/current"
  else
    return 0
  fi
  [ -f "$src/.env" ] || [ -f "$src/docker-compose.yml" ] || return 0
  rm -rf "${INSTALL_DIR}/previous"
  mkdir -p "${INSTALL_DIR}/previous"
  cp -a "$src"/. "${INSTALL_DIR}/previous"/
  if declare -F snapshot_confirmed_metadata >/dev/null; then
    snapshot_confirmed_metadata "${INSTALL_DIR}/previous"
  fi
}

activate_release_tree() {
  local release="$1" abs
  [ -d "$release" ] || return 1
  abs="$(cd "$release" && pwd)"
  drop_current_pointer
  ln -sfn "$abs" "${INSTALL_DIR}/current"
  ln -sfn "$abs" "${INSTALL_DIR}/current-release" 2>/dev/null || true
}

restore_current_from_previous_tree() {
  local prev="${INSTALL_DIR}/previous"
  [ -f "$prev/docker-compose.yml" ] || return 1
  drop_current_pointer
  mkdir -p "${INSTALL_DIR}/current"
  cp -a "$prev"/. "${INSTALL_DIR}/current"/
}
