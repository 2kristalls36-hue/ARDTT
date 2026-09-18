#!/usr/bin/env bash
# Static host-side ardttctl for linux amd64/arm64.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCH="${1:-${ARDTT_PACKAGE_ARCH:-}}"
if [ -z "$ARCH" ]; then
  case "$(uname -m)" in
    x86_64|amd64) ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
    *) echo "set arch amd64|arm64" >&2; exit 1 ;;
  esac
fi
OUT="${2:-$ROOT/dist/ardttctl-linux-${ARCH}}"
mkdir -p "$(dirname "$OUT")"
export CGO_ENABLED=0
export GOOS=linux
export GOARCH="$ARCH"
go -C "$ROOT/server/ardttctl" build -trimpath -ldflags='-s -w' -o "$OUT" .
test -x "$OUT"
echo "$OUT"
