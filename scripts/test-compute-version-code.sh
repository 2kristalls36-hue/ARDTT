#!/usr/bin/env bash
# compute-version-code.sh: release is commit-count*10, preview is base*10
# plus PR commits capped at 9, and both modes refuse a code at or below the floor.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/compute-version-code.sh"
FAIL=0
pass() { echo "OK $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

init_repo() {
  local dir="$1"
  git init -b main "$dir" >/dev/null
  git -C "$dir" config user.email "test@example.com"
  git -C "$dir" config user.name "test"
  mkdir -p "$dir/android/app"
}

set_floor() {
  local dir="$1" floor="$2"
  printf 'val releaseVersionCode = %s\n' "$floor" >"$dir/android/app/build.gradle.kts"
}

empty_commit() {
  local dir="$1" msg="$2"
  git -C "$dir" commit --allow-empty -m "$msg" >/dev/null
}

REPO="$WORKDIR/repo"
init_repo "$REPO"
set_floor "$REPO" 1
empty_commit "$REPO" c1
empty_commit "$REPO" c2
empty_commit "$REPO" c3

got="$(cd "$REPO" && bash "$SCRIPT" release)"
if [ "$got" = "30" ]; then
  pass "release 3 commits -> 30"
else
  fail "release 3 commits: got ${got}, want 30"
fi

got="$(cd "$REPO" && bash "$SCRIPT" preview main)"
if [ "$got" = "30" ]; then
  pass "preview with 0 commits ahead -> 30"
else
  fail "preview 0 ahead: got ${got}, want 30"
fi

git -C "$REPO" checkout -b feature >/dev/null
empty_commit "$REPO" p1
empty_commit "$REPO" p2
got="$(cd "$REPO" && bash "$SCRIPT" preview main)"
if [ "$got" = "32" ]; then
  pass "preview 2 commits ahead -> 32"
else
  fail "preview 2 ahead: got ${got}, want 32"
fi

for i in $(seq 1 10); do
  empty_commit "$REPO" "extra$i"
done
got="$(cd "$REPO" && bash "$SCRIPT" preview main)"
if [ "$got" = "39" ]; then
  pass "preview commits capped at 9 -> 39"
else
  fail "preview cap: got ${got}, want 39"
fi

LOW="$WORKDIR/low"
init_repo "$LOW"
set_floor "$LOW" 30
empty_commit "$LOW" a
empty_commit "$LOW" b
empty_commit "$LOW" c
if (cd "$LOW" && bash "$SCRIPT" release) >"$WORKDIR/out" 2>"$WORKDIR/err"; then
  fail "code equal to floor must fail"
else
  pass "fails when code is not above the floor"
fi
if grep -q 'floor 30' "$WORKDIR/err"; then
  pass "floor error names the floor"
else
  fail "floor error should mention floor 30"
fi

set_floor "$REPO" 1000
if (cd "$REPO" && bash "$SCRIPT" preview main) >"$WORKDIR/out" 2>"$WORKDIR/err"; then
  fail "preview below floor must fail"
else
  pass "preview fails when code is below the floor"
fi

if bash "$SCRIPT" >"$WORKDIR/out" 2>"$WORKDIR/err"; then
  fail "missing args must fail"
else
  pass "usage failure without args"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "test-compute-version-code failed" >&2
  exit 1
fi
echo "OK test-compute-version-code"
