#!/usr/bin/env bash
# Runs on the VPS: download ardtt-server-*.tar.gz from GitHub Releases,
# verify SHA-256, extract, run install.sh. The phone only SSHs and starts
# this script — it does not download or SFTP the multi‑MB package.
#
# Protocol: same ARDTT_PROGRESS| / ARDTT_ERROR| / ARDTT_DONE| as install.sh.
#
# Env (required):
#   ARDTT_PUBLIC_HOST
# Optional:
#   ARDTT_DEPLOY_VERSION   — pin stack version; empty = newest release asset
#   ARDTT_GITHUB_REPO      — owner/name (default 2kristalls36-hue/ARDTT)
#   ARDTT_GITHUB_API       — API base (default https://api.github.com)
#   ARDTT_ROLE, ports, cascade_* — forwarded to install.sh
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

REPO="${ARDTT_GITHUB_REPO:-2kristalls36-hue/ARDTT}"
API="${ARDTT_GITHUB_API:-https://api.github.com}"
INSTALL_DIR="${ARDTT_INSTALL_DIR:-/opt/ardtt}"
INCOMING="${INSTALL_DIR}/incoming"
STAGING="${INSTALL_DIR}/staging"
UA="${ARDTT_USER_AGENT:-ARDTT-VPS-fetch/1.0}"

command -v python3 >/dev/null 2>&1 || die "PYTHON_MISSING|нужен python3 на VPS"
command -v curl >/dev/null 2>&1 || die "CURL_MISSING|нужен curl на VPS для загрузки с GitHub Releases"

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'amd64' ;;
    aarch64|arm64) printf 'arm64' ;;
    *) die "UNSUPPORTED_ARCH|нужен linux amd64 или arm64 (uname -m=$(uname -m))" ;;
  esac
}

http_get() {
  local url="$1" out="$2"
  local code
  code="$(curl -fsSL -A "$UA" -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    -w '%{http_code}' -o "$out" "$url" || true)"
  [ "$code" = "200" ] || return 1
  return 0
}

http_download() {
  local url="$1" out="$2"
  curl -fL --retry 3 --retry-delay 2 -A "$UA" -o "$out" "$url"
}

ARCH="$(detect_arch)"
WANT_VER="${ARDTT_DEPLOY_VERSION:-}"
prog 0.02 "Определение пакета (${ARCH}${WANT_VER:+, версия $WANT_VER})"

LIST_JSON="$(mktemp)"
trap 'rm -f "$LIST_JSON" "${INCOMING:-/tmp}/.ardtt-fetch."* 2>/dev/null || true' EXIT

if ! http_get "${API}/repos/${REPO}/releases?per_page=40" "$LIST_JSON"; then
  die "GITHUB_UNREACHABLE|VPS не смог получить список релизов ${REPO}. Нужен исходящий HTTPS к api.github.com."
fi

RESOLVED="$(
  WANT_VER="$WANT_VER" ARCH="$ARCH" python3 - "$LIST_JSON" <<'PY'
import json, os, re, sys
path = sys.argv[1]
want = os.environ.get("WANT_VER", "").strip()
arch = os.environ["ARCH"]
pat = re.compile(r"^ardtt-server-(\d+\.\d+\.\d+)-linux-(amd64|arm64)\.tar\.gz$", re.I)
releases = json.load(open(path, encoding="utf-8"))
if not isinstance(releases, list):
    raise SystemExit("bad releases payload")

def assets(rel):
    for a in rel.get("assets") or []:
        name = a.get("name") or ""
        m = pat.match(name)
        if not m:
            continue
        ver, aarch = m.group(1), m.group(2).lower()
        if aarch != arch:
            continue
        url = (a.get("browser_download_url") or "").strip()
        if not url:
            continue
        digest = (a.get("digest") or "").removeprefix("sha256:").lower()
        size = int(a.get("size") or 0)
        yield {
            "version": ver,
            "name": name,
            "url": url,
            "sha256": digest,
            "size": size,
            "tag": (rel.get("tag_name") or "").strip(),
            "sums": None,
            "sibling": None,
            "assets": rel.get("assets") or [],
        }

picked = None
for rel in releases:
    if rel.get("draft"):
        continue
    for item in assets(rel):
        if want and item["version"] != want:
            continue
        # Prefer first match: API is newest-first; with want= empty → latest.
        picked = item
        break
    if picked:
        break

if not picked:
    if want:
        raise SystemExit(f"no ardtt-server-{want}-linux-{arch}.tar.gz in releases")
    raise SystemExit(f"no ardtt-server-*-linux-{arch}.tar.gz in releases")

# Attach SHA256SUMS / sibling urls from the same release.
for a in picked["assets"]:
    n = (a.get("name") or "")
    u = (a.get("browser_download_url") or "").strip()
    if not u:
        continue
    if n.lower() in ("sha256sums-server.txt", "sha256sums.txt", "sha256sums") and not picked["sums"]:
        picked["sums"] = u
    if n.lower() == picked["name"].lower() + ".sha256":
        picked["sibling"] = u
print(json.dumps({k: picked[k] for k in ("version", "name", "url", "sha256", "size", "tag", "sums", "sibling")}))
PY
)" || die "PACKAGE_RESOLVE|не найден пакет ardtt-server для ${ARCH}${WANT_VER:+ версии $WANT_VER} в релизах ${REPO}"

