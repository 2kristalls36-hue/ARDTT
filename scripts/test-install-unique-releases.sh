#!/usr/bin/env bash
# Each deploy attempt gets a unique releases/<deploymentId> that is never a
# current/previous target. Same-version restage must not clobber rollback.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
INSTALL_DIR="$TMP/opt"
mkdir -p "$INSTALL_DIR/releases" "$INSTALL_DIR/data" "$INSTALL_DIR/state"

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/ownership.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/switch.sh"

checksum_tree() {
  python3 - "$1" <<'PY'
import hashlib, os, sys
root = sys.argv[1]
h = hashlib.sha256()
for dirpath, dirs, files in os.walk(root, followlinks=False):
    dirs.sort()
    for name in sorted(files):
        fp = os.path.join(dirpath, name)
        if os.path.islink(fp):
            continue
        rel = os.path.relpath(fp, root).replace("\\", "/")
        h.update(rel.encode())
        h.update(b"\0")
        with open(fp, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 16), b""):
                h.update(chunk)
print(h.hexdigest())
PY
}

mkrel() {
  local id="$1" marker="$2" ver="${3:-1.0.54}"
  mkdir -p "$INSTALL_DIR/releases/$id"
  printf '%s\n' "$marker" > "$INSTALL_DIR/releases/$id/docker-compose.yml"
  printf 'ARDTT_DEPLOY_VERSION=%s\n' "$ver" > "$INSTALL_DIR/releases/$id/.env"
  printf '%s\n' "$ver" > "$INSTALL_DIR/releases/$id/DEPLOY_VERSION"
}

pointer_real() {
  readlink -f "${INSTALL_DIR}/$1" 2>/dev/null || true
}

# Mimic install.sh staging: unique unused dir, never a live pointer, then snapshot+activate.
stage_and_activate() {
  local marker="$1"
  local ver="${2:-1.0.54}"
  local release abs prev_abs cur_abs
  DEPLOY_VERSION="$ver"
  release="$(release_staging_path "$ver")" || return 1
  abs="$(readlink -f "$release" 2>/dev/null || echo "$release")"
  prev_abs="$(pointer_real previous)"
  cur_abs="$(pointer_real current)"
  if [ -n "$prev_abs" ] && [ "$abs" = "$prev_abs" ]; then
    echo "staging collided with previous: $release" >&2
    return 2
  fi
  if [ -n "$cur_abs" ] && [ "$abs" = "$cur_abs" ]; then
    echo "staging collided with current: $release" >&2
    return 2
  fi
  mkdir -p "$release"
  printf '%s\n' "$marker" > "$release/docker-compose.yml"
  printf 'ARDTT_DEPLOY_VERSION=%s\n' "$ver" > "$release/.env"
  printf '%s\n' "$ver" > "$release/DEPLOY_VERSION"
  snapshot_current_to_previous
  activate_release_tree "$release"
  printf '%s' "$release"
}

# --- reproduce: first install, then two same-version deploys ---
mkrel d-first "GOOD-ROLLBACK" 1.0.54
python3 "$ROOT/server/install-lib/atomic-pointer.py" replace "$INSTALL_DIR" current releases/d-first
DEPLOY_VERSION=1.0.54
# Second deploy of 1.0.54: previous becomes the confirmed first tree.
r2="$(stage_and_activate CANDIDATE-2 1.0.54)" || err "second deploy failed"
[ -L "$INSTALL_DIR/previous" ] || err "previous missing after second"
prev_target="$(readlink -f "$INSTALL_DIR/previous")"
[ "$prev_target" = "$(readlink -f "$INSTALL_DIR/releases/d-first")" ] \
  || err "previous should still be first confirmed release"
grep -qx 'GOOD-ROLLBACK' "$INSTALL_DIR/previous/docker-compose.yml" || err "previous content after 2"
sum_prev="$(checksum_tree "$(readlink -f "$INSTALL_DIR/previous")")"
ok "second same-version deploy keeps first tree as previous"

# Third same-version deploy must retarget previous to the last confirmed (r2),
# not skip snapshot just because deployVersion matches (live VPS regression).
sum_r2="$(checksum_tree "$(readlink -f "$r2")")"
r3="$(stage_and_activate PARTIAL-CANDIDATE 1.0.54)" || err "third deploy failed"
[ "$(readlink -f "$INSTALL_DIR/previous")" = "$(readlink -f "$r2")" ] \
  || err "previous must move to last confirmed current, not stay on first (got $(readlink -f "$INSTALL_DIR/previous"))"
