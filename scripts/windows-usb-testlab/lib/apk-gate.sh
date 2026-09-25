#!/usr/bin/env bash
# Reject GitHub latest / old Preview APKs that Android will not treat as an update.
# shellcheck disable=SC1091

ardtt_apk_artifact_looks_current() {
  local name=$1
  local min_name=${ARDTT_MIN_INSTALL_VERSION_NAME:-0.5.265}
  local reject_sha=${ARDTT_REJECT_ARTIFACT_SHA:-51cf7ce}
  case "$name" in
    *"0.5.264"*|*"ardtt-0.5.263"*|*"ardtt-0.5.262"*|*"$reject_sha"*)
      return 1
      ;;
  esac
  [[ "$name" == *"$min_name"* ]]
}

ardtt_assert_preview_apk_current() {
  local name=$1
  local min_name=${ARDTT_MIN_INSTALL_VERSION_NAME:-0.5.265}
  local min_code=${ARDTT_MIN_INSTALL_VERSION_CODE:-284}
  if ! ardtt_apk_artifact_looks_current "$name"; then
    ardtt_die "это не APK ${min_name} (versionCode ${min_code}): ${name}. GitHub Release v0.5.264 (283) и Preview PR 217 с тем же 283 Android не ставит поверх уже установленного 0.5.264. Скачайте Preview APK run ${ARDTT_PREVIEW_RUN_ID:-34944525811}."
  fi
}

ardtt_aapt_bin() {
  local c
  for c in \
    "${ANDROID_HOME:-}/build-tools/${BUILD_TOOLS:-35.0.0}/aapt" \
    "${ANDROID_SDK_ROOT:-}/build-tools/${BUILD_TOOLS:-35.0.0}/aapt" \
    aapt
  do
    if command -v "$c" >/dev/null 2>&1; then
      printf '%s\n' "$c"
      return 0
    fi
    if [[ -x "$c" ]]; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

ardtt_apk_version_code() {
  local apk=$1
  local aapt
  aapt="$(ardtt_aapt_bin)" || return 1
  "$aapt" dump badging "$apk" 2>/dev/null | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1
}

ardtt_assert_apk_sha_not_rejected() {
  local apk=$1
  local reject=${ARDTT_REJECT_APK_SHA256:-c6e9a361d1f12a6d943c9babbfdefdb37827f95c9d18a07a1e76251785ea20fd}
  local got
  got="$(ardtt_sha256_file "$apk")"
  if [[ "${got,,}" == "${reject,,}" ]]; then
    ardtt_die "это повтор Preview 51cf7ce (SHA-256 ${got}). На OnePlus он уже стоит как 0.5.264/283. Нужен APK ${ARDTT_MIN_INSTALL_VERSION_NAME:-0.5.265} SHA ${ARDTT_PREVIEW_APK_SHA256:-8ba45dd66a13bafeb4faec3ee8a114a0d31c35e0c94a06141637929ca34d0642} (run ${ARDTT_PREVIEW_RUN_ID:-34944525811})."
  fi
}

ardtt_assert_apk_version_code() {
  local apk=$1
  local min_code=${ARDTT_MIN_INSTALL_VERSION_CODE:-284}
  local got
  got="$(ardtt_apk_version_code "$apk" || true)"
  [[ -n "$got" ]] || return 0
  if [[ "$got" -lt "$min_code" ]]; then
    ardtt_die "APK versionCode ${got} < ${min_code}. Установщик не обновит пакет, который уже ${min_code} или 283 (v0.5.264)."
  fi
}
