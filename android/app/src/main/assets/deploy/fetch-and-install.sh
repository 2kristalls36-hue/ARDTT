#!/usr/bin/env bash
# Runs on the VPS: resolve the ARDTT server release on GitHub, download only
# what this host lacks, verify SHA-256, stage a package and run install.sh.
# The phone only SSHs and starts this script — it does not download or SFTP
# the multi‑MB payload.
#
# Partial deploy (release with ardtt-server-<ver>-linux-<arch>.index.json):
#   host files   — always (install.sh, Compose, install-lib; ~100 KB),
#   Docker Engine — only when the host has no docker CLI at all,
#   Compose CLI   — only when neither the host plugin nor /opt/ardtt/bin has one,
#   image layers  — only diff IDs missing from <install>/cache/layers
#                   (the cache is seeded from the already loaded image).
# Releases without an index fall back to the full ardtt-server-*.tar.gz.
#
# Protocol: same ARDTT_PROGRESS| / ARDTT_WARN| / ARDTT_ERROR| / ARDTT_DONE| as install.sh.
#
# Env (required):
#   ARDTT_PUBLIC_HOST
# Optional:
#   ARDTT_DEPLOY_VERSION   — pin stack version; empty = newest published stack
#   ARDTT_GITHUB_REPO      — owner/name (default 2kristalls36-hue/ARDTT)
#   ARDTT_GITHUB_API       — API base (default https://api.github.com)
#   ARDTT_GITHUB_TOKEN     — optional token; lifts the anonymous API rate limit
#   ARDTT_FETCH_MODE       — auto (default) | partial | full
#   ARDTT_LAYER_CACHE      — 1 (default): keep gzip layers in <install>/cache/layers; 0: no persistent cache
#   ARDTT_ROLE, ports, cascade_* — forwarded to install.sh
#   ARDTT_DISK_CLEANUP=1 — safe reclaim (logs/apt/headers/ARDTT leftovers) if disk preflight fails
set -euo pipefail

die() {
  local msg="$*"
  printf 'ARDTT_ERROR|%s\n' "$msg"
  exit 1
}

prog() {
  local frac="$1" step="$2"
  printf 'ARDTT_PROGRESS|%s|%s\n' "$frac" "$step"
}

info() { printf 'ARDTT_INFO|%s\n' "$*"; }
warn() { printf 'ARDTT_WARN|%s\n' "$*"; }
mb() { awk -v b="${1:-0}" 'BEGIN { printf "%.1f", b/1048576 }'; }

REPO="${ARDTT_GITHUB_REPO:-2kristalls36-hue/ARDTT}"
API="${ARDTT_GITHUB_API:-https://api.github.com}"
DL_HOST="${ARDTT_GITHUB_DOWNLOAD:-https://github.com}"
INSTALL_DIR="${ARDTT_INSTALL_DIR:-/opt/ardtt}"
INCOMING="${INSTALL_DIR}/incoming"
STAGING="${INSTALL_DIR}/staging"
UA="${ARDTT_USER_AGENT:-ARDTT-VPS-fetch/2.0}"
FETCH_MODE="${ARDTT_FETCH_MODE:-auto}"
LAYER_CACHE="${ARDTT_LAYER_CACHE:-1}"
SELF_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd || echo .)"

command -v python3 >/dev/null 2>&1 || die "PYTHON_MISSING|нужен python3 на VPS"
command -v curl >/dev/null 2>&1 || die "CURL_MISSING|нужен curl на VPS для загрузки с GitHub Releases"
command -v sha256sum >/dev/null 2>&1 || die "SHA256SUM_MISSING|нужен sha256sum (coreutils) на VPS"
case "$FETCH_MODE" in auto|partial|full) ;; *) die "BAD_FETCH_MODE|ARDTT_FETCH_MODE=${FETCH_MODE} (auto|partial|full)" ;; esac

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'amd64' ;;
    aarch64|arm64) printf 'arm64' ;;
    *) die "UNSUPPORTED_ARCH|нужен linux amd64 или arm64 (uname -m=$(uname -m))" ;;
  esac
}

