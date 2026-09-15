#!/usr/bin/env bash
# Download Preview APK that updates over GitHub v0.5.264. Never uses releases/latest.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/lib/common.sh"
ardtt_load_versions
# shellcheck disable=SC1091
source "$HERE/lib/apk-gate.sh"
ardtt_require_cmd gh
ardtt_require_cmd unzip

PR="${ARDTT_PR}"
RUN_ID="${ARDTT_PREVIEW_RUN_ID:-}"
OUT_DIR=""
REPO="2kristalls36-hue/ARDTT"
BRANCH="${ARDTT_PREVIEW_BRANCH:-auto/windows-usb-testlab-85a8}"
USE_LATEST_ON_BRANCH=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pr) PR=$2; shift 2 ;;
    --run-id) RUN_ID=$2; shift 2 ;;
    --out) OUT_DIR=$2; shift 2 ;;
    --repo) REPO=$2; shift 2 ;;
    --branch) BRANCH=$2; shift 2 ;;
    --latest-on-branch) USE_LATEST_ON_BRANCH=1; shift ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done
if [[ -z "$OUT_DIR" ]]; then
  OUT_DIR="${ARDTT_LAB_ROOT:-$HOME/ardtt-testlab}/apk"
fi
mkdir -p "$OUT_DIR"

if [[ "$USE_LATEST_ON_BRANCH" -eq 1 || -z "$RUN_ID" ]]; then
  RUN_ID="$(gh run list --repo "$REPO" --workflow "Preview APK" --branch "$BRANCH" --limit 20 --json databaseId,headSha,conclusion,event,displayTitle,url \
    --jq "[.[] | select(.conclusion==\"success\")][0].databaseId")"
fi
[[ -n "$RUN_ID" && "$RUN_ID" != "null" ]] || ardtt_die "не найден успешный Preview APK run на ${BRANCH}"

META="$(gh run view "$RUN_ID" --repo "$REPO" --json databaseId,headSha,event,url,displayTitle,conclusion,workflowName)"
HEAD_SHA="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["headSha"])' "$META")"
EVENT="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["event"])' "$META")"
URL="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["url"])' "$META")"

ardtt_log INFO "downloading artifacts of run $RUN_ID event=$EVENT headSha=$HEAD_SHA"
gh run download "$RUN_ID" --repo "$REPO" --dir "$OUT_DIR/raw"

APK="$(find "$OUT_DIR/raw" -type f -name '*.apk' | head -1)"
[[ -n "$APK" ]] || ardtt_die "в артефактах нет APK"

NAME="$(basename "$(dirname "$APK")")"
ardtt_assert_preview_apk_current "$NAME"
ardtt_assert_apk_version_code "$APK"
STABLE="$OUT_DIR/$(basename "$APK")"
cp -f -- "$APK" "$STABLE"
APK="$STABLE"
# workflow names artifact ardtt-<version>-<shortSha>-arm64-v8a where shortSha is GITHUB_SHA (merge commit on pull_request)
python3 - "$OUT_DIR/provenance.json" <<PY
import json, datetime
doc = {
  "schema": "ardtt-lab-ci-apk/v1",
  "pr": int("$PR"),
  "runId": int("$RUN_ID"),
  "runUrl": "$URL",
  "event": "$EVENT",
  "headSha": "$HEAD_SHA",
  "artifactDirName": "$NAME",
  "apkPath": "$APK",
  "minVersionName": "$ARDTT_MIN_INSTALL_VERSION_NAME",
  "minVersionCode": int("$ARDTT_MIN_INSTALL_VERSION_CODE"),
  "note": "Do not install GitHub releases/latest (v0.5.264 / 283). pull_request artifact SHA is the merge commit, not the PR head.",
  "downloadedAtUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
open("$OUT_DIR/provenance.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(doc, ensure_ascii=False, indent=2))
PY
printf '%s\n' "$APK"
