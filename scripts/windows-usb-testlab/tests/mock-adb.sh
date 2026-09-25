#!/usr/bin/env bash
# Mock adb for lab tests. Honors MOCK_ADB_ROOT for fake device filesystem.
set -euo pipefail
LOG="${MOCK_ADB_LOG:-}"
ROOT="${MOCK_ADB_ROOT:-/tmp/ardtt-mock-adb}"
mkdir -p "$ROOT/device" "$ROOT/host"
if [[ -n "$LOG" ]]; then
  printf '%s\n' "$*" >>"$LOG"
fi

if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi

cmd=${1:-}
shift || true

write_png() {
  local path=$1
  python3 - "$path" <<'PY'
import struct, sys, zlib
path = sys.argv[1]
def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
raw = b"\x89PNG\r\n\x1a\n"
raw += chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0))
raw += chunk(b"IDAT", zlib.compress(b"\x00\x00"))
raw += chunk(b"IEND", b"")
open(path, "wb").write(raw)
PY
}

case "$cmd" in
  version)
    echo "Android Debug Bridge version 1.0.41 (mock)"
    exit 0
    ;;
  devices)
    echo "List of devices attached"
    echo "MOCKSERIAL0123         device usb:1-1 product:mock model:Pixel device:mock transport_id:1"
    exit 0
    ;;
  logcat)
    joined=" $* "
    if [[ "$joined" == *" --help "* ]] || [[ "$joined" == *" -h "* ]] || [[ "$joined" == *" -d "* ]]; then
      echo "Usage: logcat [options]"
      echo "mock logcat dump"
      exit 0
    fi
    echo "--------- beginning of main"
    echo "01-01 00:00:00.000  1  1 I ConnMgr: auto-stage path_decision series=deadbeef event=mock"
    while true; do
      echo "01-01 00:00:01.000  1  1 I ArdttLab: heartbeat"
      sleep 0.2
    done
    ;;
  pull)
    src=${1:-}
    dest=${2:-.}
    base="$(basename "$src")"
    fake="$ROOT/device/$base"
    if [[ -d "$dest" ]]; then
      dest="$dest/$base"
    fi
    mkdir -p "$(dirname "$dest")"
    if [[ -f "$fake" ]]; then
      cp "$fake" "$dest"
    else
      printf 'mock-pulled:%s\n' "$src" >"$dest"
    fi
    echo "1 file pulled"
    exit 0
    ;;
  shell)
    sub=${1:-}
    shift || true
    case "$sub" in
      screencap)
        dest=""
        while [[ $# -gt 0 ]]; do
          if [[ "$1" != "-p" ]]; then
            dest=$1
          fi
          shift
        done
        dest_name="$(basename "${dest:-screencap.png}")"
        write_png "$ROOT/device/$dest_name"
        exit 0
        ;;
      rm)
        exit 0
        ;;
      getprop)
        key=${1:-}
        case "$key" in
          ro.product.model) echo "Mock Phone" ;;
          ro.build.version.release) echo "14" ;;
          ro.build.version.sdk) echo "34" ;;
          ro.product.cpu.abi) echo "arm64-v8a" ;;
          "")
            echo "[ro.product.model]: [Mock Phone]"
            echo "[ro.build.version.release]: [14]"
            echo "[ro.product.cpu.abi]: [arm64-v8a]"
            ;;
          *) echo "" ;;
        esac
        exit 0
        ;;
      dumpsys|pidof|ps|ip|cmd|settings|pm|uptime|date)
        echo "mock $sub $*"
        exit 0
        ;;
      *)
        echo "mock shell $sub $*"
        exit 0
        ;;
    esac
    ;;
  *)
    echo "mock adb: unsupported $cmd $*" >&2
    exit 0
    ;;
esac
