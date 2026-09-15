#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
python3 - "$LAB/scenarios.json" <<'PY'
import json, sys
from pathlib import Path
doc = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
ids = [s["id"] for s in doc["scenarios"]]
want = [f"S{i:02d}" for i in range(0, 13)]
assert ids == want, (ids, want)
for s in doc["scenarios"]:
    assert s.get("title") and s.get("needs") and s.get("checks")
print("scenarios", ",".join(ids))
PY
echo "scenarios tests passed"
