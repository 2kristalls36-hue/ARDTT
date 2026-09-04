#!/usr/bin/env bash
# Publish a signed APK to the in-app fallback:
#   https://45.129.2.3/update.json  →  https://45.129.2.3/ardtt-latest.apk
#
# GitHub Releases are private; 0.5.205/0.5.206 APKs have an empty GITHUB_API_TOKEN,
# so the phone only sees updates from this VPS file.
#
# Required env:
#   ARDTT_SSH_PASSWORD   root password for the distribution VPS
# Optional env:
#   ARDTT_DIST_HOST      default 45.129.2.3
#   VERSION_NAME         default: parsed from APK filename ardtt-<ver>-*.apk
#   VERSION_CODE         default: parsed from android/app/build.gradle.kts
#   NOTES                default: last git subject
#
# Usage:
#   ARDTT_SSH_PASSWORD='…' ./scripts/publish-vps-update.sh dist/ardtt-0.5.206-arm64-v8a.apk
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="${ARDTT_DIST_HOST:-45.129.2.3}"
REMOTE_DIR="${ARDTT_DIST_DIR:-/opt/ardtt-distribution/dist}"
APK_PATH="${1:-}"

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "Usage: $0 <path-to-arm64-apk>" >&2
  exit 1
fi
if [[ -z "${ARDTT_SSH_PASSWORD:-}" ]]; then
  echo "ARDTT_SSH_PASSWORD is required." >&2
  exit 1
fi

APK_PATH="$(cd "$(dirname "$APK_PATH")" && pwd)/$(basename "$APK_PATH")"
APK_BASE="$(basename "$APK_PATH")"
if [[ -z "${VERSION_NAME:-}" ]]; then
  VERSION_NAME="$(sed -n 's/^ardtt-\(.*\)-\(arm64-v8a\|armeabi-v7a\|x86_64\|universal\)\.apk$/\1/p' <<<"$APK_BASE")"
fi
if [[ -z "${VERSION_NAME:-}" ]]; then
  echo "Could not parse VERSION_NAME from $APK_BASE; set VERSION_NAME=." >&2
  exit 1
fi
if [[ -z "${VERSION_CODE:-}" ]]; then
  VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$ROOT_DIR/android/app/build.gradle.kts" | head -n 1)"
fi
if [[ -z "${VERSION_CODE:-}" ]]; then
  echo "Could not parse VERSION_CODE; set VERSION_CODE=." >&2
  exit 1
fi
NOTES="${NOTES:-$(git -C "$ROOT_DIR" log -1 --pretty=format:%s 2>/dev/null || echo "Обновление приложения.")}"

export ARDTT_DIST_HOST="$HOST"
export ARDTT_DIST_DIR="$REMOTE_DIR"
export VERSION_NAME VERSION_CODE NOTES APK_PATH

python3 - <<'PY'
import hashlib, json, os, sys, time

try:
    import paramiko
except ImportError:
    sys.stderr.write("Install paramiko: pip3 install paramiko\n")
    sys.exit(1)

host = os.environ["ARDTT_DIST_HOST"]
password = os.environ["ARDTT_SSH_PASSWORD"]
remote_dir = os.environ["ARDTT_DIST_DIR"].rstrip("/")
local_apk = os.environ["APK_PATH"]
version_name = os.environ["VERSION_NAME"]
version_code = int(os.environ["VERSION_CODE"])
notes = os.environ.get("NOTES") or "Обновление приложения."

with open(local_apk, "rb") as f:
    data = f.read()
size_bytes = len(data)
sha256 = hashlib.sha256(data).hexdigest()
print(f"local {local_apk} sha256={sha256} size={size_bytes} version={version_name} ({version_code})")

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(host, username="root", password=password, timeout=30, allow_agent=False, look_for_keys=False)
sftp = client.open_sftp()

remote_tmp = f"{remote_dir}/.ardtt-{version_name}.apk.part"
remote_ver = f"{remote_dir}/ardtt-{version_name}.apk"
remote_latest = f"{remote_dir}/ardtt-latest.apk"
remote_json_tmp = f"{remote_dir}/.update.json.part"
remote_json = f"{remote_dir}/update.json"

print(f"Uploading to {host}:{remote_tmp}")
t0 = time.time()
with sftp.file(remote_tmp, "wb") as remote:
    remote.write(data)
print(f"upload_seconds={time.time() - t0:.1f}")

stdin, stdout, stderr = client.exec_command(f"sha256sum {remote_tmp}")
out = stdout.read().decode().strip()
if stdout.channel.recv_exit_status() != 0 or not out.startswith(sha256):
    sftp.remove(remote_tmp)
    raise SystemExit(f"remote sha mismatch: {out!r}")

manifest = {
    "versionCode": version_code,
    "versionName": version_name,
    "apkUrl": f"https://{host}/ardtt-latest.apk",
    "sha256": sha256,
    "sizeBytes": size_bytes,
    "notes": notes,
}
with sftp.file(remote_json_tmp, "w") as f:
    f.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

# Never cp onto a hardlinked ardtt-latest.apk: that would overwrite the previous versioned APK too.
cmd = (
    f"mv -f {remote_tmp} {remote_ver} && "
    f"rm -f {remote_latest} && "
    f"cp -f {remote_ver} {remote_latest} && "
    f"mv -f {remote_json_tmp} {remote_json} && "
    f"cd {remote_dir} && sha256sum ardtt-{version_name}.apk ardtt-latest.apk update.json > SHA256SUMS.txt && "
    f"cat {remote_json}"
)
stdin, stdout, stderr = client.exec_command(cmd)
print(stdout.read().decode())
err = stderr.read().decode().strip()
code = stdout.channel.recv_exit_status()
if err:
    print(err, file=sys.stderr)
if code != 0:
    raise SystemExit(code)

sftp.close()
client.close()
print("Published fallback update.json")
PY
