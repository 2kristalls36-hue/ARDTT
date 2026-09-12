#!/usr/bin/env bash
# ensure-release-apks.sh: skip when the tag already has the APK; upload when
# dist has APKs; fail when neither.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0
pass() { echo "OK $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

fake_gh() {
  local bin="$1"
  cat >"$bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >>"${GH_LOG:?}"
case "${1:-} ${2:-}" in
  "release view")
    if [ "${GH_HAS_RELEASE:-0}" != 1 ]; then
      echo "release not found" >&2
      exit 1
    fi
    if [[ "$*" == *"--json"* ]]; then
      if [[ "$*" == *"--jq"* ]]; then
        python3 -c 'import os; print("\n".join(n for n in os.environ.get("GH_ASSET_NAMES","").split() if n))'
      else
        python3 -c 'import json,os; print(json.dumps({"assets":[{"name":n} for n in os.environ.get("GH_ASSET_NAMES","").split() if n]}))'
      fi
      exit 0
    fi
    echo '{"html_url":"https://example.invalid/release"}'
    exit 0
    ;;
  "release upload")
    printf '%s\n' "$@" >"${GH_STATE:?}/upload"
    exit 0
    ;;
  "release create")
    printf '%s\n' "$@" >"${GH_STATE:?}/create"
    exit 0
    ;;
  *)
    echo "unexpected gh $*" >&2
    exit 1
    ;;
esac
EOF
  chmod +x "$bin/gh"
}

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
mkdir -p "$WORKDIR/bin" "$WORKDIR/empty" "$WORKDIR/withapk" "$WORKDIR/state"
fake_gh "$WORKDIR/bin"
export PATH="$WORKDIR/bin:$PATH"
export GITHUB_REPOSITORY="2kristalls36-hue/ARDTT"
export GH_LOG="$WORKDIR/gh.log"
export GH_STATE="$WORKDIR/state"
: >"$GH_LOG"

# Dist empty, tag already has the APK → skip (stack-only attach).
: >"$GH_LOG"
export GH_HAS_RELEASE=1
export GH_ASSET_NAMES="ardtt-0.5.264-arm64-v8a.apk ardtt-update.json"
if bash "$ROOT/scripts/ensure-release-apks.sh" v0.5.264 0.5.264 "$WORKDIR/empty" \
  | grep -q 'skip APK upload'; then
  pass "skip when APKs already on the tag"
else
  fail "empty dist should skip when the tag already has the APK"
fi
if grep -q 'release upload' "$GH_LOG"; then
  fail "skip path must not upload APKs"
fi

# Dist empty, tag missing the APK → fail.
: >"$GH_LOG"
export GH_HAS_RELEASE=1
export GH_ASSET_NAMES="ardtt-server-1.0.52-linux-amd64.tar.gz"
if bash "$ROOT/scripts/ensure-release-apks.sh" v0.5.264 0.5.264 "$WORKDIR/empty" \
  >/dev/null 2>"$WORKDIR/err"; then
  fail "empty dist must fail when the tag has no APK"
else
  grep -q 'need ardtt-0.5.264' "$WORKDIR/err" \
    && pass "fail closed when APKs are missing" \
    || fail "missing-APK error should name the version"
fi

# Dist has APKs → rewrite + upload --clobber.
printf 'apk\n' >"$WORKDIR/withapk/ardtt-0.5.264-arm64-v8a.apk"
printf 'apk\n' >"$WORKDIR/withapk/ardtt-0.5.264-universal.apk"
printf '{}\n' >"$WORKDIR/withapk/ardtt-update.json"
: >"$GH_LOG"
export GH_HAS_RELEASE=1
export GH_ASSET_NAMES=""
if bash "$ROOT/scripts/ensure-release-apks.sh" v0.5.264 0.5.264 "$WORKDIR/withapk" \
  >/dev/null; then
  grep -q 'release upload' "$GH_LOG" && pass "upload APKs from dist" \
    || fail "dist APKs should call gh release upload"
else
  fail "dist with APKs should succeed"
fi
test -s "$WORKDIR/withapk/SHA256SUMS.txt" || fail "SHA256SUMS.txt not written"
grep -q 'ardtt-0.5.264-arm64-v8a.apk' "$WORKDIR/withapk/ardtt-update.json" \
  || fail "update json apkUrl not rewritten"

if [ "$FAIL" -ne 0 ]; then
  echo "test-ensure-release-apks failed" >&2
  exit 1
fi
echo "OK test-ensure-release-apks"