API_HTTP_CODE=""
api_get() {
  local url="$1" out="$2" code
  local hdr=(-H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28')
  if [ -n "${ARDTT_GITHUB_TOKEN:-}" ]; then
    hdr+=(-H "Authorization: Bearer ${ARDTT_GITHUB_TOKEN}")
  fi
  code="$(curl -sSL -A "$UA" --connect-timeout 20 --retry 2 --retry-delay 2 "${hdr[@]}" \
    -w '%{http_code}' -o "$out" "$url" 2>/dev/null || true)"
  API_HTTP_CODE="$code"
  [ "$code" = "200" ]
}

# download <url> <dest> [expected_size]: resumable, stall-guarded, atomic rename.
download() {
  local url="$1" dest="$2" size="${3:-0}" part have rc=1 attempt
  part="${dest}.partial"
  mkdir -p "$(dirname "$dest")"
  if [ -f "$part" ] && [ "${size:-0}" -gt 0 ] 2>/dev/null; then
    have="$(stat -c %s "$part" 2>/dev/null || echo 0)"
    if [ "$have" -eq "$size" ]; then
      mv -f "$part" "$dest"
      return 0
    fi
    [ "$have" -gt "$size" ] && rm -f "$part"
  fi
  for attempt in 1 2; do
    local resume=()
    [ -s "$part" ] && resume=(-C -)
    # ${arr[@]+"${arr[@]}"}: empty-array safe under set -u on bash < 4.4 (CentOS 7 VPS).
    if curl -fsSL -A "$UA" --connect-timeout 20 --retry 3 --retry-delay 3 \
        --speed-limit 1024 --speed-time 90 ${resume[@]+"${resume[@]}"} -o "$part" "$url"; then
      mv -f "$part" "$dest"
      return 0
    fi
    rc=$?
    # Range not honoured / stale partial: restart from scratch once.
    rm -f "$part"
    [ "$attempt" = 1 ] && sleep 2
  done
  return "$rc"
}

# verify_sha <file> <sha256> <label>: deletes the file on mismatch so a retry re-downloads.
verify_sha() {
  local f="$1" want got
  want="$(printf '%s' "$2" | tr 'A-F' 'a-f' | tr -d '[:space:]')"
  [ "${#want}" -eq 64 ] || { rm -f "$f"; die "SHA256_MISSING|нет доверенной SHA-256 для ${3}"; }
  got="$(sha256sum "$f" | awk '{print $1}')"
  if [ "$got" != "$want" ]; then
    rm -f "$f"
    die "SHA256_MISMATCH|${3} не совпал с релизом (ожидали ${want}, получили ${got})"
  fi
}

sha_from_sums() {
  local sums="$1" name="$2"
  [ -f "$sums" ] || return 0
  NAME="$name" python3 - "$sums" <<'PY'
import os, sys
name = os.environ["NAME"].lower()
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    parts = line.strip().split()
    if len(parts) >= 2 and parts[-1].rsplit("/", 1)[-1].lower() == name:
        print(parts[0].lower().removeprefix("sha256:"))
        break
PY
}

# Safe extraction: never absolute paths / .. / links. Prefer the packaged script.
safe_extract() {
  local archive="$1" dest="$2" py=""
  for py in "$STAGING/scripts/safe-extract-package.py" "$SELF_DIR/scripts/safe-extract-package.py" \
            "$INSTALL_DIR/current/scripts/safe-extract-package.py"; do
    [ -f "$py" ] && break
    py=""
  done
  if [ -n "$py" ]; then
    python3 "$py" "$archive" "$dest"
    return $?
  fi
  python3 - "$archive" "$dest" <<'PY'
import gzip, os, stat, sys, tarfile
src, dest = sys.argv[1], sys.argv[2]
os.makedirs(dest, exist_ok=True)
dest = os.path.abspath(dest)
with gzip.open(src, "rb") as gz, tarfile.open(fileobj=gz, mode="r|") as tar:
    for m in tar:
        n = m.name.replace("\\", "/")
        if n.startswith("/") or n.startswith("\\") or "/../" in "/"+n or n.endswith("/..") or n.startswith("../"):
            raise SystemExit("unsafe path "+n)
        if m.issym() or m.islnk():
            raise SystemExit("link not allowed "+n)
        target = os.path.abspath(os.path.join(dest, m.name))
        if not (target == dest or target.startswith(dest + os.sep)):
            raise SystemExit("escapes "+n)
        tar.extract(m, path=dest, set_attrs=False)
for root, dirs, files in os.walk(dest):
    for name in dirs + files:
        p = os.path.join(root, name)
        if stat.S_ISLNK(os.lstat(p).st_mode):
            raise SystemExit("symlink "+p)
print("extracted", dest)
PY
}

ARCH="$(detect_arch)"
WANT_VER="${ARDTT_DEPLOY_VERSION:-}"
prog 0.02 "Определение пакета (${ARCH}${WANT_VER:+, версия $WANT_VER})"

mkdir -p "$INCOMING" "$STAGING"
chmod 755 "$INSTALL_DIR" "$INCOMING" 2>/dev/null || true

# One fetch per install dir: a second phone/session must not clobber staging.
exec 8>"$INSTALL_DIR/fetch.lock"
flock -n 8 || die "BUSY|другая загрузка/установка ARDTT уже идёт (${INSTALL_DIR}/fetch.lock)"

TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD" 2>/dev/null || true' EXIT

# ---------------------------------------------------------------------------
# 1. Resolve the release: API (all releases, per-asset digest) or, when the
#    API is unreachable / rate limited, SHA256SUMS-server.txt of the latest release.
# ---------------------------------------------------------------------------
RESOLVE_PY="$TMPD/resolve.py"
cat > "$RESOLVE_PY" <<'PY'
import json, os, re, sys

mode, src = sys.argv[1], sys.argv[2]
want = os.environ.get("WANT_VER", "").strip()
arch = os.environ["ARCH"]
repo = os.environ["REPO"]
dl_host = os.environ.get("DL_HOST", "https://github.com").rstrip("/")
full_re = re.compile(r"^ardtt-server-(\d+\.\d+\.\d+)-linux-(amd64|arm64)\.tar\.gz$", re.I)
index_re = re.compile(r"^ardtt-server-(\d+\.\d+\.\d+)-linux-(amd64|arm64)\.index\.json$", re.I)

def vkey(v):
    return tuple(int(x) for x in v.split("."))

cands = {}  # version -> dict

def note(ver, kind, asset):
    c = cands.setdefault(ver, {"version": ver, "tag": "", "full": None, "index": None, "assets": {}})
    if c[kind] is None:
        c[kind] = asset

if mode == "api":
    releases = json.load(open(src, encoding="utf-8"))
    if not isinstance(releases, list):
        raise SystemExit("bad releases payload")
    for rel in releases:
        if rel.get("draft"):
            continue
        tag = (rel.get("tag_name") or "").strip()
        by_name = {}
        for a in rel.get("assets") or []:
            name = a.get("name") or ""
            url = (a.get("browser_download_url") or "").strip()
            if not name or not url:
                continue
            by_name[name] = {
                "name": name, "url": url,
                "sha256": (a.get("digest") or "").removeprefix("sha256:").lower(),
                "size": int(a.get("size") or 0),
            }
        seen_here = set()
        for name, asset in by_name.items():
            m = full_re.match(name) or index_re.match(name)
            if not m or m.group(2).lower() != arch:
                continue
            ver = m.group(1)
            kind = "full" if full_re.match(name) else "index"
            if ver in cands and cands[ver]["tag"] and cands[ver]["tag"] != tag:
                continue  # already seen in a newer release
            note(ver, kind, asset)
            cands[ver]["tag"] = tag
            seen_here.add(ver)
        for ver in seen_here:
            c = cands[ver]
            for name, asset in by_name.items():
                c["assets"].setdefault(name, asset)
else:
    # SHA256SUMS-server.txt of one release (latest): "<sha>  <name>" lines.
    tag = os.environ.get("DL_TAG", "latest")
    base = f"{dl_host}/{repo}/releases/latest/download" if tag == "latest" else f"{dl_host}/{repo}/releases/download/{tag}"
    by_name = {}
    for line in open(src, encoding="utf-8", errors="replace"):
        parts = line.strip().split()
        if len(parts) < 2 or parts[0].startswith("#"):
            continue
        name = parts[-1].rsplit("/", 1)[-1]
        by_name[name] = {"name": name, "url": f"{base}/{name}", "sha256": parts[0].lower().removeprefix("sha256:"), "size": 0}
    for name, asset in by_name.items():
        m = full_re.match(name) or index_re.match(name)
        if not m or m.group(2).lower() != arch:
            continue
        note(m.group(1), "full" if full_re.match(name) else "index", asset)
        cands[m.group(1)]["tag"] = tag
    for c in cands.values():
        c["assets"] = dict(by_name)

if want:
    picked = cands.get(want)
    if not picked:
        raise SystemExit(f"no ardtt-server-{want}-linux-{arch} package in releases")
else:
    if not cands:
        raise SystemExit(f"no ardtt-server-*-linux-{arch} package in releases")
    picked = cands[max(cands, key=vkey)]

def q(s):
    return "'" + str(s if s is not None else "").replace("'", "'\"'\"'") + "'"

assets = picked["assets"]
full, index = picked["full"], picked["index"]
sums = None
for n in ("SHA256SUMS-server.txt", "SHA256SUMS.txt", "SHA256SUMS"):
    for name, a in assets.items():
        if name.lower() == n.lower():
            sums = a["url"]
            break
    if sums:
        break
def sib(a):
    if not a:
        return ""
    s = assets.get(a["name"] + ".sha256")
    return s["url"] if s else ""
dl_base = ""
for a in (index, full):
    if a and a["url"]:
        dl_base = a["url"].rsplit("/", 1)[0]
        break
out = [
    "PKG_VER=" + q(picked["version"]),
    "PKG_TAG=" + q(picked["tag"]),
    "FULL_NAME=" + q(full["name"] if full else ""),
    "FULL_URL=" + q(full["url"] if full else ""),
    "FULL_SHA=" + q(full["sha256"] if full else ""),
    "FULL_SIZE=" + q(full["size"] if full else 0),
    "FULL_SIB_URL=" + q(sib(full)),
    "INDEX_NAME=" + q(index["name"] if index else ""),
    "INDEX_URL=" + q(index["url"] if index else ""),
    "INDEX_SHA=" + q(index["sha256"] if index else ""),
    "INDEX_SIZE=" + q(index["size"] if index else 0),
    "INDEX_SIB_URL=" + q(sib(index)),
    "SUMS_URL=" + q(sums or ""),
    "DL_BASE=" + q(dl_base),
]
print("\n".join(out))
PY

LIST_JSON="$TMPD/releases.json"
ENV_SNIPPET="$TMPD/resolved.env"
RESOLVE_ERR=""
if api_get "${API}/repos/${REPO}/releases?per_page=40" "$LIST_JSON"; then
  if ! WANT_VER="$WANT_VER" ARCH="$ARCH" REPO="$REPO" DL_HOST="$DL_HOST" \
      python3 "$RESOLVE_PY" api "$LIST_JSON" > "$ENV_SNIPPET" 2>"$TMPD/resolve.err"; then
    RESOLVE_ERR="$(tr '\n' ' ' < "$TMPD/resolve.err")"
    die "PACKAGE_RESOLVE|не найден пакет ardtt-server для ${ARCH}${WANT_VER:+ версии $WANT_VER} в релизах ${REPO}: ${RESOLVE_ERR}"
  fi
else
  # API down or anonymous rate limit (403/429): read the latest release's checksum list directly.
  warn "GitHub API недоступен (HTTP ${API_HTTP_CODE:-нет ответа}) — читаем SHA256SUMS-server.txt последнего релиза"
  SUMS_LATEST="$TMPD/sums.txt"
  if ! download "${DL_HOST}/${REPO}/releases/latest/download/SHA256SUMS-server.txt" "$SUMS_LATEST" 2>/dev/null; then
    die "GITHUB_UNREACHABLE|VPS не смог получить список релизов ${REPO} (HTTP ${API_HTTP_CODE:-нет ответа}) и SHA256SUMS-server.txt последнего релиза. Нужен исходящий HTTPS к api.github.com / github.com."
  fi
  if ! WANT_VER="$WANT_VER" ARCH="$ARCH" REPO="$REPO" DL_HOST="$DL_HOST" DL_TAG=latest \
      python3 "$RESOLVE_PY" sums "$SUMS_LATEST" > "$ENV_SNIPPET" 2>"$TMPD/resolve.err"; then
    RESOLVE_ERR="$(tr '\n' ' ' < "$TMPD/resolve.err")"
    die "PACKAGE_RESOLVE|не найден пакет ardtt-server для ${ARCH}${WANT_VER:+ версии $WANT_VER} в последнем релизе ${REPO} (API недоступен): ${RESOLVE_ERR}"
  fi
fi
# shellcheck disable=SC1090
. "$ENV_SNIPPET"

SUMS_FILE=""
ensure_sums() {
  [ -n "$SUMS_FILE" ] && return 0
  [ -n "${SUMS_URL:-}" ] || return 1
  SUMS_FILE="$TMPD/SHA256SUMS-server.txt"
  download "$SUMS_URL" "$SUMS_FILE" 2>/dev/null || { SUMS_FILE=""; return 1; }
}

# resolve_asset_sha <current sha> <name> <sibling url> -> echoes sha (may be empty)
resolve_asset_sha() {
  local sha="$1" name="$2" sib="$3" body
  if [ -z "$sha" ] && ensure_sums; then
    sha="$(sha_from_sums "$SUMS_FILE" "$name")"
  fi
  if [ -z "$sha" ] && [ -n "$sib" ]; then
    body="$TMPD/sib.$$"
    if download "$sib" "$body" 2>/dev/null; then
      sha="$(python3 -c 'import sys; t=open(sys.argv[1],encoding="utf-8",errors="replace").read().split(); print((t[0] if t else "").lower().removeprefix("sha256:"))' "$body")"
    fi
    rm -f "$body"
  fi
  printf '%s' "$sha"
}

USE_INDEX=0
case "$FETCH_MODE" in
  auto) [ -n "$INDEX_NAME" ] && USE_INDEX=1 ;;
  partial) [ -n "$INDEX_NAME" ] || die "INDEX_MISSING|в релизе ${PKG_TAG:-?} нет ${PKG_VER} index для ${ARCH}; ARDTT_FETCH_MODE=full для монолитного архива" ; USE_INDEX=1 ;;
  full) USE_INDEX=0 ;;
