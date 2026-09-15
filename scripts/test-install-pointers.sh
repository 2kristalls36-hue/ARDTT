#!/usr/bin/env bash
# Pointer atomicity, crash leftovers, traversal, leftover .next, other-fs target.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

PY="$ROOT/server/install-lib/atomic-pointer.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
INSTALL_DIR="$TMP/opt"

mkrel() {
  local id="$1" body="${2:-compose-$1}"
  mkdir -p "$INSTALL_DIR/releases/$id"
  printf '%s\n' "$body" > "$INSTALL_DIR/releases/$id/docker-compose.yml"
  printf 'ARDTT_DEPLOY_VERSION=%s\n' "$id" > "$INSTALL_DIR/releases/$id/.env"
}

# --- fresh install: no current ---
mkdir -p "$INSTALL_DIR/releases"
python3 "$PY" recover "$INSTALL_DIR"
[ ! -e "$INSTALL_DIR/current" ] || err "recover must not invent current on empty install"
mkrel 1.0.54
python3 "$PY" replace "$INSTALL_DIR" current releases/1.0.54
[ -L "$INSTALL_DIR/current" ] || err "fresh current symlink"
[ -e "$INSTALL_DIR/current" ] || err "current missing after replace"
ok "fresh install pointer"

# --- A → B ---
mkrel 1.0.53 old
python3 "$PY" replace "$INSTALL_DIR" previous releases/1.0.53
python3 "$PY" replace "$INSTALL_DIR" current releases/1.0.54
[ "$(readlink -f "$INSTALL_DIR/current")" = "$(readlink -f "$INSTALL_DIR/releases/1.0.54")" ] || err "A→B current"
[ "$(readlink -f "$INSTALL_DIR/previous")" = "$(readlink -f "$INSTALL_DIR/releases/1.0.53")" ] || err "A→B previous"
ok "update A→B"

# --- leftover current.next ---
ln -s "releases/1.0.54" "$INSTALL_DIR/current.next.deadbeef"
python3 "$PY" recover "$INSTALL_DIR"
[ ! -e "$INSTALL_DIR/current.next.deadbeef" ] || err "stale current.next not cleaned"
[ -L "$INSTALL_DIR/current" ] || err "current lost while cleaning next"
ok "stale current.next cleaned"

# --- both pointers never missing after replace ---
python3 "$PY" replace "$INSTALL_DIR" current releases/1.0.54
python3 "$PY" replace "$INSTALL_DIR" previous releases/1.0.53
[ -e "$INSTALL_DIR/current" ] && [ -e "$INSTALL_DIR/previous" ] || err "pointer missing after replace"
ok "current and previous coexist"

# --- traversal / outside releases ---
mkdir -p "$TMP/evil"
echo 'x' > "$TMP/evil/docker-compose.yml"
if python3 "$PY" replace "$INSTALL_DIR" current /etc 2>/dev/null; then
  err "absolute /etc must be rejected"
else
  ok "reject absolute path"
fi
if python3 "$PY" relpath "$INSTALL_DIR" "$TMP/evil" 2>/dev/null; then
  err "relpath outside releases must fail"
else
  ok "reject other filesystem/dir as release"
fi
mkdir -p "$INSTALL_DIR/releases"
ln -sfn "$TMP/evil" "$INSTALL_DIR/releases/evil"
if python3 "$PY" replace "$INSTALL_DIR" current releases/evil 2>/dev/null; then
  err "symlink escape into evil must be rejected"
else
  ok "reject symlink escape from releases/"
fi
rm -f "$INSTALL_DIR/releases/evil"

# current must still be valid after rejected replaces
[ -L "$INSTALL_DIR/current" ] || err "current lost after rejected replace"
ok "rejected replace does not drop current"

# --- crash: current missing, DEPLOY_VERSION names the release ---
rm -f "$INSTALL_DIR/current"
printf '1.0.54\n' > "$INSTALL_DIR/DEPLOY_VERSION"
python3 "$PY" recover "$INSTALL_DIR"
[ "$(readlink -f "$INSTALL_DIR/current")" = "$(readlink -f "$INSTALL_DIR/releases/1.0.54")" ] \
  || err "recover did not recreate current from DEPLOY_VERSION"
ok "recover missing current from DEPLOY_VERSION"

# --- repeated replace of the same target is idempotent ---
python3 "$PY" replace "$INSTALL_DIR" current releases/1.0.54
python3 "$PY" replace "$INSTALL_DIR" current releases/1.0.54
ok "repeated deploy of same version"

# --- candidate path on another filesystem (tmp vs install) ---
if python3 "$PY" replace "$INSTALL_DIR" current "$TMP/evil" 2>/dev/null; then
  err "other-fs path must not become current"
