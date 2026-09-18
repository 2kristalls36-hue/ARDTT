#!/usr/bin/env bash
# Pack installer host files (no image, no Engine, no ardttctl) for the APK overlay.
# The phone SFTPs this ~100 KB tarball; fetch-and-install.sh applies it when it
# is newer than the published GitHub stack, keeping Release layers/Engine/Compose.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSET_DIR="$ROOT/android/app/src/main/assets/deploy"
OUT="${1:-$ASSET_DIR/ardtt-hostfiles-overlay.tar.gz}"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
[ -n "$VER" ] || { echo "empty server/DEPLOY_VERSION" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")" "$ASSET_DIR"

python3 - "$ROOT" "$OUT" "$VER" <<'PY'
import os, tarfile, sys
from pathlib import Path

root, out, ver = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
members = [
    ("server/install.sh", "install.sh"),
    ("server/fetch-and-install.sh", "fetch-and-install.sh"),
    ("server/ready.sh", "ready.sh"),
    ("server/docker-compose.yml", "docker-compose.yml"),
    ("server/docker-compose.exit.yml", "docker-compose.exit.yml"),
    ("server/.env.example", ".env.example"),
    ("server/DEPLOY_VERSION", "DEPLOY_VERSION"),
    ("scripts/safe-extract-package.py", "scripts/safe-extract-package.py"),
    ("scripts/assemble-docker-save.py", "scripts/assemble-docker-save.py"),
    ("scripts/layer-cache.py", "scripts/layer-cache.py"),
]
for path in sorted((root / "server/install-lib").rglob("*")):
    if path.is_file():
        rel = path.relative_to(root / "server").as_posix()
        members.append((f"server/{rel}", rel))

tmp = out.with_name(out.name + ".part")
with open(tmp, "wb") as raw:
    with tarfile.open(fileobj=raw, mode="w:gz", compresslevel=9) as tar:
        tar.format = tarfile.PAX_FORMAT
        for src_rel, arcname in members:
            src = root / src_rel
            if not src.is_file():
                raise SystemExit(f"missing {src_rel}")
            info = tar.gettarinfo(str(src), arcname=arcname)
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mtime = 0
            info.mode = 0o755 if (src.stat().st_mode & 0o111) else 0o644
            with open(src, "rb") as fh:
                tar.addfile(info, fh)
os.replace(tmp, out)

forbidden = ("images/", "vendor/", "bin/", "ardttctl", "manifest.json")
with tarfile.open(out, "r:gz") as tar:
    names = [n.replace("\\", "/").lstrip("./") for n in tar.getnames() if n and not n.endswith("/")]
for n in names:
    if n == "ardttctl" or n.startswith(forbidden):
        raise SystemExit(f"overlay must not contain {n}")
for required in ("install.sh", "fetch-and-install.sh", "ready.sh", "DEPLOY_VERSION",
                 "docker-compose.yml", "install-lib/package.sh", "scripts/layer-cache.py"):
    if required not in names:
        raise SystemExit(f"overlay missing {required}")
size = out.stat().st_size
if size > 512 * 1024:
    raise SystemExit(f"overlay too large: {size} bytes")
print(f"Packed {out} ({size} bytes, deployVersion={ver}, files={len(names)})")
PY

cp -f "$ROOT/server/fetch-and-install.sh" "$ASSET_DIR/fetch-and-install.sh"
cp -f "$ROOT/server/DEPLOY_VERSION" "$ASSET_DIR/DEPLOY_VERSION"