ENV_SNIPPET="$(mktemp)"
RESOLVED_FILE="$(mktemp)"
printf '%s\n' "$RESOLVED" > "$RESOLVED_FILE"
python3 - "$ENV_SNIPPET" "$RESOLVED_FILE" <<'PY'
import json, sys
out, src = sys.argv[1], sys.argv[2]
d = json.load(open(src, encoding="utf-8"))

def q(s: object) -> str:
    return "'" + str(s).replace("'", "'\"'\"'") + "'"

lines = [
    "PKG_VER=" + q(d["version"]),
    "PKG_NAME=" + q(d["name"]),
    "PKG_URL=" + q(d["url"]),
    "PKG_SHA=" + q(d.get("sha256") or ""),
    "PKG_TAG=" + q(d.get("tag") or ""),
    "SUMS_URL=" + q(d.get("sums") or ""),
    "SIB_URL=" + q(d.get("sibling") or ""),
]
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
# shellcheck disable=SC1090
. "$ENV_SNIPPET"
rm -f "$ENV_SNIPPET" "$RESOLVED_FILE"

if [ -z "$PKG_SHA" ] && [ -n "$SUMS_URL" ]; then
  SUMS_BODY="$(mktemp)"
  if http_download "$SUMS_URL" "$SUMS_BODY"; then
    PKG_SHA="$(
      PKG_NAME="$PKG_NAME" python3 - "$SUMS_BODY" <<'PY'
import os,sys
name=os.environ["PKG_NAME"]
for line in open(sys.argv[1],encoding="utf-8",errors="replace"):
    line=line.strip()
    if not line or line.startswith("#"):
        continue
    parts=line.split()
    if len(parts)<2: continue
    if parts[-1].rsplit("/",1)[-1].lower()==name.lower():
        print(parts[0].lower().removeprefix("sha256:"))
        break
PY
    )"
  fi
  rm -f "$SUMS_BODY"
fi
if [ -z "$PKG_SHA" ] && [ -n "$SIB_URL" ]; then
  SIB_BODY="$(mktemp)"
  if http_download "$SIB_URL" "$SIB_BODY"; then
    PKG_SHA="$(
      python3 - "$SIB_BODY" <<'PY'
import sys
tok=open(sys.argv[1],encoding="utf-8",errors="replace").read().split()
print((tok[0] if tok else "").lower().removeprefix("sha256:"))
PY
    )"
  fi
  rm -f "$SIB_BODY"
fi
[ -n "$PKG_SHA" ] && [ "${#PKG_SHA}" -eq 64 ] || die "SHA256_MISSING|нет доверенной SHA-256 для ${PKG_NAME} (digest / SHA256SUMS релиза)"

mkdir -p "$INCOMING" "$STAGING"
chmod 755 "$INSTALL_DIR" "$INCOMING" 2>/dev/null || true
PKG_PATH="${INCOMING}/${PKG_NAME}"
PARTIAL="${PKG_PATH}.partial"
rm -f "$PARTIAL"

prog 0.08 "Загрузка ${PKG_NAME} с GitHub Releases (${PKG_TAG:-?})"
http_download "$PKG_URL" "$PARTIAL" || die "DOWNLOAD_FAILED|не удалось скачать ${PKG_URL}"
mv -f "$PARTIAL" "$PKG_PATH"

prog 0.22 "Проверка SHA-256"
echo "${PKG_SHA}  ${PKG_PATH}" | sha256sum -c - >/dev/null || die "SHA256_MISMATCH|пакет ${PKG_NAME} не совпал с релизом"

prog 0.28 "Распаковка пакета"
rm -rf "$STAGING"
mkdir -p "$STAGING"
if [ -f "$(dirname "$0")/scripts/safe-extract-package.py" ]; then
  python3 "$(dirname "$0")/scripts/safe-extract-package.py" "$PKG_PATH" "$STAGING"
elif [ -f "${STAGING}/../current/scripts/safe-extract-package.py" ]; then
  python3 "${INSTALL_DIR}/current/scripts/safe-extract-package.py" "$PKG_PATH" "$STAGING"
else
  # Inline safe extract (same rules as phone DeployInstallEnv / package path).
  python3 - "$PKG_PATH" "$STAGING" <<'PY'
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
fi
[ -f "$STAGING/install.sh" ] || die "PACKAGE_INVALID|в архиве нет install.sh"
[ -f "$STAGING/manifest.json" ] || die "PACKAGE_INVALID|в архиве нет manifest.json"

# Prefer fetch script from the new package for the next update cycle.
if [ -f "$STAGING/fetch-and-install.sh" ]; then
  chmod 755 "$STAGING/fetch-and-install.sh" || true
fi

export ARDTT_PACKAGE="$PKG_PATH"
export ARDTT_PACKAGE_SHA256="$PKG_SHA"
export ARDTT_PKG_DIR="$STAGING"
export ARDTT_DEPLOY_VERSION="${ARDTT_DEPLOY_VERSION:-$PKG_VER}"
export ARDTT_INSTALL_DIR="$INSTALL_DIR"

prog 0.35 "Запуск install.sh (${ARDTT_DEPLOY_VERSION})"
# install.sh emits its own 0..1 progress; remap is left to the phone if needed.
bash "$STAGING/install.sh"
# install.sh exits 0 and prints ARDTT_DONE; keep package for a bit then drop partials
rm -f "$PARTIAL" 2>/dev/null || true