esac
if [ "$USE_INDEX" = 0 ] && [ -z "$FULL_NAME" ]; then
  die "PACKAGE_RESOLVE|у релиза ${PKG_TAG:-?} нет ardtt-server-${PKG_VER}-linux-${ARCH}.tar.gz"
fi

# 1-vCPU VPS: Docker rejects cpus: 2.0 from older package defaults.
if [ -z "${ARDTT_CPUS:-}" ]; then
  _n="$(nproc 2>/dev/null || echo 1)"
  if [ "${_n:-1}" -lt 2 ] 2>/dev/null; then
    export ARDTT_CPUS=1.0
    export ARDTT_MEM_LIMIT="${ARDTT_MEM_LIMIT:-512m}"
  else
    export ARDTT_CPUS=2.0
  fi
fi

CACHE_DIR="${INSTALL_DIR}/cache/layers"
if [ "$LAYER_CACHE" = "0" ]; then
  CACHE_DIR="${STAGING}/.layer-cache"
fi

run_install() {
  export ARDTT_PKG_DIR="$STAGING"
  export ARDTT_DEPLOY_VERSION="${ARDTT_DEPLOY_VERSION:-$PKG_VER}"
  export ARDTT_INSTALL_DIR="$INSTALL_DIR"
  if [ "$LAYER_CACHE" != "0" ]; then
    export ARDTT_LAYER_CACHE_DIR="$CACHE_DIR"
  fi
  prog 0.35 "Запуск install.sh (${ARDTT_DEPLOY_VERSION})"
  # install.sh emits its own 0..1 progress; remap is left to the phone if needed.
  bash "$STAGING/install.sh"
}

