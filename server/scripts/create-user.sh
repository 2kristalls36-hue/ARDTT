#!/usr/bin/env bash
# Create a VPN user and print nvpn profile JSON (keys for AWG filled later).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="${1:-}"
if [[ -z "$NAME" ]]; then
  echo "usage: $0 <username>" >&2
  exit 1
fi
mkdir -p "$ROOT/data"
export NVPN_PUBLIC_HOST="${NVPN_PUBLIC_HOST:-}"
cd "$ROOT/provision"
go run . -cmd create-user -name "$NAME" -data "$ROOT/data" ${NVPN_PUBLIC_HOST:+-public-host "$NVPN_PUBLIC_HOST"}
