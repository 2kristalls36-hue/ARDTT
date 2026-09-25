#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
# shellcheck disable=SC1091
source "$LAB/lib/common.sh"

FAIL=0
fail() { echo "FAIL: $*" >&2; FAIL=1; }
ok() { echo "OK $*"; }

[[ "$(ardtt_lab_root)" == "$LAB" ]] || fail "lab root $(ardtt_lab_root) != $LAB"
ok "lab root"

ardtt_load_versions
[[ -n "${NDK_VERSION:-}" ]] || fail "versions not loaded"
ok "load versions"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
printf 'hello\n' >"$tmp"
sum="$(ardtt_sha256_file "$tmp")"
[[ "$sum" == "$(printf 'hello\n' | sha256sum | awk '{print $1}')" ]] || fail "sha256"
ok "sha256"

before="$PATH"
ardtt_prepend_path "/tmp/ardtt-does-not-need-to-exist"
[[ "$PATH" == "/tmp/ardtt-does-not-need-to-exist:$before" ]] || fail "prepend path first"
ardtt_prepend_path "/tmp/ardtt-does-not-need-to-exist"
[[ "$PATH" == "/tmp/ardtt-does-not-need-to-exist:$before" ]] || fail "prepend path dup"
ok "prepend path"
PATH="$before"

if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi
echo "common.sh tests passed"
