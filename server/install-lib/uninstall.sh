# shellcheck shell=bash
# Remove only this ARDTT instance. Docker Engine, foreign containers, bridges,
# and firewall policy stay. Default keeps data/; ARDTT_PURGE_DATA=1 deletes it.

uninstall_this_instance() {
  local purge="${ARDTT_PURGE_DATA:-0}"
  prog 0.10 "Поиск экземпляра ARDTT"
  if ! load_instance; then
    if [ -d "${INSTALL_DIR}/stack" ] || docker inspect ardtt >/dev/null 2>&1; then
      INSTANCE_ID="${INSTANCE_ID:-unknown}"
      plan_legacy_resources
      stop_legacy_owned
    else
      echo "ARDTT_INFO|экземпляр ARDTT не найден — нечего снимать"
      echo "ARDTT_UNINSTALLED"
      return 0
    fi
  else
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
  mkdir -p "${INSTALL_DIR}/current"
  cp -a "$prev"/. "${INSTALL_DIR}/current"/
  (
    cd "${INSTALL_DIR}/current"
    compose_up_cmd up -d --no-build --pull never
  ) || die "Откат: compose up не удался"
  wait_readiness || die "Откат: readiness не прошла"
  printf '%s\n' "$(env_file_val "$prev/.env" ARDTT_DEPLOY_VERSION)" > "${INSTALL_DIR}/DEPLOY_VERSION"
  echo "ARDTT_DONE|rollback=1|install_dir=$INSTALL_DIR|public_host=$PUBLIC_HOST|deploy_version=$(cat "${INSTALL_DIR}/DEPLOY_VERSION")"
}
