#!/bin/bash
# Edge Gallery On-Device Skill Test — XML-based (device-independent)
# Usage: ./run-device-test.sh [-d <device-serial>] [-s <skills-dir>] <skill-name>
#
# Uses uiautomator XML dumps to find UI elements dynamically.
# No hardcoded coordinates — works on any device resolution.

DEVICE_SERIAL=""
SKILLS_SUBDIR="skills"

while getopts "d:s:" opt; do
  case $opt in
    d) DEVICE_SERIAL="$OPTARG" ;;
    s) SKILLS_SUBDIR="$OPTARG" ;;
    *) echo "Usage: $0 [-d <device-serial>] [-s <skills-dir>] <skill-name>"; exit 1 ;;
  esac
done
shift $((OPTIND - 1))

SKILL_NAME="${1:?Usage: $0 [-d <device-serial>] [-s <skills-dir>] <skill-name>}"

if [ -n "$DEVICE_SERIAL" ]; then
  ADB="adb -s $DEVICE_SERIAL"
else
  ADB="adb"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$SCRIPT_DIR/$SKILLS_SUBDIR"
SCREENSHOTS_DIR="$SCRIPT_DIR/screenshots"
TEST_PLAN="$SCRIPT_DIR/TEST-PLAN.md"
SKILL_PATH="$SKILLS_DIR/$SKILL_NAME"
UI_XML="/tmp/ui_${DEVICE_SERIAL:-default}.xml"

if [ ! -d "$SKILL_PATH" ]; then
  echo "ERROR: Skill '$SKILL_NAME' not found in $SKILLS_DIR"
  exit 1
fi

# Load test prompt from TEST-PLAN.md
PROMPT1=$(python3 -c "
with open('$TEST_PLAN') as f:
    for line in f:
        line = line.strip()
        if '| $SKILL_NAME |' in line:
            cols = [c.strip() for c in line.split('|')]
            if len(cols) >= 5:
                prompt = cols[3]
                if prompt and prompt.lower() != 'test prompt':
                    print(prompt)
            break
")

if [ -z "$PROMPT1" ]; then
  echo "ERROR: No test prompt found for '$SKILL_NAME' in TEST-PLAN.md"
  exit 1
fi

DEVICE_MODEL=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "Device: $DEVICE_MODEL (${DEVICE_SERIAL:-default})"
echo "Prompt: \"$PROMPT1\""

# Load shared helpers
source "$SCRIPT_DIR/lib-device-helpers.sh"

# =============================================
# Device-test-specific helpers
# =============================================

# Find Delete button near a skill name
find_skill_delete() {
  python3 -c "
import xml.etree.ElementTree as ET, re, sys
tree = ET.parse('$UI_XML')
nodes = list(tree.iter('node'))
for i, node in enumerate(nodes):
    if node.get('text', '') == '''$1''':
        for j in range(i, min(i+15, len(nodes))):
            if nodes[j].get('text', '') == 'Delete':
                m = re.findall(r'\d+', nodes[j].get('bounds', ''))
                if m:
                    print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
                    sys.exit(0)
        break
"
}

filter_response() {
  echo "$1" | grep -v "^Type prompt" | grep -v "^Skills$" | grep -v "^Agent Chat$" \
    | grep -v "^Model on CPU$" | grep -v "^+Image$" | grep -v "^+Audio$" | grep -v "^Input history$"
}

# =============================================
# Test execution
# =============================================

mkdir -p "$SCREENSHOTS_DIR/$SKILL_NAME"

echo "=========================================="
echo "Scenario Test: $SKILL_NAME"
echo "=========================================="

# --- Step 1: Push skill to device ---
echo -n "[1/5] Setup... "
$ADB get-state >/dev/null 2>&1 || { echo "FAIL - No device"; exit 1; }
$ADB shell rm -rf "/sdcard/Download/$SKILL_NAME" >/dev/null 2>&1
$ADB shell mkdir -p "/sdcard/Download/$SKILL_NAME" >/dev/null 2>&1
$ADB push "$SKILL_PATH/SKILL.md" "/sdcard/Download/$SKILL_NAME/SKILL.md" >/dev/null 2>&1
$ADB push "$SKILL_PATH/scripts" "/sdcard/Download/$SKILL_NAME/scripts" >/dev/null 2>&1
if [ -d "$SKILL_PATH/assets" ]; then
  $ADB push "$SKILL_PATH/assets" "/sdcard/Download/$SKILL_NAME/assets" >/dev/null 2>&1
fi
echo "OK"

# --- Step 2: Baseline (no skill) ---
echo -n "[2/5] Baseline... "
fresh_app
dump_ui
if ! ui_has "Type prompt" && ! ui_has "Prompt input"; then
  echo "ERROR: Failed to reach chat screen"; exit 1
fi

# Disable all skills
tap_element "Skills"; sleep 1
dump_ui; tap_element "Turn off all" || tap_element "Deselect all"; sleep 0.5
dump_ui; tap_element "Close"; sleep 0.5

send_prompt "$PROMPT1"
sleep 25; dump_ui
BASELINE=$(ui_text)
take_screenshot "$SCREENSHOTS_DIR/$SKILL_NAME/disabled.png"
echo "Done"
echo ""
echo "--- BASELINE ---"
filter_response "$BASELINE"
echo ""

# --- Step 3: Install skill ---
echo -n "[3/5] Installing... "
fresh_app
reset_session
import_skill
echo "OK"

# --- Step 4: Enabled test ---
echo ""
echo -n "[4/5] Testing: \"$PROMPT1\"... "

dump_ui
if ! ui_has "Type prompt" && ! ui_has "Prompt input"; then
  navigate_to_chat; dump_ui
fi

send_prompt "$PROMPT1"

PASSED=false
if poll_ui "Called JS script" 60 3; then
  sleep 5; dump_ui
  RESPONSE=$(ui_text)
  echo "PASS"
  PASSED=true

  echo "--- Response ---"
  filter_response "$RESPONSE"
  take_screenshot "$SCREENSHOTS_DIR/$SKILL_NAME/enabled.png"
  echo "Screenshot: screenshots/$SKILL_NAME/enabled.png"
else
  echo "FAIL (timeout)"
  dump_ui
  echo "--- Last state ---"
  ui_text
  take_screenshot "$SCREENSHOTS_DIR/$SKILL_NAME/failure.png"
fi

# --- Step 5: Summary ---
echo ""
echo "=========================================="
echo "SUMMARY: $SKILL_NAME ($DEVICE_MODEL)"
echo "=========================================="
echo "Screenshots: screenshots/$SKILL_NAME/"
ls "$SCREENSHOTS_DIR/$SKILL_NAME/" 2>/dev/null

if [ "$PASSED" = true ]; then
  echo ""; echo "TEST PASSED"; exit 0
else
  echo ""; echo "TEST FAILED"; exit 1
fi
