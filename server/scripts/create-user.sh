#!/usr/bin/env bash
# Create a VPN user and print ardtt profile JSON (keys for AWG filled later).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="${1:-}"
if [[ -z "$NAME" ]]; then
  echo "usage: $0 <username>" >&2
  exit 1
fi
mkdir -p "$ROOT/data"
export ARDTT_PUBLIC_HOST="${ARDTT_PUBLIC_HOST:-}"
cd "$ROOT/provision"
go run . -cmd create-user -name "$NAME" -data "$ROOT/data" ${ARDTT_PUBLIC_HOST:+-public-host "$ARDTT_PUBLIC_HOST"}
