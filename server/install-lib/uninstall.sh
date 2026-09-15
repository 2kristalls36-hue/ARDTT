# shellcheck shell=bash
# Remove only this ARDTT instance. Docker Engine (including a bundled copy
# outside /opt/ardtt), foreign containers, bridges, and firewall policy stay.
# Default keeps data/; ARDTT_PURGE_DATA=1 deletes it.

# shellcheck disable=SC1091
_switch_lib="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/switch.sh"
if [ -f "$_switch_lib" ]; then
  # shellcheck disable=SC1090
  . "$_switch_lib"
fi

uninstall_this_instance() {
  local purge="${ARDTT_PURGE_DATA:-0}"
  prog 0.10 "Поиск экземпляра ARDTT"
  if load_instance || load_pending_instance || load_instance_from_env; then
    prog 0.30 "Остановка контейнера ${ARDTT_CONTAINER_NAME}"
    local current="${INSTALL_DIR}/current"
    [ -d "$current" ] || current="${INSTALL_DIR}/stack"
    stop_owned_stack "$current"
    if [ -n "${ARDTT_CONTAINER_NAME:-}" ]; then
      if docker inspect "$ARDTT_CONTAINER_NAME" >/dev/null 2>&1; then
        if object_owned_by_us container "$ARDTT_CONTAINER_NAME" || container_looks_like_legacy_ardtt "$ARDTT_CONTAINER_NAME"; then
          docker rm -f "$ARDTT_CONTAINER_NAME" >/dev/null 2>&1 || true
        else
          echo "ARDTT_WARN|контейнер ${ARDTT_CONTAINER_NAME} не наш — не удаляем"
        fi
      fi
    fi
    local net id
    for id in $(list_owned_networks); do
      docker network rm "$id" >/dev/null 2>&1 || true
    done
    if [ -n "${PREV_IMAGE_ID:-}" ]; then
      echo "ARDTT_INFO|образ ${PREV_IMAGE_ID} оставляем (другие экземпляры / откат)"
    fi
  elif [ -d "${INSTALL_DIR}/stack" ] || docker inspect ardtt >/dev/null 2>&1; then
    INSTANCE_ID="${INSTANCE_ID:-unknown}"
    plan_legacy_resources
    stop_legacy_owned
  else
    echo "ARDTT_INFO|экземпляр ARDTT не найден — нечего снимать"
    echo "ARDTT_UNINSTALLED"
    return 0
  fi
  prog 0.70 "Каталоги установки"
  rm -f "${INSTALL_DIR}/incoming/"*.partial 2>/dev/null || true
  rm -rf "${INSTALL_DIR}/staging" "${INSTALL_DIR}/releases" "${INSTALL_DIR}/current" \
    "${INSTALL_DIR}/current-release" "${INSTALL_DIR}/previous" "${INSTALL_DIR}/stack" \
    "${INSTALL_DIR}/stack.old" "${INSTALL_DIR}/stack.staging" "${INSTALL_DIR}/bin" \
    "${INSTALL_DIR}/cache" "${INSTALL_DIR}/state" 2>/dev/null || true
  rm -f "${INSTALL_DIR}/install.lock" "${INSTALL_DIR}/fetch.lock" "${INSTALL_DIR}/DEPLOY_VERSION" 2>/dev/null || true
  if [ "$purge" = "1" ]; then
    prog 0.85 "Удаление данных (ARDTT_PURGE_DATA=1)"
    rm -rf "${INSTALL_DIR}/data" "${INSTALL_DIR}/logs" "${INSTALL_DIR}/backups" \
      "${INSTALL_DIR}/instance.json" "${INSTALL_DIR}/incoming" "${INSTALL_DIR}" \
      /opt/nonamevpn 2>/dev/null || true
  else
    echo "ARDTT_INFO|данные оставлены в ${INSTALL_DIR}/data (ARDTT_PURGE_DATA=1 чтобы стереть)"
    rm -f "${INSTALL_DIR}/instance.json"
  fi
  prog 1.00 "Готово"
  echo "ARDTT_UNINSTALLED"
}

