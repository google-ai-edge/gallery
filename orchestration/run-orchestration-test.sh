#!/bin/bash
# Orchestration On-Device Test — XML-based (device-independent)
# Usage: ./run-orchestration-test.sh [-d <device-serial>] [scenario-name]
#
# Runs orchestration-specific scenarios on a connected device.
# If no scenario is given, runs all scenarios from ORCHESTRATION-TEST-PLAN.md.
#
# Uses the same lib-device-helpers.sh infrastructure as run-device-test.sh.
# No hardcoded coordinates — works on any device resolution.

DEVICE_SERIAL=""

while getopts "d:" opt; do
  case $opt in
    d) DEVICE_SERIAL="$OPTARG" ;;
    *) echo "Usage: $0 [-d <device-serial>] [scenario-name]"; exit 1 ;;
  esac
done
shift $((OPTIND - 1))

SCENARIO_NAME="$1"

if [ -n "$DEVICE_SERIAL" ]; then
  ADB="adb -s $DEVICE_SERIAL"
else
  ADB="adb"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCREENSHOTS_DIR="$SCRIPT_DIR/screenshots/orchestration"
TEST_PLAN="$SCRIPT_DIR/ORCHESTRATION-TEST-PLAN.md"
UI_XML="/tmp/ui_orch_${DEVICE_SERIAL:-default}.xml"

# We need SKILL_NAME and SKILL_PATH set for lib-device-helpers (even if unused)
SKILL_NAME=""
SKILL_PATH=""

# Load shared helpers
source "$SCRIPT_DIR/lib-device-helpers.sh"

mkdir -p "$SCREENSHOTS_DIR"

# =============================================
# Parse test plan
# =============================================

parse_scenarios() {
  # Parse ORCHESTRATION-TEST-PLAN.md table rows (skip header and separator)
  python3 -c "
import sys
with open('$TEST_PLAN') as f:
    lines = f.readlines()
for line in lines:
    line = line.strip()
    if not line.startswith('|'): continue
    cols = [c.strip() for c in line.split('|')]
    if len(cols) < 7: continue
    # Skip header and separator
    if cols[1] == '#' or cols[1].startswith('-'): continue
    try:
        int(cols[1])
    except ValueError:
        continue
    # num, scenario, skills, prompt, pass_pattern, timeout
    print(f'{cols[2]}|{cols[3]}|{cols[4]}|{cols[5]}|{cols[6]}')
"
}

# =============================================
# Run a single scenario
# =============================================

run_scenario() {
  local scenario="$1"
  local skills="$2"
  local prompt="$3"
  local pass_pattern="$4"
  local timeout="$5"

  echo ""
  echo "=========================================="
  echo "Orchestration Test: $scenario"
  echo "=========================================="
  echo "Skills: $skills"
  echo "Prompt: \"$prompt\""
  echo "Pass pattern: \"$pass_pattern\""
  echo "Timeout: ${timeout}s"
  echo ""

  # --- Step 1: Fresh app, navigate to chat ---
  echo -n "[1/4] Setup... "
  $ADB get-state >/dev/null 2>&1 || { echo "FAIL - No device"; return 1; }
  fresh_app
  dump_ui
  if ! ui_has "Type prompt" && ! ui_has "Prompt input"; then
    echo "FAIL - Could not reach chat screen"
    take_screenshot "$SCREENSHOTS_DIR/${scenario}_setup_fail.png"
    return 1
  fi
  echo "OK"

  # --- Step 2: Import and enable required skills ---
  echo -n "[2/4] Skills... "
  if [ -n "$skills" ] && [ "$skills" != "(none)" ]; then
    IFS=',' read -ra SKILL_LIST <<< "$skills"
    for skill in "${SKILL_LIST[@]}"; do
      skill=$(echo "$skill" | xargs)  # trim whitespace
      # Set globals for import_skill helper
      SKILL_NAME="$skill"
      SKILL_PATH="/sdcard/Download/$skill"

      # Check if skill folder exists on device
      if $ADB shell "[ -d /sdcard/Download/$skill ]" 2>/dev/null; then
        import_skill
        echo -n "($skill imported) "
      else
        echo -n "($skill NOT on device — skipping) "
      fi
    done
  fi
  echo "OK"

  # --- Step 3: Send prompt ---
  echo -n "[3/4] Testing... "
  send_prompt "$prompt"

  # Poll for pass pattern with extended timeout (orchestration has multiple LLM round-trips)
  local PASSED=false
  if poll_ui "$pass_pattern" "$timeout" 5; then
    PASSED=true
    echo "PASS"
  else
    echo "FAIL (timeout waiting for \"$pass_pattern\")"
  fi

  # --- Step 4: Capture results ---
  echo -n "[4/4] Results... "
  sleep 3
  dump_ui
  take_screenshot "$SCREENSHOTS_DIR/${scenario}.png"

  # Show UI state
  echo ""
  echo "--- UI State ---"
  ui_text | grep -v "^Type prompt" | grep -v "^Skills$" | grep -v "^Agent Chat$" \
    | grep -v "^Model on CPU$" | grep -v "^+Image$" | grep -v "^+Audio$" | head -30
  echo ""

  # Check for orchestration-specific elements
  echo "--- Orchestration Elements ---"
  if ui_has "Plan"; then echo "  [x] Plan card visible"; else echo "  [ ] Plan card NOT visible"; fi
  if ui_has "Goal achieved"; then echo "  [x] Goal achieved"; fi
  if ui_has "Evaluation"; then echo "  [x] Evaluation visible"; fi
  if ui_has "Completed"; then echo "  [x] Completed steps"; fi
  if ui_has "Called JS script"; then echo "  [x] JS script called"; fi
  if ui_has "Calling JS script"; then echo "  [x] JS script in progress"; fi
  echo ""

  echo "Screenshot: $SCREENSHOTS_DIR/${scenario}.png"

  if [ "$PASSED" = true ]; then
    echo "RESULT: PASS"
    return 0
  else
    echo "RESULT: FAIL"
    return 1
  fi
}

