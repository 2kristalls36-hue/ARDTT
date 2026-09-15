#!/usr/bin/env bash
# Install Linux/WSL toolchain for ARDTT Android builds. Idempotent.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/lib/common.sh"
ardtt_load_versions

TOOLS_ROOT="${ARDTT_TOOLS_ROOT:-$HOME/ardtt-testlab/tools}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
GO_ROOT="$TOOLS_ROOT/go"
STATE_DIR="${ARDTT_LAB_STATE:-$HOME/ardtt-testlab/state}"
mkdir -p "$TOOLS_ROOT" "$SDK_ROOT" "$STATE_DIR" /tmp/ardtt-sdk-dl

MIN_FREE=$((8 * 1024 * 1024 * 1024))
avail="$(ardtt_free_bytes "$HOME")"
if [[ "$avail" -lt "$MIN_FREE" ]]; then
  ardtt_die "мало места: ${avail} байт в $HOME, нужно >= 8GiB"
fi

need_apt=()
for pkg in git curl ca-certificates unzip zip tar patch make build-essential util-linux coreutils python3; do
  case "$pkg" in
    build-essential) command -v gcc >/dev/null || need_apt+=("$pkg") ;;
    util-linux) command -v flock >/dev/null || need_apt+=("$pkg") ;;
    coreutils) command -v sha256sum >/dev/null || need_apt+=("$pkg") ;;
    *) dpkg -s "$pkg" >/dev/null 2>&1 || need_apt+=("$pkg") ;;
  esac
