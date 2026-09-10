#!/usr/bin/env bash
# Attach ardtt-server-*.tar.gz from a local directory to an existing GitHub
# Release tag. Does not rebuild or repack images. Does not download older
# workflow artifacts. Refuses to overwrite assets that already exist.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
REPO="${GITHUB_REPOSITORY:-}"
if [ -z "$REPO" ]; then
  origin="$(git -C "$ROOT" remote get-url origin 2>/dev/null || true)"
  REPO="$(printf '%s' "$origin" | sed -E 's#.*github.com[:/]([^/]+/[^/.]+)(\.git)?$#\1#')"
fi
[ -n "$REPO" ] || { echo "GITHUB_REPOSITORY is not set" >&2; exit 1; }

TAG=""
FROM_DIR=""
DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --from-dir) FROM_DIR="${2:-}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help)
      echo "usage: $0 --tag vX.Y.Z --from-dir DIR [--dry-run]"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

[ -n "$TAG" ] || { echo "usage: $0 --tag vX.Y.Z --from-dir DIR" >&2; exit 1; }
[ -n "$FROM_DIR" ] && [ -d "$FROM_DIR" ] || { echo "--from-dir must be a directory" >&2; exit 1; }

python3 - "$FROM_DIR" "$VER" "$DRY" "$TAG" "$REPO" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1])
ver = sys.argv[2]
dry = sys.argv[3] == "1"
tag = sys.argv[4]
repo = sys.argv[5]
want = {
    f"ardtt-server-{ver}-linux-amd64.tar.gz",
    f"ardtt-server-{ver}-linux-arm64.tar.gz",
}
pkgs = sorted(p for p in root.rglob(f"ardtt-server-{ver}-linux-*.tar.gz") if p.is_file())
have = {p.name for p in pkgs}
if have != want:
    raise SystemExit(f"need both architectures for {ver}, have {sorted(have)}")

def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

upload = []
lines = []
for pkg in pkgs:
    digest = sha256(pkg)
    sidecar = pathlib.Path(str(pkg) + ".sha256")
    if sidecar.is_file():
        expect = sidecar.read_text(encoding="utf-8").split()[0].strip().lower().removeprefix("sha256:")
        if expect and expect != digest:
            raise SystemExit(f"SHA-256 mismatch for {pkg.name}")
    else:
        sidecar.write_text(digest + "\n", encoding="utf-8")
    lines.append(f"{digest}  {pkg.name}\n")
    upload.append(pkg)
    upload.append(sidecar)
sums = root / "SHA256SUMS-server.txt"
sums.write_text("".join(lines), encoding="utf-8")
upload.append(sums)

raw = subprocess.check_output(
    ["gh", "release", "view", tag, "--repo", repo, "--json", "assets"],
    text=True,
)
remote_assets = json.loads(raw).get("assets") or []
by_name = {a["name"]: a for a in remote_assets}
assets = set(by_name)
overlap = [p.name for p in upload if p.name in assets]
if overlap:
    if set(overlap) != {p.name for p in upload}:
        raise SystemExit(
            "refusing to overwrite existing release assets: " + ", ".join(overlap)
        )
    mismatches = []
    for path in upload:
        remote = by_name[path.name]
        local_digest = sha256(path)
        remote_digest = (remote.get("digest") or "").lower().removeprefix("sha256:")
        remote_size = int(remote.get("size") or 0)
        local_size = path.stat().st_size
        if remote_digest:
            if remote_digest != local_digest:
                mismatches.append(
                    f"{path.name}: release {remote_digest} != local {local_digest}"
                )
        elif remote_size and remote_size != local_size:
            mismatches.append(
                f"{path.name}: release size {remote_size} != local {local_size}"
            )
        elif not remote_digest:
            mismatches.append(f"{path.name}: release asset has no digest to compare")
    if mismatches:
        raise SystemExit(
            "release already has different server assets; not overwriting:\n  "
            + "\n  ".join(mismatches)
        )
    print("already attached (digest match) to https://github.com/%s/releases/tag/%s" % (repo, tag))
    sys.exit(0)

print("Attach to https://github.com/%s/releases/tag/%s" % (repo, tag))
for path in upload:
    print(" ", path, sha256(path) if path.suffix != ".txt" else "")
if dry:
    print("dry-run: skip gh release upload")
    sys.exit(0)
cmd = ["gh", "release", "upload", tag, *[str(p) for p in upload], "--repo", repo]
subprocess.check_call(cmd)
print(f"Attached {len(pkgs)} server packages to {tag}")
PY