# ---------------------------------------------------------------------------
# 2a. Full archive (release without index, or ARDTT_FETCH_MODE=full).
# ---------------------------------------------------------------------------
if [ "$USE_INDEX" = 0 ]; then
  PKG_SHA="$(resolve_asset_sha "$FULL_SHA" "$FULL_NAME" "$FULL_SIB_URL")"
  [ -n "$PKG_SHA" ] && [ "${#PKG_SHA}" -eq 64 ] || die "SHA256_MISSING|нет доверенной SHA-256 для ${FULL_NAME} (digest / SHA256SUMS релиза)"
  PKG_PATH="${INCOMING}/${FULL_NAME}"
  if [ -f "$PKG_PATH" ] && [ "$(sha256sum "$PKG_PATH" | awk '{print $1}')" = "$PKG_SHA" ]; then
    info "пакет ${FULL_NAME} уже в incoming и совпадает по SHA-256 — загрузка пропущена"
  else
    rm -f "$PKG_PATH"
    prog 0.08 "Загрузка ${FULL_NAME} ($(mb "$FULL_SIZE") МБ) с GitHub Releases (${PKG_TAG:-?})"
    download "$FULL_URL" "$PKG_PATH" "$FULL_SIZE" || die "DOWNLOAD_FAILED|не удалось скачать ${FULL_URL}"
    prog 0.22 "Проверка SHA-256"
    verify_sha "$PKG_PATH" "$PKG_SHA" "пакет ${FULL_NAME}"
  fi

  prog 0.28 "Распаковка пакета"
  rm -rf "$STAGING"
  mkdir -p "$STAGING"
  safe_extract "$PKG_PATH" "$STAGING" || die "PACKAGE_INVALID|распаковка ${FULL_NAME} отклонена"
  [ -f "$STAGING/install.sh" ] || die "PACKAGE_INVALID|в архиве нет install.sh"
  [ -f "$STAGING/manifest.json" ] || die "PACKAGE_INVALID|в архиве нет manifest.json"
  [ -f "$STAGING/fetch-and-install.sh" ] && chmod 755 "$STAGING/fetch-and-install.sh" || true

  export ARDTT_PACKAGE="$PKG_PATH"
  export ARDTT_PACKAGE_SHA256="$PKG_SHA"
  run_install
  rm -f "${PKG_PATH}.partial" 2>/dev/null || true
  exit 0
