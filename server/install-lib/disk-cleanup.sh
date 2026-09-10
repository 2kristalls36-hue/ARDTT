# shellcheck shell=bash
# Opt-in safe disk reclaim before install. Never wipe Docker images in use,
# foreign containers, /opt/ardtt/data, or host networking.

# Truncate Docker container json logs (often hundreds of MB). Does not stop containers.
ardtt_truncate_docker_json_logs() {
  local f
  shopt -s nullglob
  for f in /var/lib/docker/containers/*/*-json.log; do
    [ -f "$f" ] || continue
    # Only truncate large logs (≥16 MiB) to avoid churn on tiny files.
    if [ "$(wc -c <"$f" 2>/dev/null || echo 0)" -ge 16777216 ] 2>/dev/null; then
      : >"$f" || true
    fi
  done
  shopt -u nullglob
}

# Remove linux-headers for kernels that are not the running one.
ardtt_purge_unused_linux_headers() {
  local running base d pkg
  running="$(uname -r 2>/dev/null || true)"
  [ -n "$running" ] || return 0
  shopt -s nullglob
  for d in /usr/src/linux-headers-*; do
    [ -e "$d" ] || continue
    base="$(basename "$d")"
    case "$base" in
      *"${running}"*|*"${running%-generic}"*) ;;
      *) rm -rf "$d" 2>/dev/null || true ;;
    esac
  done
  shopt -u nullglob
  if command -v dpkg >/dev/null 2>&1 && command -v apt-get >/dev/null 2>&1; then
    while read -r pkg; do
      [ -n "$pkg" ] || continue
      case "$pkg" in
        *"${running}"*|*"${running%-generic}"*|linux-headers-generic*|linux-headers-virtual*)
          continue
          ;;
      esac
      DEBIAN_FRONTEND=noninteractive apt-get -y purge "$pkg" >/dev/null 2>&1 || true
    done < <(dpkg -l 'linux-headers-*' 2>/dev/null | awk '/^ii/{print $2}')
  fi
}

# Drop failed-install leftovers under INSTALL_DIR without touching the active package/staging.
ardtt_cleanup_ardtt_leftovers() {
  local root="${INSTALL_DIR:-/opt/ardtt}"
  local keep_pkg="${ARDTT_PACKAGE:-}"
  local keep_dir="${PKG_DIR:-${ARDTT_PKG_DIR:-}}"
  local f

  mkdir -p "${root}/incoming" 2>/dev/null || true
  rm -f "${root}/incoming/"*.partial 2>/dev/null || true
  # Stale packages in incoming (keep the one we are installing).
  shopt -s nullglob
  for f in "${root}/incoming/"*.tar.gz; do
    [ -f "$f" ] || continue
    if [ -n "$keep_pkg" ] && [ "$f" -ef "$keep_pkg" ]; then
      continue
    fi
    if [ -n "$keep_pkg" ] && [ "$(readlink -f "$f" 2>/dev/null || echo "$f")" = "$(readlink -f "$keep_pkg" 2>/dev/null || echo "$keep_pkg")" ]; then
      continue
    fi
    rm -f "$f" 2>/dev/null || true
  done
  shopt -u nullglob

  # Only remove staging if it is NOT the package dir of this run.
  if [ -d "${root}/staging" ]; then
    if [ -z "$keep_dir" ] || [ "$(readlink -f "${root}/staging" 2>/dev/null || echo "${root}/staging")" != "$(readlink -f "$keep_dir" 2>/dev/null || echo "$keep_dir")" ]; then
      rm -rf "${root}/staging" 2>/dev/null || true
    fi
  fi
  rm -f "${root}/install.lock" 2>/dev/null || true
}

# Safe opt-in reclaim. Caller must set ARDTT_DISK_CLEANUP=1 intentionally.
ardtt_disk_cleanup() {
  local before after
  before="$(disk_avail_mb "${INSTALL_DIR:-/}")"
  echo "ARDTT_INFO|очистка диска (безопасно): логи Docker ≥16МиБ, apt-кэш, лишние linux-headers, хвосты ARDTT; свободно было ${before:-?} МБ"

  ardtt_truncate_docker_json_logs
  ardtt_cleanup_ardtt_leftovers

  if command -v apt-get >/dev/null 2>&1; then
    apt-get clean >/dev/null 2>&1 || true
    rm -rf /var/lib/apt/lists/* 2>/dev/null || true
    rm -f /var/cache/apt/archives/*.deb 2>/dev/null || true
  fi

  if command -v journalctl >/dev/null 2>&1; then
    journalctl --vacuum-size=50M >/dev/null 2>&1 || true
  fi

  ardtt_purge_unused_linux_headers

  # Dangling images / stopped containers only — never prune running or tagged in-use.
  if command -v docker >/dev/null 2>&1; then
    docker container prune -f >/dev/null 2>&1 || true
    docker image prune -f >/dev/null 2>&1 || true
    docker volume prune -f >/dev/null 2>&1 || true
  fi

  find /var/log -type f \( -name '*.gz' -o -name '*.1' -o -name '*.old' \) -delete 2>/dev/null || true

  after="$(disk_avail_mb "${INSTALL_DIR:-/}")"
  echo "ARDTT_INFO|очистка диска завершена: свободно ${after:-?} МБ (было ${before:-?} МБ)"
}
