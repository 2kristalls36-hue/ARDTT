#!/usr/bin/env bash
# Print a monotonic Android versionCode derived from git history.
#
# usage: compute-version-code.sh release
#        compute-version-code.sh preview <base_ref>
#
# release: code = $(git rev-list --count HEAD) * 10
# preview: base = $(git rev-list --count <merge-base HEAD base_ref>)
#          pr_commits = $(git rev-list --count <merge-base>..HEAD), capped at 9
#          code = base * 10 + min(pr_commits, 9)
#
# Preview с того же main всегда выше релиза этой базы (на 1..9), а следующий
# релиз main (count+1, затем *10) всегда выше любого preview. stdout — только
# число. code должен быть строго больше releaseVersionCode (нижняя граница)
# из android/app/build.gradle.kts текущего git-корня.
set -euo pipefail

usage() {
  echo "usage: compute-version-code.sh release | preview <base_ref>" >&2
  exit 2
}

case "${1:-}" in
  release)
    [ "$#" -eq 1 ] || usage
    count="$(git rev-list --count HEAD)"
    code=$((count * 10))
    ;;
  preview)
    [ "$#" -eq 2 ] || usage
    base_ref="$2"
    merge_base="$(git merge-base HEAD "$base_ref")"
    base="$(git rev-list --count "$merge_base")"
    pr_commits="$(git rev-list --count "${merge_base}..HEAD")"
    if [ "$pr_commits" -gt 9 ]; then
      pr_commits=9
    fi
    code=$((base * 10 + pr_commits))
    ;;
  *)
    usage
    ;;
esac

root="$(git rev-parse --show-toplevel)"
gradle="$root/android/app/build.gradle.kts"
floor="$(sed -n 's/.*releaseVersionCode = \([0-9][0-9]*\).*/\1/p' "$gradle" | head -n 1)"
if [ -z "$floor" ]; then
  echo "releaseVersionCode not found in $gradle" >&2
  exit 1
fi
if [ "$code" -le "$floor" ]; then
  echo "versionCode ${code} is not above releaseVersionCode floor ${floor}" >&2
  exit 1
fi
echo "$code"
