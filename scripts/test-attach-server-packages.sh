#!/usr/bin/env bash
# attach-server-packages-to-release.sh must create the GitHub Release when
# the tag exists but Android build has not published it yet (tag workflows
# race). Without that, Server package fails with "release not found".
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
FAIL=0
pass() { echo "OK $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

run_case() {
  local name="$1" mode="$2"
  local work log bin
  work="$(mktemp -d)"
  trap 'rm -rf "'"$work"'"' RETURN
  mkdir -p "$work/dist" "$work/bin"
  log="$work/gh.log"
  : >"$log"
  printf 'tiny-%s\n' amd64 | gzip -n >"$work/dist/ardtt-server-${VER}-linux-amd64.tar.gz"
  printf 'tiny-%s\n' arm64 | gzip -n >"$work/dist/ardtt-server-${VER}-linux-arm64.tar.gz"
  cat >"$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >>"${GH_LOG:?}"
case "${1:-} ${2:-}" in
  "release view")
    if [ "${GH_RELEASE_EXISTS:-0}" = 1 ] || [ -f "${GH_STATE_DIR:?}/created" ]; then
      echo '{"assets":[]}'
      exit 0
    fi
    echo "release not found" >&2
    exit 1
    ;;
  "release create")
    if [ "${GH_CREATE_FAILS:-0}" = 1 ]; then
      echo "already exists" >&2
      exit 1
    fi
    echo 1 >"${GH_STATE_DIR:?}/created"
    echo '{"html_url":"https://example.invalid/release"}'
    exit 0
    ;;
  "release upload")
    printf '%s\n' "$@" >"${GH_STATE_DIR:?}/upload"
    exit 0
    ;;
  "release edit")
    printf '%s\n' "$@" >"${GH_STATE_DIR:?}/edit"
    exit 0
    ;;
  *)
    echo "unexpected gh $*" >&2
    exit 1
    ;;
esac
EOF
  chmod +x "$work/bin/gh"

  local env_exists=0 create_fails=0
  case "$mode" in
    missing) env_exists=0; create_fails=0 ;;
    exists) env_exists=1; create_fails=0 ;;
    race) env_exists=0; create_fails=1 ;;
    *) echo "bad mode $mode" >&2; return 1 ;;
  esac

  set +e
  (
    export PATH="$work/bin:$PATH"
    export GH_LOG="$log"
    export GH_STATE_DIR="$work"
    export GH_RELEASE_EXISTS="$env_exists"
    export GH_CREATE_FAILS="$create_fails"
    export GITHUB_REPOSITORY="2kristalls36-hue/ARDTT"
    # Race: create fails because Android just created the release; the next
    # view must succeed.
    if [ "$mode" = race ]; then
      cat >"$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >>"${GH_LOG:?}"
case "${1:-} ${2:-}" in
  "release view")
    if [ -f "${GH_STATE_DIR:?}/appeared" ]; then
      echo '{"assets":[]}'
      exit 0
    fi
    echo "release not found" >&2
    exit 1
    ;;
  "release create")
    echo 1 >"${GH_STATE_DIR:?}/appeared"
    echo "HTTP 422: already exists" >&2
    exit 1
    ;;
  "release upload")
    printf '%s\n' "$@" >"${GH_STATE_DIR:?}/upload"
    exit 0
    ;;
  "release edit")
    printf '%s\n' "$@" >"${GH_STATE_DIR:?}/edit"
    exit 0
    ;;
  *)
    echo "unexpected gh $*" >&2
    exit 1
    ;;
esac
EOF
      chmod +x "$work/bin/gh"
    fi
    bash "$ROOT/scripts/attach-server-packages-to-release.sh" \
      --tag "v0.5.264" --from-dir "$work/dist"
  )
  local rc=$?
  set -e

  if [ "$rc" -ne 0 ]; then
    fail "$name: attach exited $rc"
    cat "$log" >&2 || true
    return 0
  fi
  if [ ! -f "$work/upload" ]; then
    fail "$name: did not upload"
    return 0
  fi
  if [ ! -f "$work/edit" ] || ! grep -q -- '--draft=false' "$work/edit" || ! grep -q -- '--latest' "$work/edit"; then
    fail "$name: did not publish the GitHub Release (edit --draft=false --latest)"
    cat "$log" >&2 || true
    return 0
  fi
  case "$mode" in
    missing)
      if [ ! -f "$work/created" ]; then
        fail "$name: missing release was not created"
        return 0
      fi
      ;;
    exists)
      if grep -q 'release create' "$log"; then
        fail "$name: created a release that already existed"
        return 0
      fi
      ;;
    race)
      if [ ! -f "$work/appeared" ]; then
        fail "$name: did not retry after create lost the race"
        return 0
      fi
      ;;
  esac
  pass "$name"
}

run_case "create missing GitHub Release then attach" missing
run_case "existing empty release just uploads" exists
run_case "create races with Android build, then attach" race

if [ "$FAIL" -ne 0 ]; then
  echo "test-attach-server-packages failed" >&2
  exit 1
fi
echo "OK test-attach-server-packages"
