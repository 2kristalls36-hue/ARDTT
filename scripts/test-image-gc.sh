#!/usr/bin/env bash
# Old ARDTT images are removed. Current, previous, and foreign images stay.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }
ok() { echo "OK $*"; }

PY="$ROOT/server/install-lib/image-gc.py"
python3 -m py_compile "$PY"

python3 - "$PY" <<'PY' || err "planner kept or removed the wrong image"
import subprocess, sys, tempfile, os
py = sys.argv[1]
images = """ardtt/server\t1.0.53\taaa111aaa111
ardtt/server\t1.0.51\tbbb222bbb222
ardtt/server\t1.0.46\tccc333ccc333
stack-ardtt\tlatest\tddd444ddd444
whn0thacked/telemt-docker\tlatest\teee555eee555
<none>\t<none>\tccc333ccc333
"""
keep = """ref\tardtt/server:1.0.53
ref\tardtt/server:1.0.51
id\tsha256:aaa111aaa111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
ref\twhn0thacked/telemt-docker:latest
"""
with tempfile.TemporaryDirectory() as d:
    img = os.path.join(d, "images")
    kp = os.path.join(d, "keep")
    open(img, "w", encoding="utf-8").write(images)
    open(kp, "w", encoding="utf-8").write(keep)
    out = subprocess.check_output([sys.executable, py, "plan", img, kp], text=True)
got = [line for line in out.splitlines() if line.strip()]
want = {"ardtt/server:1.0.46", "stack-ardtt:latest"}
if set(got) != want:
    raise SystemExit(f"got {got}")
# In-use id protects an old tag even if it is not current/previous.
images2 = """ardtt/server\t1.0.46\tccc333ccc333
"""
keep2 = """id\tccc333ccc333
"""
with tempfile.TemporaryDirectory() as d:
    img = os.path.join(d, "images")
    kp = os.path.join(d, "keep")
    open(img, "w", encoding="utf-8").write(images2)
    open(kp, "w", encoding="utf-8").write(keep2)
    out = subprocess.check_output([sys.executable, py, "plan", img, kp], text=True)
if out.strip():
    raise SystemExit(f"in-use image was selected: {out!r}")
PY
ok "planner keeps current, previous, in-use, and foreign images"

if ! grep -q 'ardtt_gc_owned_images' "$ROOT/server/install.sh"; then
  err "install.sh must drop old owned images"
fi
if ! grep -A3 'preflight_space()' "$ROOT/server/install.sh" | grep -q 'ardtt_gc_owned_images'; then
  err "image gc must run before the disk budget"
fi
if grep -q 'docker image prune' "$ROOT/server/install-lib/disk-cleanup.sh" "$ROOT/server/install-lib/image-gc.py"; then
  err "image gc must not docker image prune"
fi
ok "installer calls owned image gc and does not prune"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BIN="$TMP/bin"
mkdir -p "$BIN" "$TMP/opt/current" "$TMP/opt/previous"
printf 'ARDTT_IMAGE=ardtt/server:1.0.53\n' > "$TMP/opt/current/.env"
printf 'ARDTT_IMAGE=ardtt/server:1.0.51\n' > "$TMP/opt/previous/.env"
printf 'ARDTT_IMAGE=ardtt/server:1.0.53\n' > "$TMP/opt/.env"
RMLOG="$TMP/rm.log"
cat > "$BIN/docker" <<EOF
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "\$*" >> "$TMP/docker.log"
if [ "\$1" = "info" ]; then
  exit 0
fi
if [ "\$1" = "images" ]; then
  printf '%s\n' \\
    'ardtt/server	1.0.53	aaa111aaa111' \\
    'ardtt/server	1.0.51	bbb222bbb222' \\
    'ardtt/server	1.0.46	ccc333ccc333' \\
    'stack-ardtt	latest	ddd444ddd444' \\
    'whn0thacked/telemt-docker	latest	eee555eee555'
  exit 0
fi
if [ "\$1" = "ps" ]; then
  printf '%s\n' ardttcid telemtcid
  exit 0
fi
if [ "\$1" = "inspect" ]; then
  fmt="\${3:-}"
  id="\${4:-}"
  case "\$id:\$fmt" in
    ardttcid:'{{.Image}}') printf '%s\n' 'sha256:aaa111aaa111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' ;;
    ardttcid:'{{.Config.Image}}') printf '%s\n' 'ardtt/server:1.0.53' ;;
    telemtcid:'{{.Image}}') printf '%s\n' 'sha256:eee555eee555eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' ;;
    telemtcid:'{{.Config.Image}}') printf '%s\n' 'whn0thacked/telemt-docker:latest' ;;
    *) exit 1 ;;
  esac
  exit 0
fi
if [ "\$1" = "image" ] && [ "\$2" = "rm" ]; then
  printf '%s\n' "\$3" >> "$RMLOG"
  exit 0
fi
echo "unexpected docker \$*" >&2
exit 1
EOF
chmod 755 "$BIN/docker"

# shellcheck disable=SC1091
. "$ROOT/server/install-lib/common.sh"
# shellcheck disable=SC1091
. "$ROOT/server/install-lib/disk-cleanup.sh"
INSTALL_DIR="$TMP/opt"
PATH="$BIN:$PATH"
ARDTT_IMAGE="ardtt/server:1.0.56"
out="$(ardtt_gc_owned_images)"
echo "$out" | grep -q 'ardtt/server:1.0.46' || err "did not report removal of 1.0.46"
echo "$out" | grep -q 'stack-ardtt:latest' || err "did not report removal of stack-ardtt"
if grep -q '1.0.53' "$RMLOG"; then err "removed current image"; fi
if grep -q '1.0.51' "$RMLOG"; then err "removed previous image"; fi
if grep -q 'telemt' "$RMLOG"; then err "removed foreign image"; fi
grep -qx 'ardtt/server:1.0.46' "$RMLOG" || err "rm log missing 1.0.46"
grep -qx 'stack-ardtt:latest' "$RMLOG" || err "rm log missing stack-ardtt"
# The tag we are about to load is kept even though it is not on disk yet.
ok "fake docker gc removed only old owned tags"

if [ "$fail" -ne 0 ]; then
  echo "image gc tests failed" >&2
  exit 1
fi
echo "OK image gc"
exit 0