fi

# ---------------------------------------------------------------------------
# 2b. Partial deploy from the index.
# ---------------------------------------------------------------------------
INDEX_SHA="$(resolve_asset_sha "$INDEX_SHA" "$INDEX_NAME" "$INDEX_SIB_URL")"
[ -n "$INDEX_SHA" ] && [ "${#INDEX_SHA}" -eq 64 ] || die "SHA256_MISSING|нет доверенной SHA-256 для ${INDEX_NAME} (digest / SHA256SUMS релиза)"
INDEX_PATH="${INCOMING}/${INDEX_NAME}"
rm -f "$INDEX_PATH"
prog 0.05 "Индекс ${INDEX_NAME} (${PKG_TAG:-?})"
download "$INDEX_URL" "$INDEX_PATH" "$INDEX_SIZE" || die "DOWNLOAD_FAILED|не удалось скачать ${INDEX_URL}"
verify_sha "$INDEX_PATH" "$INDEX_SHA" "индекс ${INDEX_NAME}"

INDEX_ENV="$TMPD/index.env"
PKG_VER="$PKG_VER" ARCH="$ARCH" python3 - "$INDEX_PATH" "$INDEX_ENV" <<'PY'
import json, os, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
if d.get("format") != "ardtt-server-index-v1":
    raise SystemExit("unsupported index format %r" % d.get("format"))