else
  ok "other-fs candidate rejected"
fi

# --- P0.2: legacy current dir vs existing same-version release ---
MIG="$TMP/migrate"
mkdir -p "$MIG/releases/1.0.53" "$MIG/current"
printf 'STALE-SAME-VERSION\n' > "$MIG/releases/1.0.53/docker-compose.yml"
printf 'ARDTT_DEPLOY_VERSION=1.0.53\n' > "$MIG/releases/1.0.53/.env"
printf 'ACTIVE-LEGACY\n' > "$MIG/current/docker-compose.yml"
printf 'ARDTT_DEPLOY_VERSION=1.0.53\n' > "$MIG/current/.env"
echo extra-active > "$MIG/current/active.extra"
python3 "$PY" migrate "$MIG" current
[ -L "$MIG/current" ] || err "legacy current must become a symlink"
grep -qx 'ACTIVE-LEGACY' "$MIG/current/docker-compose.yml" \
  || err "migrate pointed at stale same-version release (lost ACTIVE-LEGACY)"
[ -f "$MIG/current/active.extra" ] || err "active extra file lost"
[ -f "$MIG/releases/1.0.53/docker-compose.yml" ] || err "pre-existing dest was deleted"
grep -qx 'STALE-SAME-VERSION' "$MIG/releases/1.0.53/docker-compose.yml" \
  || err "stale dest content changed"
ok "legacy current with colliding version keeps the active tree"

# Identical trees: migrate is allowed to point at dest; both trees remain until GC.
IDN="$TMP/ident"
mkdir -p "$IDN/releases/1.0.53" "$IDN/current"
printf 'SAME\n' > "$IDN/releases/1.0.53/docker-compose.yml"
printf 'ARDTT_DEPLOY_VERSION=1.0.53\n' > "$IDN/releases/1.0.53/.env"
printf 'SAME\n' > "$IDN/current/docker-compose.yml"
printf 'ARDTT_DEPLOY_VERSION=1.0.53\n' > "$IDN/current/.env"
python3 "$PY" migrate "$IDN" current
[ -L "$IDN/current" ] || err "identical legacy current must become a symlink"
grep -qx 'SAME' "$IDN/current/docker-compose.yml" || err "identical migrate lost content"
ok "legacy current identical to dest"

# No existing release: park into releases/.
FRESH="$TMP/fresh"
mkdir -p "$FRESH/current"
printf 'ONLY\n' > "$FRESH/current/docker-compose.yml"
printf 'ARDTT_DEPLOY_VERSION=1.0.52\n' > "$FRESH/current/.env"
python3 "$PY" migrate "$FRESH" current
[ -L "$FRESH/current" ] || err "fresh legacy current must become a symlink"
grep -qx 'ONLY' "$FRESH/current/docker-compose.yml" || err "fresh migrate lost content"
case "$(readlink -f "$FRESH/current")" in
  "$FRESH"/releases/*) ok "fresh legacy current parked under releases/" ;;
  *) err "fresh migrate did not park under releases/: $(readlink -f "$FRESH/current")" ;;
esac

# Crash after park, before symlink: recover must restore current.
CRASH="$TMP/crash"
mkdir -p "$CRASH/state" "$CRASH/releases" "$CRASH/current"
printf 'PARK-ME\n' > "$CRASH/current/docker-compose.yml"
printf 'ARDTT_DEPLOY_VERSION=1.0.51\n' > "$CRASH/current/.env"
# Simulate: intent written and directory renamed, pointer not created.
python3 - "$PY" "$CRASH" <<'PY'
import os, json, shutil, sys, importlib.util
py, crash = sys.argv[1], sys.argv[2]
spec = importlib.util.spec_from_file_location("ap", py)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
dest = os.path.join(crash, "releases", "d-parked")
os.rename(os.path.join(crash, "current"), dest)
intent = {"name": "current", "destRel": "releases/d-parked", "phase": "parked"}
os.makedirs(os.path.join(crash, "state"), exist_ok=True)
open(os.path.join(crash, "state", "migrate.json"), "w", encoding="utf-8").write(json.dumps(intent))
PY
python3 "$PY" recover "$CRASH"
[ -L "$CRASH/current" ] || err "recover after park did not recreate current"
grep -qx 'PARK-ME' "$CRASH/current/docker-compose.yml" || err "recover after park lost tree"
ok "recover completes migrate after park"

if [ "$fail" -ne 0 ]; then
  echo "pointer atomicity tests failed" >&2
  exit 1
fi
echo "OK install pointers / atomicity"
exit 0
