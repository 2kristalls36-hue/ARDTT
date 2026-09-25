#!/usr/bin/env bash
# Idempotent Android SDK/NDK + Go 1.25 setup for Cloud Agent and a fresh Linux host.
# Safe to re-run: already-installed pieces are skipped.
set -euo pipefail

ANDROID_SDK="${HOME}/android-sdk"
NDK_VERSION="27.0.12077973"
BUILD_TOOLS_VERSION="35.0.0"
CMAKE_VERSION="3.22.1"
PLATFORM_ID="android-35"
CMDLINE_TOOLS_BUILD="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
ENV_FILE="${HOME}/.config/ardtt-android-env.sh"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() {
  printf '==> %s\n' "$*"
}

have_pkg() {
  dpkg -s "$1" >/dev/null 2>&1
}

install_apt_packages() {
  local pkg missing=()
  for pkg in ca-certificates curl unzip python3 git patch libc6-dev build-essential; do
    if ! have_pkg "$pkg"; then
      missing+=("$pkg")
    fi
  done
  if ! command -v java >/dev/null 2>&1; then
    missing+=(openjdk-21-jdk)
  fi
  if ((${#missing[@]} == 0)); then
    log "apt packages already present"
    return 0
  fi
  log "installing apt packages: ${missing[*]}"
  sudo -n apt-get update -y
  sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing[@]}"
}

install_cmdline_tools() {
  local sdkmanager="${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager"
  if [[ -x "$sdkmanager" ]]; then
    log "Android cmdline-tools already installed"
    return 0
  fi
  log "downloading Android command-line tools (${CMDLINE_TOOLS_BUILD})"
  local tmp
  tmp="$(mktemp -d)"
  curl -fL --retry 5 --retry-delay 2 -o "${tmp}/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q "${tmp}/cmdline-tools.zip" -d "$tmp"
  if [[ ! -x "${tmp}/cmdline-tools/bin/sdkmanager" ]]; then
    echo "cmdline-tools zip did not contain bin/sdkmanager" >&2
    rm -rf "$tmp"
    exit 1
  fi
  mkdir -p "${ANDROID_SDK}/cmdline-tools"
  rm -rf "${ANDROID_SDK}/cmdline-tools/latest"
  mv "${tmp}/cmdline-tools" "${ANDROID_SDK}/cmdline-tools/latest"
  rm -rf "$tmp"
  chmod +x "${ANDROID_SDK}/cmdline-tools/latest/bin/"*
}

accept_sdk_licenses() {
  local sdkmanager="${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager"
  if [[ -s "${ANDROID_SDK}/licenses/android-sdk-license" ]]; then
    log "Android SDK licenses already accepted"
    return 0
  fi
  log "accepting Android SDK licenses"
  # A fixed number of answers avoids `yes` exiting 141 under pipefail.
  printf 'y\n%.0s' {1..200} | "$sdkmanager" --sdk_root="$ANDROID_SDK" --licenses >/dev/null
}

sdk_package_present() {
  case "$1" in
    platform-tools) [[ -x "${ANDROID_SDK}/platform-tools/adb" ]] ;;
    "platforms;${PLATFORM_ID}") [[ -d "${ANDROID_SDK}/platforms/${PLATFORM_ID}" ]] ;;
    "build-tools;${BUILD_TOOLS_VERSION}") [[ -x "${ANDROID_SDK}/build-tools/${BUILD_TOOLS_VERSION}/aapt2" ]] ;;
    "ndk;${NDK_VERSION}") [[ -d "${ANDROID_SDK}/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64" ]] ;;
    "cmake;${CMAKE_VERSION}") [[ -x "${ANDROID_SDK}/cmake/${CMAKE_VERSION}/bin/cmake" ]] ;;
    *) return 1 ;;
  esac
}

