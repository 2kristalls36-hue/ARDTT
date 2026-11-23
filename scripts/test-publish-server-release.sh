#!/usr/bin/env bash
# publish-server-release.sh against a fake gh: an image already on the release
# is kept and its published sum stays; new files are uploaded with their sums.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="$(tr -d '[:space:]' < "$ROOT/server/DEPLOY_VERSION")"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }

mkdir -p "$W/bin" "$W/dist" "$W/release"
echo amd-new >"$W/dist/ardtt-server-${VER}-linux-amd64.image.tar.gz"
echo arm-new >"$W/dist/ardtt-server-${VER}-linux-arm64.image.tar.gz"
# Already published: amd64 image (other bytes) and its sum, plus an unrelated line.
echo amd-old >"$W/release/ardtt-server-${VER}-linux-amd64.image.tar.gz"
{
  (cd "$W/release" && sha256sum "ardtt-server-${VER}-linux-amd64.image.tar.gz")
  echo "0000000000000000000000000000000000000000000000000000000000000000  ardtt-server-1.9.0-linux-amd64.image.tar.gz"
} >"$W/release/SHA256SUMS-server.txt"
cat >"$W/bin/gh" <<GH
#!/usr/bin/env bash
R="$W/release"
case "\$1 \$2" in
  "release view")
    if [ "\${4:-}" = "--json" ]; then ls "\$R"; fi
    exit 0 ;;
  "release download") cp "\$R/\$5" "\$7"; exit 0 ;;
  "release upload") cp "\$4" "\$R/"; exit 0 ;;
  "release create") exit 0 ;;
esac
exit 1
GH
chmod 755 "$W/bin/gh"

PATH="$W/bin:$PATH" bash "$ROOT/scripts/publish-server-release.sh" --tag v9.9.9 --from-dir "$W/dist" >/dev/null 2>&1 \
  || err "publish exited non-zero"
[ "$(cat "$W/release/ardtt-server-${VER}-linux-amd64.image.tar.gz")" = amd-old ] || err "published amd64 image overwritten"
[ "$(cat "$W/release/ardtt-server-${VER}-linux-arm64.image.tar.gz")" = arm-new ] || err "arm64 image not uploaded"
cmp -s "$W/release/ardtt-deploy.sh" "$ROOT/server/deploy/ardtt-deploy.sh" || err "installer not uploaded"
(cd "$W/release" && sha256sum -c --quiet --ignore-missing SHA256SUMS-server.txt) || err "sums do not match the release files"
grep -q 'ardtt-server-1.9.0-linux-amd64' "$W/release/SHA256SUMS-server.txt" || err "unrelated sum line dropped"
[ "$fail" -eq 0 ] && echo "OK publish-server-release" || exit 1