# Copy previous/ onto current/ (directory tree, not a symlink into releases/)
# and start it. Does not emit ARDTT_DONE — a failed update must still return
# ARDTT_ERROR even after restoring the old stack.
# Returns: 0 restored (and ready if wait_readiness exists), 1 no previous,
# 2 rollback attempted and failed (compose or readiness).
# docker-compose.yml is a file: [ -d that-path ] is always false.
restore_previous_release() {
  local prev="${INSTALL_DIR}/previous"
  [ -f "$prev/docker-compose.yml" ] || return 1
  echo "ARDTT_WARN|откат на предыдущую версию"
  if declare -F ardtt_state_set >/dev/null; then
    ardtt_state_set --phase rollback --previous "$(env_file_val "$prev/.env" ARDTT_DEPLOY_VERSION)"
  fi
  if [ -d "${INSTALL_DIR}/current" ] || [ -L "${INSTALL_DIR}/current" ]; then
    (
      cd "${INSTALL_DIR}/current"
      compose_up_cmd down --remove-orphans || true
    ) >/dev/null 2>&1 || true
  fi
  if declare -F restore_current_from_previous_tree >/dev/null; then
    restore_current_from_previous_tree || return 2
  else
    rm -rf "${INSTALL_DIR}/current"
    mkdir -p "${INSTALL_DIR}/current"
    cp -a "$prev"/. "${INSTALL_DIR}/current"/
  fi
  local prev_ver
  prev_ver="$(env_file_val "$prev/.env" ARDTT_DEPLOY_VERSION)"
  if [ -n "$prev_ver" ]; then
    mkdir -p "${INSTALL_DIR}/data"
    printf '%s\n' "$prev_ver" > "${INSTALL_DIR}/data/DEPLOY_VERSION"
    printf '%s\n' "$prev_ver" > "${INSTALL_DIR}/DEPLOY_VERSION"
  fi
  if [ -f "$prev/root.env" ]; then
    cp -a "$prev/root.env" "${INSTALL_DIR}/.env"
  elif [ -f "$prev/.env" ]; then
    cp -a "$prev/.env" "${INSTALL_DIR}/.env"
  fi
  if [ -f "$prev/instance.json" ]; then
    cp -a "$prev/instance.json" "${INSTALL_DIR}/instance.json"
    chmod 600 "${INSTALL_DIR}/instance.json" 2>/dev/null || true
  fi
  rm -f "${INSTALL_DIR}/instance.pending.json"
  if ! (
    cd "${INSTALL_DIR}/current"
    compose_up_cmd up -d --no-build --pull never
  ); then
    _rollback_failed "compose_up_failed_during_rollback"
    return 2
  fi
  if declare -F wait_readiness >/dev/null; then
    if ! wait_readiness; then
      _rollback_failed "readiness_timeout_during_rollback"
      return 2
    fi
  fi
  if declare -F ardtt_state_set >/dev/null; then
    ardtt_state_set --phase commit --current "$prev_ver" --desired "$prev_ver"
  fi
  return 0
}

_rollback_failed() {
  local msg="$1"
  if declare -F ardtt_state_set >/dev/null; then
    ardtt_state_set --phase rollback_failed --code ROLLBACK_FAILED --error "$msg"
  fi
  if declare -F ardtt_emit >/dev/null; then
    ardtt_emit error --code ROLLBACK_FAILED --message "$msg" || \
      echo "ARDTT_ERROR|code=ROLLBACK_FAILED|$msg" >&2
  else
    echo "ARDTT_ERROR|code=ROLLBACK_FAILED|$msg" >&2
  fi
}

rollback_previous() {
  local prev="${INSTALL_DIR}/previous"
  [ -f "$prev/docker-compose.yml" ] || die "Нет предыдущей версии для отката"
  local img
  img="$(env_file_val "$prev/.env" ARDTT_IMAGE)"
  [ -n "$img" ] || die "В previous/.env нет ARDTT_IMAGE"
  docker image inspect "$img" >/dev/null 2>&1 || die "Предыдущий образ $img отсутствует"
  local live="${INSTALL_DIR}/current"
  stop_owned_stack "$live"
  if [ -d "$live" ]; then
    rm -rf "${INSTALL_DIR}/failed"
    mv "$live" "${INSTALL_DIR}/failed" || true
  fi
  restore_previous_release || die --code ROLLBACK_FAILED "Откат: compose up / readiness не удались"
  if declare -F emit_done >/dev/null; then
    emit_done "rollback=1|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$(cat "${INSTALL_DIR}/DEPLOY_VERSION")"
  else
    echo "ARDTT_DONE|rollback=1|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$(cat "${INSTALL_DIR}/DEPLOY_VERSION")"
  fi
}
