#!/usr/bin/env bash
# Terminal launcher for the ARDTT alpha unlock helper.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$root/alpha-unlock.py" "$@"
fi
if command -v python >/dev/null 2>&1; then
  exec python "$root/alpha-unlock.py" "$@"
fi
echo "Нужен Python 3: https://www.python.org/downloads/" >&2
exit 1
