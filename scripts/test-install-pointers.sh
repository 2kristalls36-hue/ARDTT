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

if [ "$fail" -ne 0 ]; then
  echo "pointer atomicity tests failed" >&2
  exit 1
fi
echo "OK install pointers / atomicity"
exit 0
