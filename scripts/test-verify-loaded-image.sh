#!/usr/bin/env bash
# Same layers + different Entrypoint/Env must not pass image identity.
# Matching config+layers may pass even when docker Id strings differ.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUB="$TMP/bin"
mkdir -p "$STUB" "$TMP/tar"

# Minimal docker-save layout: manifest Config + config JSON + fake layer name.
python3 - "$TMP/tar" <<'PY'
import json, pathlib, tarfile, io, sys
root = pathlib.Path(sys.argv[1])
cfg = {
    "os": "linux",
    "architecture": "amd64",
    "rootfs": {"type": "layers", "diff_ids": ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]},
    "config": {
        "Entrypoint": ["/opt/ardtt/entrypoint.sh"],
        "Cmd": None,
        "Env": ["PATH=/usr/bin"],
        "User": "0",
        "WorkingDir": "/opt/ardtt",
    },
}
(root / "cfg.json").write_text(json.dumps(cfg), encoding="utf-8")
man = [{"Config": "cfg.json", "RepoTags": ["ardtt/server:1.0.45"], "Layers": []}]
(root / "manifest.json").write_text(json.dumps(man), encoding="utf-8")
with tarfile.open(root / "image.tar", "w") as t:
    t.add(root / "manifest.json", arcname="manifest.json")
    t.add(root / "cfg.json", arcname="cfg.json")
PY

cat > "$STUB/docker" <<'EOF'
#!/usr/bin/env bash
# $FAKE_ENTRYPOINT / $FAKE_ENV override inspect Config.
if [ "$1" = "image" ] && [ "$2" = "inspect" ]; then
  python3 - "$FAKE_ENTRYPOINT" "$FAKE_ENV" <<'PY'
import json, os, sys
ep = sys.argv[1]
env = sys.argv[2]
doc = [{
  "Id": "sha256:otherstore",
  "Os": "linux",
  "Architecture": "amd64",
  "RootFS": {"Layers": ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]},
  "Config": {
    "Entrypoint": [ep] if ep else ["/opt/ardtt/entrypoint.sh"],
    "Cmd": None,
    "Env": [env] if env else ["PATH=/usr/bin"],
    "User": "0",
    "WorkingDir": "/opt/ardtt",
  },
}]
print(json.dumps(doc))
PY
  exit 0
fi
echo "unexpected docker $*" >&2
exit 1
EOF
chmod +x "$STUB/docker"
export PATH="$STUB:$PATH"

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/package.sh"

# Subprocess docker does not see unexported shell vars.
export FAKE_ENTRYPOINT="/opt/ardtt/entrypoint.sh"
export FAKE_ENV="PATH=/usr/bin"
if loaded_image_matches_tar "$TMP/tar/image.tar" "ardtt/server:1.0.45"; then
  ok "matching config+layers accepted"
else
  err "matching config should pass"
fi

export FAKE_ENTRYPOINT="/bin/evil"
export FAKE_ENV="PATH=/usr/bin"
if loaded_image_matches_tar "$TMP/tar/image.tar" "ardtt/server:1.0.45"; then
  err "different Entrypoint with same layers must fail"
else
  ok "different Entrypoint rejected"
fi

export FAKE_ENTRYPOINT="/opt/ardtt/entrypoint.sh"
export FAKE_ENV="PATH=/evil"
if loaded_image_matches_tar "$TMP/tar/image.tar" "ardtt/server:1.0.45"; then
  err "different Env with same layers must fail"
else
  ok "different Env rejected"
fi

if [ "$fail" -ne 0 ]; then
  echo "image identity tests failed" >&2
  exit 1
fi
echo "OK image identity"
exit 0
