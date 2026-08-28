#!/usr/bin/env bash
# Build WDTT go_client as libclient.so for Android ABIs (Path B).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="$ROOT_DIR/android/go_client"
OUT_BASE="$ROOT_DIR/android/app/src/main/jniLibs"
API_LEVEL="${ANDROID_NATIVE_API_LEVEL:-28}"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -n "${1:-}" ]]; then
  ABIS=("$1")
else
  ABIS=(arm64-v8a x86_64)
fi

if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
  SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$SDK_DIR" && -f "$ROOT_DIR/android/local.properties" ]]; then
    SDK_DIR="$(grep -E '^sdk\.dir=' "$ROOT_DIR/android/local.properties" | head -n1 | cut -d= -f2- || true)"
  fi
  if [[ -n "$SDK_DIR" ]]; then
    NDK_DIR="$(find "$SDK_DIR/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n1 || true)"
  fi
fi

if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
  echo "NDK not found. Set ANDROID_NDK_HOME or install NDK." >&2
  exit 1
fi

HOST_TAG="linux-x86_64"
export PATH="${PATH}"

for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) GOARCH=arm64; CLANG_PREFIX=aarch64-linux-android ;;
    x86_64)    GOARCH=amd64; CLANG_PREFIX=x86_64-linux-android ;;
    armeabi-v7a) GOARCH=arm; CLANG_PREFIX=armv7a-linux-androideabi ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
  esac
  CC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin/${CLANG_PREFIX}${API_LEVEL}-clang"
  if [[ ! -x "$CC" ]]; then
    echo "Compiler not found: $CC" >&2
    exit 1
  fi
  OUT_DIR="$OUT_BASE/$ABI"
  mkdir -p "$OUT_DIR"
  echo "Building $ABI -> $OUT_DIR/libclient.so"
  (
    cd "$GO_DIR"
    GOOS=android GOARCH="$GOARCH" CGO_ENABLED=1 CC="$CC" \
      go build -trimpath -ldflags=-checklinkname=0 -o "$OUT_DIR/libclient.so" .
  )
  ls -lh "$OUT_DIR/libclient.so"
done

echo "Done."
