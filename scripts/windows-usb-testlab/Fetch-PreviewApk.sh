#!/usr/bin/env bash
# Download Preview APK artifact for a PR/run. Records provenance; does not publish.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/lib/common.sh"
ardtt_load_versions
ardtt_require_cmd gh
ardtt_require_cmd unzip

PR="${ARDTT_PR}"
RUN_ID=""
OUT_DIR=""
REPO="2kristalls36-hue/ARDTT"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pr) PR=$2; shift 2 ;;
    --run-id) RUN_ID=$2; shift 2 ;;
    --out) OUT_DIR=$2; shift 2 ;;
    --repo) REPO=$2; shift 2 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done
[[ -n "$OUT_DIR" ]] || ardtt_die "--out обязателен"
mkdir -p "$OUT_DIR"

if [[ -z "$RUN_ID" ]]; then
  RUN_ID="$(gh run list --repo "$REPO" --workflow "Preview APK" --limit 20 --json databaseId,headSha,conclusion,event,displayTitle,url \
    --jq "[.[] | select(.conclusion==\"success\")][0].databaseId")"
fi
[[ -n "$RUN_ID" && "$RUN_ID" != "null" ]] || ardtt_die "не найден успешный Preview APK run"

META="$(gh run view "$RUN_ID" --repo "$REPO" --json databaseId,headSha,event,url,displayTitle,conclusion,workflowName)"
HEAD_SHA="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["headSha"])' "$META")"
EVENT="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["event"])' "$META")"
URL="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["url"])' "$META")"

ardtt_log INFO "downloading artifacts of run $RUN_ID event=$EVENT headSha=$HEAD_SHA"
gh run download "$RUN_ID" --repo "$REPO" --dir "$OUT_DIR/raw"

APK="$(find "$OUT_DIR/raw" -type f -name '*.apk' | head -1)"
[[ -n "$APK" ]] || ardtt_die "в артефактах нет APK"

NAME="$(basename "$(dirname "$APK")")"
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
  "note": "pull_request workflows build a merge commit (GITHUB_SHA), not the raw PR head. Do not treat artifact zip name as proof of head SHA.",
  "downloadedAtUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
open("$OUT_DIR/provenance.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(doc, ensure_ascii=False, indent=2))
PY
printf '%s\n' "$APK"