# =============================================
# Main
# =============================================

DEVICE_MODEL=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "=========================================="
echo "Orchestration On-Device Test Runner"
echo "=========================================="
echo "Device: $DEVICE_MODEL (${DEVICE_SERIAL:-default})"
echo ""

TOTAL=0
PASSED=0
FAILED=0
FAIL_LIST=""

# Read all scenarios into an array first (avoids stdin consumption by ADB commands)
SCENARIOS=()
while IFS= read -r line; do
  SCENARIOS+=("$line")
done < <(parse_scenarios)

if [ -n "$SCENARIO_NAME" ]; then
  # Run a single named scenario
  FOUND=false
  for entry in "${SCENARIOS[@]}"; do
    IFS='|' read -r scenario skills prompt pass_pattern timeout <<< "$entry"
    if [ "$scenario" = "$SCENARIO_NAME" ]; then
      FOUND=true
      TOTAL=$((TOTAL + 1))
      if run_scenario "$scenario" "$skills" "$prompt" "$pass_pattern" "$timeout"; then
        PASSED=$((PASSED + 1))
      else
        FAILED=$((FAILED + 1))
        FAIL_LIST="$FAIL_LIST $scenario"
      fi
    fi
  done

  if [ "$FOUND" = false ]; then
    echo "ERROR: Scenario '$SCENARIO_NAME' not found in $TEST_PLAN"
    echo ""
    echo "Available scenarios:"
    for entry in "${SCENARIOS[@]}"; do echo "$entry" | cut -d'|' -f1; done
    exit 1
  fi
else
  # Run all scenarios
  for entry in "${SCENARIOS[@]}"; do
    IFS='|' read -r scenario skills prompt pass_pattern timeout <<< "$entry"
    TOTAL=$((TOTAL + 1))
    if run_scenario "$scenario" "$skills" "$prompt" "$pass_pattern" "$timeout"; then
      PASSED=$((PASSED + 1))
    else
      FAILED=$((FAILED + 1))
      FAIL_LIST="$FAIL_LIST $scenario"
    fi
  done
fi

# =============================================
# Summary
# =============================================

echo ""
echo "=========================================="
echo "ORCHESTRATION TEST SUMMARY ($DEVICE_MODEL)"
echo "=========================================="
echo "Total:  $TOTAL"
echo "Passed: $PASSED"
echo "Failed: $FAILED"
if [ -n "$FAIL_LIST" ]; then
  echo "Failed:$FAIL_LIST"
fi
echo "Screenshots: $SCREENSHOTS_DIR/"
echo ""

if [ "$FAILED" -gt 0 ]; then
  exit 1
else
  exit 0
fi