install_sdk_packages() {
  local pkg missing=()
  local packages=(
    "platform-tools"
    "platforms;${PLATFORM_ID}"
    "build-tools;${BUILD_TOOLS_VERSION}"
    "ndk;${NDK_VERSION}"
    "cmake;${CMAKE_VERSION}"
  )
  for pkg in "${packages[@]}"; do
    if sdk_package_present "$pkg"; then
      log "SDK package already installed: ${pkg}"
    else
      missing+=("$pkg")
    fi
  done
  if ((${#missing[@]} == 0)); then
    return 0
  fi
  accept_sdk_licenses
  log "installing SDK packages: ${missing[*]}"
  "${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK" --install "${missing[@]}"
}

go_is_1_25() {
  [[ -x /usr/local/go/bin/go ]] || return 1
  local ver
  ver="$(/usr/local/go/bin/go version 2>/dev/null || true)"
  [[ "$ver" == *" go1.25."* || "$ver" == *" go1.25 "* ]]
}

resolve_go_tarball() {
  # Prints: version filename sha256
  # Prefer the newest go1.25.x from the JSON index. The default index only
  # lists the current stable majors; fall back to include=all, then go1.25.0.
  python3 - "$@" <<'PY'
import json, sys, urllib.request

pinned = ("go1.25.0", "go1.25.0.linux-amd64.tar.gz",
          "2852af0cb20a13139b3448992e69b868e50ed0f8a1e5940ee1de9e19a123b613")
urls = sys.argv[1:]

def pick(data):
    best = None
    for rel in data:
        ver = rel.get("version") or ""
        if not ver.startswith("go1.25."):
            continue
        try:
            patch = int(ver.split(".")[2])
        except (IndexError, ValueError):
            continue
        for f in rel.get("files") or []:
            if f.get("os") == "linux" and f.get("arch") == "amd64" and f.get("kind") == "archive":
                if best is None or patch > best[0]:
                    best = (patch, ver, f.get("filename") or "", f.get("sha256") or "")
    return best

for url in urls:
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            data = json.load(resp)
    except Exception as exc:
        print(f"go index {url} failed: {exc}", file=sys.stderr)
        continue
    chosen = pick(data)
    if chosen:
        print(chosen[1])
        print(chosen[2])
        print(chosen[3])
        sys.exit(0)

print(pinned[0])
print(pinned[1])
print(pinned[2])
PY
}

install_go() {
  if go_is_1_25; then
    log "Go already 1.25: $(/usr/local/go/bin/go version)"
    return 0
  fi
  local meta version filename sha url tmp
  meta="$(resolve_go_tarball \
    "https://go.dev/dl/?mode=json" \
    "https://go.dev/dl/?mode=json&include=all")"
  version="$(printf '%s\n' "$meta" | sed -n '1p')"
  filename="$(printf '%s\n' "$meta" | sed -n '2p')"
  sha="$(printf '%s\n' "$meta" | sed -n '3p')"
  if [[ -z "$version" || -z "$filename" ]]; then
    echo "failed to resolve a Go 1.25 tarball" >&2
    exit 1
  fi
  url="https://go.dev/dl/${filename}"
  log "installing ${version} into /usr/local/go"
  tmp="$(mktemp -d)"
  curl -fL --retry 5 --retry-delay 2 -o "${tmp}/go.tgz" "$url"
  if [[ -n "$sha" ]]; then
    echo "${sha}  ${tmp}/go.tgz" | sha256sum -c -
  fi
  sudo -n rm -rf /usr/local/go
  sudo -n tar -C /usr/local -xzf "${tmp}/go.tgz"
  rm -rf "$tmp"
  if ! go_is_1_25; then
    echo "Go install did not produce a 1.25 toolchain: $(/usr/local/go/bin/go version 2>&1 || true)" >&2
    exit 1
  fi
}

write_env_file() {
  mkdir -p "${HOME}/.config"
  local tmp
  tmp="$(mktemp)"
  cat >"$tmp" <<'EOF'
# Android SDK / NDK / Go for ARDTT. Sourced from bashrc and profile.
export ANDROID_HOME="${HOME}/android-sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/27.0.12077973"

_ardtt_path_prepend() {
  case ":${PATH}:" in
    *":$1:"*) ;;
    *) PATH="$1:${PATH}" ;;
  esac
}
# Prepend lowest priority first so cmdline-tools stays at the front.
# /usr/local/go/bin must precede /usr/bin (system Go is older).
_ardtt_path_prepend "${HOME}/go/bin"
_ardtt_path_prepend "/usr/local/go/bin"
_ardtt_path_prepend "${ANDROID_HOME}/build-tools/35.0.0"
_ardtt_path_prepend "${ANDROID_HOME}/platform-tools"
_ardtt_path_prepend "${ANDROID_HOME}/cmdline-tools/latest/bin"
export PATH
unset -f _ardtt_path_prepend
EOF
  if [[ -f "$ENV_FILE" ]] && cmp -s "$tmp" "$ENV_FILE"; then
    rm -f "$tmp"
    log "env file already up to date: ${ENV_FILE}"
  else
    mv "$tmp" "$ENV_FILE"
    chmod 644 "$ENV_FILE"
    log "wrote ${ENV_FILE}"
  fi
}

ensure_shell_sources_env() {
  local file="$1"
  local marker
  marker='[ -f "$HOME/.config/ardtt-android-env.sh" ] && . "$HOME/.config/ardtt-android-env.sh"'
  if [[ -f "$file" ]] && grep -qF 'ardtt-android-env.sh' "$file"; then
    log "already sourced from ${file}"
    return 0
  fi
  mkdir -p "$(dirname "$file")"
  touch "$file"
  printf '\n# Android SDK / Go for ARDTT\n%s\n' "$marker" >>"$file"
  log "sourced env from ${file}"
}

write_local_properties() {
  local props="${ROOT_DIR}/android/local.properties"
  local line="sdk.dir=${ANDROID_SDK}"
  if ! git -C "$ROOT_DIR" check-ignore -q -- android/local.properties; then
    echo "android/local.properties is not gitignored; refusing to write it" >&2
    return 0
  fi
  if [[ -f "$props" ]] && [[ "$(cat "$props")" == "$line" ]]; then
    log "android/local.properties already set"
    return 0
  fi
  printf '%s\n' "$line" >"$props"
  log "wrote ${props}"
}

print_versions() {
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  hash -r || true
  echo "=== java ==="
  java -version
  echo "=== go ==="
  command -v go
  go version
  echo "=== sdkmanager --list_installed ==="
  sdkmanager --list_installed
  echo "=== cmake ==="
  cmake --version | head -n 1
  if [[ -x "${ANDROID_HOME}/cmake/${CMAKE_VERSION}/bin/cmake" ]]; then
    echo -n "sdk "
    "${ANDROID_HOME}/cmake/${CMAKE_VERSION}/bin/cmake" --version | head -n 1
  fi
}

install_apt_packages
install_cmdline_tools
install_sdk_packages
install_go
write_env_file
ensure_shell_sources_env "${HOME}/.bashrc"
ensure_shell_sources_env "${HOME}/.profile"
write_local_properties
print_versions
log "done"
