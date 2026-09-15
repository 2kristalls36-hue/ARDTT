#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
need=(
  Setup-ARDTT-TestLab.ps1
  Doctor-ARDTT.ps1
  Doctor-ARDTT.sh
  Build-ARDTT.ps1
  Build-ARDTT.sh
  Install-ARDTT.ps1
  Start-ARDTT-Diagnostics.ps1
  Mark-ARDTT-Event.ps1
  Capture-ARDTT-State.ps1
  Stop-ARDTT-Diagnostics.ps1
  Test-ARDTT-Scenario.ps1
  Test-ARDTT-Doze.ps1
  Restore-ARDTT-TestState.ps1
  README-Windows-USB.md
  HANDOFF-LOCAL-AGENT.md
  lab-config.example.json
  scenarios.json
  Install-LinuxToolchain.sh
  Fetch-PreviewApk.sh
  lib/ArdttLab.ps1
  lib/redact.py
  lib/pack-session.sh
  lib/capture-state.sh
  lib/capture-commands.json
  lib/capture-screenshot.sh
  lib/start-logcat.sh
  lib/stop-logcat.sh
)
FAIL=0
for f in "${need[@]}"; do
  if [[ ! -e "$LAB/$f" ]]; then
    echo "FAIL missing $f" >&2
    FAIL=1
  else
    echo "OK $f"
  fi
done
# wrappers must not contain TODO in primary flow
if grep -n 'TODO' "$LAB"/*.ps1 "$LAB"/*.sh "$LAB"/lib/*.ps1 "$LAB"/lib/*.sh 2>/dev/null | grep -v tests; then
  echo "FAIL TODO in lab scripts" >&2
  FAIL=1
else
  echo "OK no TODO markers"
fi
if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi
echo "required-files tests passed"
