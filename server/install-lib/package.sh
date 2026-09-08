# shellcheck shell=bash
# Package verification. Trust is the outer SHA-256 vs GitHub/app metadata,
# not checksums stored inside the same archive.

PACKAGE_FORMAT="ardtt-server-v1"

safe_extract_package() {
  local archive="$1" dest="$2"
  if command -v python3 >/dev/null 2>&1 && [ -f "${SAFE_EXTRACT_PY:-}" ]; then
    python3 "$SAFE_EXTRACT_PY" "$archive" "$dest"
    return $?
  fi
  if command -v python3 >/dev/null 2>&1 && [ -f "$INSTALL_LIB_DIR/../..//scripts/safe-extract-package.py" ]; then
    python3 "$INSTALL_LIB_DIR/../../scripts/safe-extract-package.py" "$archive" "$dest"
    return $?
  fi
  # Bash fallback: refuse unsafe names, then extract and reject leftover links.
  local names
  names="$(tar -tzf "$archive" 2>/dev/null || true)"
  [ -n "$names" ] || die "Не удалось прочитать архив пакета"
  if echo "$names" | grep -Eq '(^/)|(^\.\./)|(/\.\./)|(/\.\.$)|\.\./'; then
    die "В архиве опасные пути (.. или абсолютные)"
  fi
  mkdir -p "$dest"
  tar -xzf "$archive" -C "$dest" --no-same-owner --no-same-permissions
  if find "$dest" \( -type l -o -type b -o -type c -o -type p \) | grep -q .; then
    die "В распакованном пакете есть symlink/device — отказ"
  fi
}

verify_outer_sha256() {
  local archive="$1" expect="$2" got
  [ -f "$archive" ] || die "Пакет не найден: $archive"
  got="$(sha256_file "$archive")"
  got="$(printf '%s' "$got" | tr 'A-F' 'a-f')"
  expect="$(printf '%s' "$expect" | tr 'A-F' 'a-f' | tr -d '[:space:]')"
  [ -n "$expect" ] || die "Нет доверенной SHA-256 пакета (ARDTT_PACKAGE_SHA256). Суммы внутри архива недостаточны."
  if [ "$got" != "$expect" ]; then
    die "SHA-256 пакета не совпал (ожидали ${expect}, получили ${got}). Установка не начата."
  fi
}

read_manifest() {
  local dir="$1"
  MANIFEST="$dir/manifest.json"
  [ -f "$MANIFEST" ] || die "В пакете нет manifest.json"
}

manifest_field() {
  json_get "$MANIFEST" "$1"
}

require_manifest() {
  local fmt ver arch img
  fmt="$(manifest_field format)"
  [ "$fmt" = "$PACKAGE_FORMAT" ] || die "Неизвестный формат пакета (${fmt:-пусто}), нужен $PACKAGE_FORMAT"
  PKG_DEPLOY_VERSION="$(manifest_field deployVersion)"
  PKG_ARCH="$(manifest_field arch)"
  PKG_OS="$(manifest_field os)"
  PKG_IMAGE_TAG="$(python3 - "$MANIFEST" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
print((d.get("image") or {}).get("tag") or "")
PY
)"
  PKG_IMAGE_ID="$(python3 - "$MANIFEST" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
print((d.get("image") or {}).get("id") or "")
PY
)"
  [ -n "$PKG_DEPLOY_VERSION" ] || die "manifest.json без deployVersion"
  [ "${PKG_OS:-linux}" = "linux" ] || die "Пакет не для linux"
  [ "$PKG_ARCH" = "$(host_arch)" ] || die "Архитектура пакета ${PKG_ARCH} не совпадает с VPS ($(host_arch))"
  if [ -n "${ARDTT_DEPLOY_VERSION:-}" ] && [ "$ARDTT_DEPLOY_VERSION" != "$PKG_DEPLOY_VERSION" ]; then
    die "Версия пакета ${PKG_DEPLOY_VERSION} не совпадает с ARDTT_DEPLOY_VERSION=${ARDTT_DEPLOY_VERSION}"
  fi
  [ -f "$PKG_DIR/install.sh" ] || die "В пакете нет install.sh"
  [ -f "$PKG_DIR/docker-compose.yml" ] || die "В пакете нет docker-compose.yml"
  if grep -qE '^[[:space:]]*build:' "$PKG_DIR/docker-compose.yml"; then
    die "production Compose в пакете содержит build: — отказ"
  fi
  IMAGE_TAR=""
  if [ -f "$PKG_DIR/images/ardtt.tar" ]; then
    IMAGE_TAR="$PKG_DIR/images/ardtt.tar"
  elif [ -f "$PKG_DIR/ardtt.tar" ]; then
    IMAGE_TAR="$PKG_DIR/ardtt.tar"
  else
    die "В пакете нет docker save образа (images/ardtt.tar)"
  fi
}

verify_loaded_image() {
  local tag="$1" expect_id="$2" got_id got_arch
  got_id="$(docker image inspect -f '{{.Id}}' "$tag" 2>/dev/null || true)"
  [ -n "$got_id" ] || die "docker load не дал образ $tag"
  if [ -n "$expect_id" ] && [ "$got_id" != "$expect_id" ]; then
    die "Image ID после docker load ($got_id) не совпал с манифестом ($expect_id). Это не registry RepoDigest."
  fi
  got_arch="$(docker image inspect -f '{{.Architecture}}' "$tag" 2>/dev/null || true)"
  [ "$got_arch" = "$(host_arch)" ] || die "Архитектура образа ${got_arch} не совпадает с VPS ($(host_arch))"
}

# docker load keeps an existing repo:tag if that name already points at another
# ID. Pin the name to the image ID recorded in this package's manifest.
load_package_image() {
  local tar="$1" tag="$2" expect_id="$3"
  docker load -i "$tar" >/dev/null
  if [ -n "$expect_id" ]; then
    docker image inspect "$expect_id" >/dev/null 2>&1 \
      || die "docker load не импортировал образ ${expect_id} из пакета"
    docker tag "$expect_id" "$tag"
  fi
  verify_loaded_image "$tag" "$expect_id"
}