grep -qx 'CANDIDATE-2' "$INSTALL_DIR/previous/docker-compose.yml" \
  || err "previous should be CANDIDATE-2 (got $(cat "$INSTALL_DIR/previous/docker-compose.yml"))"
grep -qx 'GOOD-ROLLBACK' "$INSTALL_DIR/releases/d-first/docker-compose.yml" \
  || err "first confirmed tree was mutated"
[ "$(checksum_tree "$(readlink -f "$r2")")" = "$sum_r2" ] || err "last confirmed tree mutated on third deploy"
[ "$(checksum_tree "$(readlink -f "$INSTALL_DIR/releases/d-first")")" = "$sum_prev" ] \
  || err "first tree bytes changed on third deploy"
[ "$(readlink -f "$r3")" != "$(readlink -f "$INSTALL_DIR/previous")" ] \
  || err "third staging path is previous"
[ "$(readlink -f "$INSTALL_DIR/current")" != "$(readlink -f "$INSTALL_DIR/previous")" ] \
  || err "current and previous collapsed"
ok "third same-version deploy retargets previous to last confirmed"

# Five sequential same-version deploys: unique paths; previous is last confirmed; trees immutable.
paths=("$INSTALL_DIR/releases/d-first" "$r2" "$r3")
last_current="$r3"
last_sum="$(checksum_tree "$(readlink -f "$r3")")"
for i in 4 5; do
  p="$(stage_and_activate "CANDIDATE-$i" 1.0.54)" || err "deploy $i failed"
  paths+=("$p")
  [ "$(readlink -f "$INSTALL_DIR/previous")" = "$(readlink -f "$last_current")" ] \
    || err "previous not last confirmed after deploy $i"
  [ "$(checksum_tree "$(readlink -f "$last_current")")" = "$last_sum" ] \
    || err "last confirmed tree mutated on deploy $i"
  last_current="$p"
  last_sum="$(checksum_tree "$(readlink -f "$p")")"
done
uniq="$(printf '%s\n' "${paths[@]}" | while read -r p; do readlink -f "$p"; done | sort -u | wc -l)"
[ "$uniq" -eq "${#paths[@]}" ] || err "duplicate staging paths among five deploys"
ok "five sequential same-version deploys use unique directories"

# Failed candidate (not activated) must not become rollback target.
fail_rel="$(release_staging_path 1.0.54)"
mkdir -p "$fail_rel"
echo 'BROKEN' > "$fail_rel/docker-compose.yml"
echo 'ARDTT_DEPLOY_VERSION=1.0.54' > "$fail_rel/.env"
[ "$(readlink -f "$INSTALL_DIR/previous")" != "$(readlink -f "$fail_rel")" ] \
  || err "unactivated candidate became previous"
[ "$(readlink -f "$INSTALL_DIR/current")" != "$(readlink -f "$fail_rel")" ] \
  || err "unactivated candidate became current"
python3 "$ROOT/server/install-lib/atomic-pointer.py" validate "$INSTALL_DIR" previous >/dev/null \
  || err "previous invalid after failed candidate"
ok "incomplete staging is not a rollback target"

# Snapshot after activate of this attempt must not retarget previous onto the candidate.
DEPLOYMENT_ID="$(basename "$(pointer_real current)")"
prev_before="$(pointer_real previous)"
snapshot_current_to_previous
[ "$(pointer_real previous)" = "$prev_before" ] \
  || err "snapshot after activate retargeted previous onto this candidate"
ok "snapshot skip is by deploymentId, not version string"

# Static: install.sh must not rm -rf the staging path blindly if it is a pointer.
if grep -E 'rm -rf "\$release"' "$ROOT/server/install.sh"; then
  err "install.sh still rm -rf \$release (can delete previous)"
else
  ok "install.sh does not rm -rf staging blindly"
fi

# Directory name is deploymentId, not the version string.
p="$(release_staging_path 1.0.54)"
base="$(basename "$p")"
[ "$base" != "1.0.54" ] || err "staging dir must not be the version id"
[ "$base" != "1.0.54.new" ] || err "staging dir must not be version.new"
ok "staging path is a unique deploymentId, not the version"

if [ "$fail" -ne 0 ]; then
  echo "unique release tests failed" >&2
  exit 1
fi
echo "OK unique immutable releases"
exit 0
