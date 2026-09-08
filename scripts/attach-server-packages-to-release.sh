#!/usr/bin/env bash
# Attach already-built ardtt-server-*.tar.gz Actions artifacts to a GitHub Release.
# Does not rebuild the image. Uploads only files whose version matches
# server/DEPLOY_VERSION in this checkout.
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
RUN_ID=""
DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --run-id) RUN_ID="${2:-}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help)
      echo "usage: $0 [--tag vX.Y.Z] [--run-id N] [--dry-run]"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [ -z "$TAG" ]; then
  TAG="$(gh release view --repo "$REPO" --json tagName --jq .tagName 2>/dev/null || true)"
fi
if [ -z "$TAG" ]; then
  echo "No GitHub Release to attach to; packages stay on Actions artifacts."
  exit 0
fi

WORKDIR="$(mktemp -d)"
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

if [ -z "$RUN_ID" ]; then
  RUN_ID="$(python3 - "$REPO" <<'PY'
import json, subprocess, sys
repo = sys.argv[1]

def artifacts(name):
    raw = subprocess.check_output(
        ["gh", "api", "--paginate",
         f"repos/{repo}/actions/artifacts?name={name}&per_page=100"],
        text=True,
    )
    decoder = json.JSONDecoder()
    s = raw.strip()
    idx = 0
    pages = []
    while idx < len(s):
        while idx < len(s) and s[idx].isspace():
            idx += 1
        if idx >= len(s):
            break
        obj, end = decoder.raw_decode(s, idx)
        pages.append(obj)
        idx = end
    out = []
    for page in pages:
        for a in page.get("artifacts", []):
            if a.get("expired"):
                continue
            wr = a.get("workflow_run") or {}
            run = wr.get("id")
            if not run:
                continue
            out.append({"run": run, "created": a.get("created_at") or ""})
    return out

amd = artifacts("ardtt-server-amd64")
arm_runs = {a["run"] for a in artifacts("ardtt-server-arm64")}
candidates = [a for a in amd if a["run"] in arm_runs]
candidates.sort(key=lambda a: a["created"], reverse=True)
if candidates:
    print(candidates[0]["run"])
PY
)" || true
fi

if [ -z "${RUN_ID:-}" ]; then
  echo "No non-expired ardtt-server-{amd64,arm64} artifacts; skip upload."
  exit 0
fi

echo "Downloading server packages from Actions run $RUN_ID (want $VER)"
gh run download "$RUN_ID" --repo "$REPO" \
  -n ardtt-server-amd64 -n ardtt-server-arm64 \
  -D "$WORKDIR"

python3 - "$WORKDIR" "$VER" "$DRY" "$TAG" "$REPO" <<'PY'
import hashlib, os, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1])
ver = sys.argv[2]
dry = sys.argv[3] == "1"
tag = sys.argv[4]
repo = sys.argv[5]
pkgs = sorted(root.rglob(f"ardtt-server-{ver}-linux-*.tar.gz"))
want = {
    f"ardtt-server-{ver}-linux-amd64.tar.gz",
    f"ardtt-server-{ver}-linux-arm64.tar.gz",
}
have = {p.name for p in pkgs}
if not want <= have:
    print(f"Artifacts do not contain {sorted(want)}; skip upload.")
    for p in root.rglob("*"):
        if p.is_file():
            print(" ", p)
    sys.exit(0)
upload = []
lines = []
for pkg in pkgs:
    digest = hashlib.sha256(pkg.read_bytes()).hexdigest()
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
print("Attach to https://github.com/%s/releases/tag/%s" % (repo, tag))
for path in upload:
    print(" ", path)
if dry:
    print("dry-run: skip gh release upload")
    sys.exit(0)
cmd = ["gh", "release", "upload", tag, *[str(p) for p in upload], "--repo", repo, "--clobber"]
subprocess.check_call(cmd)
print(f"Attached {len(pkgs)} server packages to {tag}")
PY
