#!/usr/bin/env bash
# Attach server packages from a local directory to a GitHub Release tag: the
# full ardtt-server-*.tar.gz archives plus the partial-deploy assets next to
# them (index, host files, per-layer gzips, Engine, Compose) and a
# SHA256SUMS-server.txt covering all of them. Creates the Release if the tag
# exists but Android build has not published it yet (the two tag workflows
# race). Does not rebuild or repack images. Does not download older workflow
# artifacts. Refuses to overwrite assets that already exist.
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
import hashlib, json, os, pathlib, subprocess, sys, tempfile
root = pathlib.Path(sys.argv[1])
ver = sys.argv[2]
dry = sys.argv[3] == "1"
tag = sys.argv[4]
repo = sys.argv[5]
archs = ("amd64", "arm64")

def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(4 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def find_one(pattern: str) -> pathlib.Path | None:
    hits = sorted(p for p in root.rglob(pattern) if p.is_file())
    if len(hits) > 1:
        raise SystemExit(f"more than one {pattern} under {root}: {[str(h) for h in hits]}")
    return hits[0] if hits else None

want = {f"ardtt-server-{ver}-linux-{a}.tar.gz" for a in archs}
pkgs = sorted(p for p in root.rglob(f"ardtt-server-{ver}-linux-*.tar.gz")
              if p.is_file() and p.name in want)
have = {p.name for p in pkgs}
if have != want:
    raise SystemExit(f"need both architectures for {ver}, have {sorted(have)}")

upload: list[pathlib.Path] = []
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

# Partial-deploy assets: every component the index references must be present
# with the recorded SHA-256, otherwise the release would advertise a partial
# deploy the VPS cannot complete.
indexes = 0
for arch in archs:
    index = find_one(f"ardtt-server-{ver}-linux-{arch}.index.json")
    if index is None:
        print(f"note: no partial-deploy index for {arch}; only the full archive is attached")
        continue
    indexes += 1
    d = json.loads(index.read_text(encoding="utf-8"))
    if d.get("format") != "ardtt-server-index-v1" or d.get("deployVersion") != ver or d.get("arch") != arch:
        raise SystemExit(f"{index.name}: unexpected index header {d.get('format')} {d.get('deployVersion')} {d.get('arch')}")
    components = [d["package"], d["hostfiles"], *d["image"]["layers"]]
    for key in ("engine", "compose"):
        if d.get(key):
            components.append(d[key])
    seen = {p.name for p in upload}
    for comp in components:
        path = find_one(comp["asset"])
        if path is None:
            raise SystemExit(f"{index.name}: missing component {comp['asset']}")
        got = sha256(path)
        if got != comp["sha256"].lower():
            raise SystemExit(f"{index.name}: {comp['asset']} sha256 {got} != index {comp['sha256']}")
        if path.name not in seen:
            upload.append(path)
            lines.append(f"{got}  {path.name}\n")
            seen.add(path.name)
    idx_digest = sha256(index)
    idx_sidecar = pathlib.Path(str(index) + ".sha256")
    idx_sidecar.write_text(idx_digest + "\n", encoding="utf-8")
    upload.extend([index, idx_sidecar])
    lines.append(f"{idx_digest}  {index.name}\n")

def gh(*args, check=True):
    return subprocess.run(
        ["gh", *args],
        text=True,
        capture_output=True,
        check=check,
    )

def load_release_assets():
    viewed = gh("release", "view", tag, "--repo", repo, "--json", "assets", check=False)
    if viewed.returncode == 0:
        return json.loads(viewed.stdout).get("assets") or []
    err = (viewed.stderr or viewed.stdout or "").strip()
    if "release not found" not in err.lower() and viewed.returncode != 1:
        raise SystemExit(err or f"gh release view {tag} failed")
    return None

def publish_release():
    # Deleting and recreating a git tag can leave the GitHub Release as a
    # draft (untagged-… URL). Users and /releases/latest do not see drafts.
    edited = gh(
        "release", "edit", tag, "--repo", repo,
        "--draft=false", "--latest",
        check=False,
    )
    if edited.returncode != 0:
        err = (edited.stderr or edited.stdout or "").strip()
        raise SystemExit(f"cannot publish GitHub Release {tag}: {err}")
    print("published GitHub Release https://github.com/%s/releases/tag/%s" % (repo, tag))

remote_assets = load_release_assets()
if remote_assets is None:
    # Tag workflows race: Server package can finish while Android build has
    # not created the GitHub Release yet. Create it, or attach if Android
    # won the create in between.
    create_cmd = [
        "release", "create", tag, "--repo", repo,
        "--title", f"ARDTT {tag}", "--generate-notes", "--latest",
    ]
    sha = os.environ.get("GITHUB_SHA", "").strip()
    if sha:
        create_cmd.extend(["--target", sha])
    created = gh(*create_cmd, check=False)
    if created.returncode != 0:
        remote_assets = load_release_assets()
        if remote_assets is None:
            err = (created.stderr or created.stdout or "release not found").strip()
            raise SystemExit(f"cannot create or view release {tag}: {err}")
        print("note: GitHub Release appeared while creating (Android build likely won)")
    else:
        remote_assets = load_release_assets() or []
        print("created GitHub Release https://github.com/%s/releases/tag/%s" % (repo, tag))
by_name = {a["name"]: a for a in remote_assets}
assets = set(by_name)

# Retag/rebuild can produce new layer filenames while the full archives
# for this DEPLOY_VERSION are already on the Release. Do not mix two 1.0.x
# payloads under the same names; succeed and publish instead of failing.
if want <= assets:
    print("full %s archives already on https://github.com/%s/releases/tag/%s; not replacing"
          % (ver, repo, tag))
    if not dry:
        publish_release()
    sys.exit(0)

# SHA256SUMS-server.txt is derived metadata: when a newer stack is attached to
# a tag that already carries an older one (attach-built-packages on an existing
# release), keep the existing lines and add ours instead of refusing.
sums = root / "SHA256SUMS-server.txt"
merged = {}
if sums.name in assets:
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.check_call(
            ["gh", "release", "download", tag, "--repo", repo, "--pattern", sums.name, "--dir", tmp],
        )
        for line in (pathlib.Path(tmp) / sums.name).read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) >= 2:
                merged[parts[-1]] = parts[0]
for line in lines:
    digest, name = line.split()
    merged[name] = digest
sums.write_text("".join(f"{d}  {n}\n" for n, d in sorted(merged.items())), encoding="utf-8")
sums_clobber = sums.name in assets

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
    if not dry:
        publish_release()
    sys.exit(0)

print("Attach to https://github.com/%s/releases/tag/%s (%d files, %d partial-deploy index(es))"
      % (repo, tag, len(upload), indexes))
for path in upload:
    print(" ", path, sha256(path))
print(" ", sums, "(merged, %d entries%s)" % (len(merged), ", replaces the existing file" if sums_clobber else ""))
if dry:
    print("dry-run: skip gh release upload")
    sys.exit(0)
# New assets are never clobbered; only the merged checksum list may replace itself.
subprocess.check_call(["gh", "release", "upload", tag, *[str(p) for p in upload], "--repo", repo])
sums_cmd = ["gh", "release", "upload", tag, str(sums), "--repo", repo]
if sums_clobber:
    sums_cmd.append("--clobber")
subprocess.check_call(sums_cmd)
print(f"Attached {len(pkgs)} server packages (+{len(upload) - len(pkgs)} assets) to {tag}")
publish_release()
PY