if d.get("arch") != os.environ["ARCH"]:
    raise SystemExit("index arch %s != host %s" % (d.get("arch"), os.environ["ARCH"]))
if d.get("deployVersion") != os.environ["PKG_VER"]:
    raise SystemExit("index version %s != resolved %s" % (d.get("deployVersion"), os.environ["PKG_VER"]))
def q(s):
    return "'" + str(s if s is not None else "").replace("'", "'\"'\"'") + "'"
h = d["hostfiles"]; img = d["image"]; eng = d.get("engine") or {}; comp = d.get("compose") or {}
lines = [
    "HOST_ASSET=" + q(h["asset"]), "HOST_SHA=" + q(h["sha256"]), "HOST_SIZE=" + q(h.get("size", 0)),
    "ENGINE_ASSET=" + q(eng.get("asset", "")), "ENGINE_SHA=" + q(eng.get("sha256", "")), "ENGINE_SIZE=" + q(eng.get("size", 0)),
    "ENGINE_VER=" + q(eng.get("version", "")),
    "COMPOSE_ASSET=" + q(comp.get("asset", "")), "COMPOSE_SHA=" + q(comp.get("sha256", "")), "COMPOSE_SIZE=" + q(comp.get("size", 0)),
    "IMAGE_TAG=" + q(img.get("tag", "")), "LAYER_COUNT=" + q(len(img.get("layers") or [])),
    "LAYERS_GZ_TOTAL=" + q(img.get("totalGzSize", 0)),
]
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
# shellcheck disable=SC1090
. "$INDEX_ENV"
[ -n "$DL_BASE" ] || DL_BASE="${INDEX_URL%/*}"

# Host files (install.sh, Compose, install-lib, layout, helpers): always fetched, tiny.
prog 0.07 "Файлы установщика ${HOST_ASSET} ($(mb "$HOST_SIZE") МБ)"
HOST_PATH="${INCOMING}/${HOST_ASSET}"
rm -f "$HOST_PATH"
download "${DL_BASE}/${HOST_ASSET}" "$HOST_PATH" "$HOST_SIZE" || die "DOWNLOAD_FAILED|не удалось скачать ${DL_BASE}/${HOST_ASSET}"
verify_sha "$HOST_PATH" "$HOST_SHA" "файлы установщика ${HOST_ASSET}"
rm -rf "$STAGING"
mkdir -p "$STAGING"
safe_extract "$HOST_PATH" "$STAGING" || die "PACKAGE_INVALID|распаковка ${HOST_ASSET} отклонена"
for f in install.sh manifest.json docker-compose.yml images/layout.json scripts/layer-cache.py scripts/assemble-docker-save.py; do
  [ -f "$STAGING/$f" ] || die "PACKAGE_INVALID|в ${HOST_ASSET} нет ${f}"
done
chmod 755 "$STAGING/install.sh" "$STAGING/fetch-and-install.sh" 2>/dev/null || true
# Every extracted file must match the index (the index is the trust root here).
python3 - "$INDEX_PATH" "$STAGING" <<'PY' || die "PACKAGE_INVALID|файлы установщика не совпали с индексом"
import hashlib, json, os, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
stage = sys.argv[2]
files = d["hostfiles"].get("files") or {}
bad = []
for rel, want in files.items():
    p = os.path.join(stage, rel)
    if not os.path.isfile(p):
        bad.append(rel + " (missing)")
        continue
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != want.lower():
        bad.append(rel)
for root, dirs, names in os.walk(stage):
    for n in names:
        rel = os.path.relpath(os.path.join(root, n), stage)
        if rel not in files:
            bad.append(rel + " (not in index)")
if bad:
    raise SystemExit("hostfiles mismatch: " + ", ".join(bad[:8]))
PY
rm -f "$HOST_PATH"
LC_PY="$STAGING/scripts/layer-cache.py"

