#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/server/ardttctl"
go test ./...
go vet ./...
# Dual protocol is what old APKs must still see.
bin="$(mktemp)"
trap 'rm -f "$bin"' EXIT
GOOS="$(go env GOOS)" GOARCH="$(go env GOARCH)" CGO_ENABLED=0 go build -o "$bin" .
out="$("$bin" emit progress --frac 0.5 --message step --phase fetch)"
echo "$out" | grep -q '"protocol":2' || { echo "FAIL: jsonl missing"; exit 1; }
echo "$out" | grep -q 'ARDTT_PROGRESS|0.5|step' || { echo "FAIL: legacy missing"; exit 1; }
err="$("$bin" emit error --code DISK_FULL --message full)"
echo "$err" | grep -q 'ARDTT_ERROR|code=DISK_FULL|full' || { echo "FAIL: error code"; exit 1; }
done_json="$("$bin" emit done --no-legacy --dry_run 1 --install_dir /opt/ardtt)"
echo "$done_json" | grep -q '"protocol":2' || { echo "FAIL: done json"; exit 1; }
echo "$done_json" | grep -q 'ARDTT_DONE' && { echo "FAIL: --no-legacy still printed ARDTT_DONE"; exit 1; }
echo "OK test-ardttctl"
