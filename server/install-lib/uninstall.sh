# shellcheck shell=bash
# Remove only this ARDTT instance. Docker Engine, foreign containers, bridges,
# and firewall policy stay. Default keeps data/; ARDTT_PURGE_DATA=1 deletes it.

uninstall_this_instance() {
  local purge="${ARDTT_PURGE_DATA:-0}"
  prog 0.10 "Поиск экземпляра ARDTT"
  if load_instance || load_instance_from_env; then
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
    "${INSTALL_DIR}/previous" "${INSTALL_DIR}/stack" "${INSTALL_DIR}/stack.old" \
    "${INSTALL_DIR}/stack.staging" "${INSTALL_DIR}/bin" 2>/dev/null || true
  rm -f "${INSTALL_DIR}/install.lock" "${INSTALL_DIR}/DEPLOY_VERSION" 2>/dev/null || true
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

# Copy previous/ onto current/ and start it. Does not emit ARDTT_DONE —
# a failed update must still return ARDTT_ERROR even after restoring the old stack.
# docker-compose.yml is a file: [ -d that-path ] is always false.
restore_previous_release() {
  local prev="${INSTALL_DIR}/previous"
  [ -f "$prev/docker-compose.yml" ] || return 1
  echo "ARDTT_WARN|откат на предыдущую версию"
  if [ -d "${INSTALL_DIR}/current" ]; then
    (
      cd "${INSTALL_DIR}/current"
      compose_up_cmd down --remove-orphans || true
    ) >/dev/null 2>&1 || true
  fi
  rm -rf "${INSTALL_DIR}/current"
  mkdir -p "${INSTALL_DIR}/current"
  cp -a "$prev"/. "${INSTALL_DIR}/current"/
  local prev_ver
  prev_ver="$(env_file_val "$prev/.env" ARDTT_DEPLOY_VERSION)"
  if [ -n "$prev_ver" ]; then
    mkdir -p "${INSTALL_DIR}/data"
    printf '%s\n' "$prev_ver" > "${INSTALL_DIR}/data/DEPLOY_VERSION"
    printf '%s\n' "$prev_ver" > "${INSTALL_DIR}/DEPLOY_VERSION"
  fi
  (
    cd "${INSTALL_DIR}/current"
    compose_up_cmd up -d --no-build --pull never
  ) || return 1
  return 0
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
  restore_previous_release || die "Откат: compose up не удался"
  wait_readiness || die "Откат: readiness не прошла"
  echo "ARDTT_DONE|rollback=1|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$(cat "${INSTALL_DIR}/DEPLOY_VERSION")"
}