# Docker Engine: only when the host has no docker CLI at all (a dead foreign
# daemon or our own unit is handled by install.sh without the tarball).
NEED_ENGINE=0
if ! command -v docker >/dev/null 2>&1; then
  if [ -n "$ENGINE_ASSET" ]; then
    NEED_ENGINE=1
  else
    warn "на VPS нет docker, а в релизе нет отдельного Engine — install.sh сообщит DOCKER_MISSING"
  fi
fi
# Compose: host plugin or our /opt/ardtt/bin copy with the same SHA-256 is enough.
NEED_COMPOSE=0
if [ -n "$COMPOSE_ASSET" ]; then
  if docker compose version >/dev/null 2>&1 || command -v docker-compose >/dev/null 2>&1; then
    NEED_COMPOSE=0
  elif [ -x "${INSTALL_DIR}/bin/docker-compose" ] && \
       [ "$(sha256sum "${INSTALL_DIR}/bin/docker-compose" | awk '{print $1}')" = "$COMPOSE_SHA" ]; then
    NEED_COMPOSE=0
  else
    NEED_COMPOSE=1
  fi
fi

if [ "$NEED_ENGINE" = 1 ]; then
  prog 0.09 "Docker Engine ${ENGINE_VER:-} из релиза ($(mb "$ENGINE_SIZE") МБ) — на VPS его нет"
  ENGINE_PATH="${INCOMING}/${ENGINE_ASSET}"
  if ! { [ -f "$ENGINE_PATH" ] && [ "$(sha256sum "$ENGINE_PATH" | awk '{print $1}')" = "$ENGINE_SHA" ]; }; then
    rm -f "$ENGINE_PATH"
    download "${DL_BASE}/${ENGINE_ASSET}" "$ENGINE_PATH" "$ENGINE_SIZE" || die "DOWNLOAD_FAILED|не удалось скачать ${DL_BASE}/${ENGINE_ASSET}"
    verify_sha "$ENGINE_PATH" "$ENGINE_SHA" "Docker Engine ${ENGINE_ASSET}"
  fi
  mkdir -p "$STAGING/vendor"
  ln -f "$ENGINE_PATH" "$STAGING/vendor/docker.tgz" 2>/dev/null || cp -f "$ENGINE_PATH" "$STAGING/vendor/docker.tgz"
else
  info "Docker Engine на VPS уже есть — $(mb "$ENGINE_SIZE") МБ не скачиваем"
fi

if [ "$NEED_COMPOSE" = 1 ]; then
  prog 0.10 "Compose CLI из релиза ($(mb "$COMPOSE_SIZE") МБ) — на VPS его нет"
  COMPOSE_PATH="${INCOMING}/${COMPOSE_ASSET}"
  if ! { [ -f "$COMPOSE_PATH" ] && [ "$(sha256sum "$COMPOSE_PATH" | awk '{print $1}')" = "$COMPOSE_SHA" ]; }; then
    rm -f "$COMPOSE_PATH"
    download "${DL_BASE}/${COMPOSE_ASSET}" "$COMPOSE_PATH" "$COMPOSE_SIZE" || die "DOWNLOAD_FAILED|не удалось скачать ${DL_BASE}/${COMPOSE_ASSET}"
    verify_sha "$COMPOSE_PATH" "$COMPOSE_SHA" "Compose ${COMPOSE_ASSET}"
  fi
  mkdir -p "$STAGING/bin"
  ln -f "$COMPOSE_PATH" "$STAGING/bin/docker-compose" 2>/dev/null || cp -f "$COMPOSE_PATH" "$STAGING/bin/docker-compose"
  chmod 755 "$STAGING/bin/docker-compose"
elif [ -n "$COMPOSE_ASSET" ]; then
  info "Compose уже есть на VPS — $(mb "$COMPOSE_SIZE") МБ не скачиваем"
fi

# Image layers: reuse the local cache, seed it from the loaded image, download the rest.
LAYOUT="$STAGING/images/layout.json"
mkdir -p "$CACHE_DIR"
chmod 700 "$CACHE_DIR" 2>/dev/null || true
PLAN="$(python3 "$LC_PY" plan "$LAYOUT" "$CACHE_DIR")" || die "PACKAGE_INVALID|layout.json не прочитан"
cached_now="$(printf '%s\n' "$PLAN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["cachedCount"])')"
# Cold cache (first partial update after a full install, or ARDTT_LAYER_CACHE=0):
# pull the layers the loaded image already has out of `docker save` instead of
# the network. A warm cache is only ever missing genuinely new layers, so the
# (CPU-heavy) save is skipped then.
if [ "$cached_now" -eq 0 ] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  prog 0.11 "Слои образа: посев кэша из уже загруженного образа"
  seed_refs=()
  if [ -f "$INSTALL_DIR/instance.json" ]; then
    prev_tag="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("imageTag") or "")' "$INSTALL_DIR/instance.json" 2>/dev/null || true)"
    [ -n "$prev_tag" ] && seed_refs+=("$prev_tag")
  fi
  python3 "$LC_PY" seed "$CACHE_DIR" --want "$LAYOUT" ${seed_refs[@]+"${seed_refs[@]}"} 2>/dev/null \
    | sed 's/^/ARDTT_INFO|/' || warn "посев кэша слоёв из docker save не удался — качаем слои"
  PLAN="$(python3 "$LC_PY" plan "$LAYOUT" "$CACHE_DIR")" || die "PACKAGE_INVALID|layout.json не прочитан"