done
if [[ ${#need_apt[@]} -gt 0 ]]; then
  ardtt_log INFO "apt-get install ${need_apt[*]}"
  sudo apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y "${need_apt[@]}"
fi

download_verify() {
  local url=$1 dest=$2 sha=$3
  if [[ -f "$dest" ]]; then
    if echo "$sha  $dest" | sha256sum -c - >/dev/null 2>&1; then
      return 0
    fi
    ardtt_log WARN "хеш $dest не совпал, качаю заново"
    rm -f "$dest"
  fi
  ardtt_log INFO "download $url"
  curl -fL --retry 4 --retry-delay 4 -o "$dest" "$url"
  echo "$sha  $dest" | sha256sum -c -
}

# Go bootstrap 1.25.x
if [[ ! -x "$GO_ROOT/bin/go" ]] || ! "$GO_ROOT/bin/go" version | grep -q "go${GO_BOOTSTRAP_VERSION}"; then
  tarball="/tmp/ardtt-sdk-dl/$GO_BOOTSTRAP_TARBALL"
  download_verify "https://dl.google.com/go/$GO_BOOTSTRAP_TARBALL" "$tarball" "$GO_BOOTSTRAP_SHA256"
  rm -rf "$GO_ROOT"
  mkdir -p "$TOOLS_ROOT"
  tar -C "$TOOLS_ROOT" -xzf "$tarball"
  [[ -x "$GO_ROOT/bin/go" ]] || ardtt_die "Go не распаковался в $GO_ROOT"
fi
ardtt_prepend_path "$GO_ROOT/bin"
export GOPATH="${GOPATH:-$HOME/go}"
"$GO_ROOT/bin/go" version | grep -q "go${GO_BOOTSTRAP_VERSION}" || ardtt_die "ожидался go ${GO_BOOTSTRAP_VERSION}"

# Command-line tools
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  zip="/tmp/ardtt-sdk-dl/$CMDLINE_TOOLS_ZIP"
  download_verify "$CMDLINE_TOOLS_URL" "$zip" "$CMDLINE_TOOLS_SHA256"
  tmp="$(mktemp -d)"
  unzip -q "$zip" -d "$tmp"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  if [[ -d "$tmp/cmdline-tools/bin" ]]; then
    mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  else
    ardtt_die "неожиданная структура cmdline-tools zip"
  fi
  rm -rf "$tmp"
fi
[[ -x "$SDKMANAGER" ]] || ardtt_die "нет sdkmanager: $SDKMANAGER"

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"

licenses_log="$STATE_DIR/sdk-licenses.log"
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >"$licenses_log" 2>&1 || true
lic_code=$?
set -o pipefail
if [[ "$lic_code" -ne 0 && "$lic_code" -ne 141 ]]; then
  ardtt_log WARN "sdkmanager --licenses код $lic_code (SIGPIPE 141 допустим); смотри $licenses_log"
fi

packages=(
  "platforms;android-${COMPILE_SDK}"
  "build-tools;${BUILD_TOOLS}"
  "ndk;${NDK_VERSION}"
  "cmake;${CMAKE_VERSION}"
  "platform-tools"
)
install_log="$STATE_DIR/sdk-install.log"
ardtt_log INFO "sdkmanager --install ${packages[*]}"
if ! "$SDKMANAGER" --sdk_root="$SDK_ROOT" --install "${packages[@]}" >"$install_log" 2>&1; then
  ardtt_log ERROR "sdkmanager --install failed; last lines:"
  tail -n 40 "$install_log" >&2
  exit 1
fi

list_log="$STATE_DIR/sdk-installed.txt"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --list_installed >"$list_log" 2>&1 || true

missing=0
[[ -d "$SDK_ROOT/platforms/android-${COMPILE_SDK}" ]] || { ardtt_log ERROR "нет platforms;android-${COMPILE_SDK}"; missing=1; }
[[ -x "$SDK_ROOT/build-tools/${BUILD_TOOLS}/aapt" || -x "$SDK_ROOT/build-tools/${BUILD_TOOLS}/aapt2" ]] || { ardtt_log ERROR "нет build-tools ${BUILD_TOOLS}"; missing=1; }
[[ -d "$SDK_ROOT/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64" ]] || { ardtt_log ERROR "нет NDK ${NDK_VERSION} linux-x86_64"; missing=1; }
[[ -d "$SDK_ROOT/cmake/${CMAKE_VERSION}" || -d "$SDK_ROOT/cmake/${CMAKE_VERSION}.0" ]] || { ardtt_log ERROR "нет cmake ${CMAKE_VERSION}"; missing=1; }
[[ -x "$SDK_ROOT/platform-tools/adb" ]] || { ardtt_log ERROR "нет platform-tools adb"; missing=1; }
if [[ "$missing" -ne 0 ]]; then
  tail -n 50 "$install_log" >&2 || true
  exit 1
fi

clang="$SDK_ROOT/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${MIN_SDK}-clang"
[[ -x "$clang" ]] || ardtt_die "нет clang: $clang"

# Confirm Makefile Go hash still matches official 1.27.0
python3 - "$GO_LIBWG_SHA256" <<'PY'
import json, sys, urllib.request
want = sys.argv[1]
with urllib.request.urlopen("https://go.dev/dl/?mode=json&include=all", timeout=30) as r:
    data = json.load(r)
found = None
for rel in data:
    if rel.get("version") == "go1.27.0":
        for f in rel.get("files", []):
            if f.get("filename") == "go1.27.0.linux-amd64.tar.gz":
                found = f.get("sha256")
if found is None:
    raise SystemExit("go1.27.0.linux-amd64.tar.gz not listed on go.dev")
if found != want:
    raise SystemExit(f"libwg-go Makefile hash {want} != go.dev {found}")
print("libwg-go Go 1.27.0 hash matches go.dev")
PY

cat >"$STATE_DIR/linux-toolchain.json" <<EOF
{
  "androidHome": "$SDK_ROOT",
  "goRoot": "$GO_ROOT",
  "goVersion": "$GO_BOOTSTRAP_VERSION",
  "ndk": "$SDK_ROOT/ndk/${NDK_VERSION}",
  "clang": "$clang",
  "sdkmanager": "$SDKMANAGER"
}
EOF

ardtt_log INFO "Linux toolchain ready"
printf 'ANDROID_HOME=%s\n' "$SDK_ROOT"
printf 'ANDROID_NDK_HOME=%s\n' "$SDK_ROOT/ndk/${NDK_VERSION}"
printf 'GOROOT=%s\n' "$GO_ROOT"
"$GO_ROOT/bin/go" version
java -version
"$SDKMANAGER" --version | tail -n 5 || true