fi

LAYER_LIST="$TMPD/layers.txt"
PLAN_FILE="$TMPD/plan.json"
printf '%s\n' "$PLAN" > "$PLAN_FILE"
python3 - "$INDEX_PATH" "$LAYER_LIST" "$PLAN_FILE" <<'PY'
import json, sys
plan = json.load(open(sys.argv[3], encoding="utf-8"))
index = json.load(open(sys.argv[1], encoding="utf-8"))
by_diff = {l["diffId"].removeprefix("sha256:"): l for l in index["image"]["layers"]}
lines = []
for l in plan["layers"]:
    if l["cached"]:
        continue
    a = by_diff.get(l["diffId"])
    if not a:
        raise SystemExit("index has no asset for layer " + l["diffId"][:16])
    lines.append("|".join([str(l["index"]), l["diffId"], a["asset"], a["sha256"], str(a.get("gzSize", 0))]))
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(lines) + ("\n" if lines else ""))
PY
CACHED_COUNT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["cachedCount"])' "$PLAN_FILE")"
MISSING_GZ="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["missingGzSize"])' "$PLAN_FILE")"
info "слои образа: ${LAYER_COUNT} всего, ${CACHED_COUNT} в кэше, скачать $(( LAYER_COUNT - CACHED_COUNT )) ($(mb "$MISSING_GZ") МБ из $(mb "$LAYERS_GZ_TOTAL") МБ)"

done_bytes=0
done_n=0
mkdir -p "$INCOMING/layers"
while IFS='|' read -r lidx ldiff lasset lsha lsize; do
  [ -n "$lasset" ] || continue
  done_n=$((done_n + 1))
  frac="$(awk -v d="$done_bytes" -v t="${MISSING_GZ:-1}" 'BEGIN { if (t < 1) t = 1; printf "%.3f", 0.12 + 0.18 * d / t }')"
  prog "$frac" "Слой $((lidx + 1))/${LAYER_COUNT} ($(mb "$lsize") МБ; скачано $(mb "$done_bytes") из $(mb "$MISSING_GZ") МБ)"
  lpath="$INCOMING/layers/$lasset"
  rm -f "$lpath"
  download "${DL_BASE}/${lasset}" "$lpath" "$lsize" || die "DOWNLOAD_FAILED|не удалось скачать слой ${DL_BASE}/${lasset}"
  verify_sha "$lpath" "$lsha" "слой ${lasset}"
  python3 "$LC_PY" store "$CACHE_DIR" "$lpath" "$ldiff" >/dev/null || die "PACKAGE_INVALID|слой ${lasset} не совпал с diff ID образа"
  done_bytes=$((done_bytes + lsize))
done < "$LAYER_LIST"
rm -rf "$INCOMING/layers" 2>/dev/null || true

prog 0.31 "Сборка пакета из кэша слоёв"
python3 "$LC_PY" link "$LAYOUT" "$CACHE_DIR" "$STAGING/images" | sed 's/^/ARDTT_INFO|/' \
  || die "PACKAGE_INVALID|не все слои образа доступны после загрузки"

export ARDTT_PACKAGE_INDEX="$INDEX_PATH"
export ARDTT_PACKAGE_INDEX_SHA256="$INDEX_SHA"
unset ARDTT_PACKAGE ARDTT_PACKAGE_SHA256
run_install

# Success: drop one-off downloads and layers no longer referenced by the current image.
rm -f "$INDEX_PATH" "${INCOMING}/${ENGINE_ASSET:-none.none}" "${INCOMING}/${COMPOSE_ASSET:-none.none}" 2>/dev/null || true
if [ "$LAYER_CACHE" != "0" ] && [ -f "$INSTALL_DIR/current/images/layout.json" ]; then
  python3 "$INSTALL_DIR/current/scripts/layer-cache.py" prune "$CACHE_DIR" "$INSTALL_DIR/current/images/layout.json" 2>/dev/null \
    | sed 's/^/ARDTT_INFO|кэш слоёв: /' || true
fi
exit 0
