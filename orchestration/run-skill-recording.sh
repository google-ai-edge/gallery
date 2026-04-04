#!/bin/bash
# Record a video of a skill working with two example prompts in one chat session.
# Fully XML-based UI interaction — works on any device/resolution.
#
# Usage: ./run-skill-recording.sh [-s device_serial] <skill-name> [prompt1] [prompt2]
#
# Video is saved to recording/<skill-name>/recording.mp4

# Parse optional -s flag for device serial
DEVICE_SERIAL=""
if [ "$1" = "-s" ]; then
  DEVICE_SERIAL="$2"
  shift 2
fi

SKILL_NAME="${1:?Usage: ./run-skill-recording.sh [-s device_serial] <skill-name> [prompt1] [prompt2]}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -n "$DEVICE_SERIAL" ]; then
  ADB="adb -s $DEVICE_SERIAL"
  UI_DUMP_LOCAL="/tmp/ui_${DEVICE_SERIAL}.xml"
else
  ADB="adb"
  UI_DUMP_LOCAL="/tmp/ui.xml"
fi

# Auto-detect skill in skills/, new_skills/skills/, or new_skills/
if [ -d "$SCRIPT_DIR/skills/$SKILL_NAME" ]; then
  SKILLS_DIR="$SCRIPT_DIR/skills"
elif [ -d "$SCRIPT_DIR/new_skills/skills/$SKILL_NAME" ]; then
  SKILLS_DIR="$SCRIPT_DIR/new_skills/skills"
elif [ -d "$SCRIPT_DIR/new_skills/$SKILL_NAME" ]; then
  SKILLS_DIR="$SCRIPT_DIR/new_skills"
else
  SKILLS_DIR="$SCRIPT_DIR/skills"
fi
SKILL_PATH="$SKILLS_DIR/$SKILL_NAME"
SKILL_MD="$SKILL_PATH/SKILL.md"
# Save recordings next to the skills directory
if echo "$SKILLS_DIR" | grep -q "new_skills"; then
  RECORDING_DIR="$SCRIPT_DIR/new_skills/recording/$SKILL_NAME"
else
  RECORDING_DIR="$SCRIPT_DIR/recording/$SKILL_NAME"
fi
DEVICE_VIDEO="/data/local/tmp/skill_recording.mp4"

if [ ! -d "$SKILL_PATH" ]; then
  echo "ERROR: Skill '$SKILL_NAME' not found in $SKILLS_DIR"
  exit 1
fi

# Load two example prompts from SKILL.md if not provided as arguments
if [ -n "$2" ] && [ -n "$3" ]; then
  PROMPT1="$2"
  PROMPT2="$3"
else
  EXAMPLES=$(python3 -c "
import re
examples = []
in_examples = False
with open('$SKILL_MD') as f:
    for line in f:
        if line.strip().startswith('## Examples'):
            in_examples = True
            continue
        if in_examples:
            if line.strip().startswith('## '):
                break
            m = re.match(r'^\s*\*\s*\"(.+)\"', line.strip())
            if m:
                examples.append(m.group(1))
for e in examples[:2]:
    print(e)
")
  PROMPT1=$(echo "$EXAMPLES" | sed -n '1p')
  PROMPT2=$(echo "$EXAMPLES" | sed -n '2p')
fi

if [ -z "$PROMPT1" ] || [ -z "$PROMPT2" ]; then
  echo "ERROR: Need two example prompts. Provide as arguments or add to SKILL.md."
  exit 1
fi

echo "Skill:    $SKILL_NAME"
echo "Prompt 1: \"$PROMPT1\""
echo "Prompt 2: \"$PROMPT2\""
[ -n "$DEVICE_SERIAL" ] && echo "Device:   $DEVICE_SERIAL"
echo ""

# =============================================
# Helpers — all UI interaction is XML-based
# =============================================
tap() { $ADB shell input tap $1 $2; }

type_text() {
  local encoded=$(echo "$1" | sed 's/ /%s/g; s/(/\\(/g; s/)/\\)/g; s/&/\\&/g; s/;/\\;/g; s/|/\\|/g; s/</\\</g; s/>/\\>/g; s/"/\\"/g; s/'"'"'/\\'"'"'/g; s/#/\\#/g; s/\$/\\$/g')
  $ADB shell input text "$encoded"
}

dump_ui() {
  $ADB shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  $ADB pull /data/local/tmp/ui.xml "$UI_DUMP_LOCAL" >/dev/null 2>&1
}

ui_has() { grep -q "$1" "$UI_DUMP_LOCAL" 2>/dev/null; }

ui_find_bounds() {
  python3 -c "
import xml.etree.ElementTree as ET, re
tree = ET.parse('$UI_DUMP_LOCAL')
for node in tree.iter('node'):
    if node.get('text', '') == '$1' or node.get('content-desc', '') == '$1':
        m = re.findall(r'\d+', node.get('bounds', ''))
        if m:
            print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
            break
"
}

# Tap element by text or content-desc. Returns 0 if found, 1 if not.
tap_by() {
  local COORDS=$(ui_find_bounds "$1")
  if [ -n "$COORDS" ]; then tap $COORDS; return 0; fi
  return 1
}

scroll_down() { $ADB shell input swipe 540 1800 540 1000 200; }

poll_ui() {
  local pattern="$1" timeout_s="${2:-60}" interval="${3:-3}" elapsed=0
  while [ $elapsed -lt $timeout_s ]; do
    dump_ui
    if ui_has "$pattern"; then return 0; fi
    sleep "$interval"
    elapsed=$((elapsed + interval))
  done
  return 1
}

# =============================================
# Navigation
# =============================================
navigate_to_chat() {
  dump_ui
  # Already on chat screen
  if ui_has "Prompt input" || ui_has "Type prompt"; then return 0; fi
  # Model selection screen — tap "Try it"
  if ui_has "Try it"; then
    tap_by "Try it"; sleep 2; return 0
  fi
  # Home screen — tap Experimental tab first if visible, then find Agent Chat
  if ui_has "Experimental"; then
    tap_by "Experimental"; sleep 1; dump_ui
  fi
  if ui_has "Agent Chat task"; then
    tap_by "Agent Chat task with 2 models"; sleep 3; dump_ui
    if ui_has "Try it"; then tap_by "Try it"; sleep 2; fi
    return 0
  fi
  # Fallback: go back and relaunch
  dump_ui; tap_by "Go back"; sleep 0.5; tap_by "Go back"; sleep 0.5
  $ADB shell am start -n com.google.ai.edge.gallery.dev/com.google.ai.edge.gallery.MainActivity >/dev/null 2>&1
  sleep 3; dump_ui
  if ui_has "Agent Chat task"; then
    tap_by "Agent Chat task with 2 models"; sleep 3; dump_ui
    if ui_has "Try it"; then tap_by "Try it"; sleep 2; fi
  fi
}

dismiss_overlay() {
  dump_ui
  if ui_has "Introducing"; then
    # Tap center of screen to dismiss the intro overlay
    $ADB shell input tap 540 1400; sleep 1
  fi
}

reset_session() {
  dump_ui
  tap_by "Reset session"; sleep 0.5; dump_ui
  if ui_has "Confirm" || ui_has "Reset" || ui_has "Yes"; then
    tap_by "Confirm" || tap_by "Reset" || tap_by "Yes"
    sleep 0.5
  fi
}

send_prompt() {
  dump_ui
  tap_by "Type prompt" || tap_by "Prompt input"
  sleep 0.5
  type_text "$1"; sleep 0.3
  dump_ui
  tap_by "Send prompt"; sleep 0.3
}

open_skills_panel() {
  dump_ui
  if ui_has "Manage skills"; then return 0; fi
  tap_by "Skills"; sleep 1; dump_ui
}

close_skills_panel() {
  dump_ui
  tap_by "Close"; sleep 0.5
}

# =============================================
# Skill management
# =============================================
find_skill_toggle() {
  python3 -c "
import xml.etree.ElementTree as ET, re
tree = ET.parse('$UI_DUMP_LOCAL')
nodes = list(tree.iter('node'))
for i, node in enumerate(nodes):
    if node.get('text', '') == '$SKILL_NAME':
        for j in range(max(0,i-5), min(i+5, len(nodes))):
            if nodes[j].get('checkable', '') == 'true':
                m = re.findall(r'\d+', nodes[j].get('bounds', ''))
                if m: print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
                break
        break
"
}

find_skill_delete_btn() {
  python3 -c "
import xml.etree.ElementTree as ET, re
tree = ET.parse('$UI_DUMP_LOCAL')
nodes = list(tree.iter('node'))
for i, node in enumerate(nodes):
    if node.get('text', '') == '$SKILL_NAME':
        for j in range(i, min(i+10, len(nodes))):
            if nodes[j].get('text', '') == 'Delete':
                m = re.findall(r'\d+', nodes[j].get('bounds', ''))
                if m: print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
                break
        break
"
}

pick_folder_in_browser() {
  # Navigate the system file picker to select the skill folder
  # Handles: already in correct folder, in Download, in wrong folder, etc.
  dump_ui

  # Already showing the skill folder contents
  if ui_has "Files in $SKILL_NAME"; then return 0; fi

  # In Download folder — tap skill name
  if ui_has "Files in Download"; then
    tap_by "$SKILL_NAME"; sleep 1; return 0
  fi

  # In some other folder — navigate up to Download via breadcrumb
  if ui_has "Download"; then
    tap_by "Download"; sleep 1; dump_ui
    if ui_has "$SKILL_NAME"; then
      tap_by "$SKILL_NAME"; sleep 1; return 0
    fi
  fi

  return 1
}

import_skill() {
  # Clean Download folder and push skill
  $ADB shell "rm -rf /sdcard/Download/*" >/dev/null 2>&1
  $ADB push "$SKILL_PATH" "/sdcard/Download/$SKILL_NAME" >/dev/null 2>&1

  open_skills_panel

  # Delete old version if exists
  FOUND=false
  for attempt in 1 2 3 4; do
    dump_ui
    if ui_has "$SKILL_NAME"; then FOUND=true; break; fi
    scroll_down; sleep 0.5
  done
  if [ "$FOUND" = true ]; then
    for attempt in 1 2 3; do
      dump_ui; DELETE_COORDS=$(find_skill_delete_btn)
      if [ -n "$DELETE_COORDS" ]; then
        tap $DELETE_COORDS; sleep 0.5; dump_ui
        if ui_has "Delete skill"; then
          tap_by "Delete"; sleep 0.5
        fi
        break
      fi
      scroll_down; sleep 0.5
    done
  fi

  # Open Add → Import local skill
  open_skills_panel
  dump_ui; tap_by "Add"; sleep 1
  dump_ui; tap_by "Import local skill"; sleep 1.5

  dump_ui
  # Check if skill name is already shown (Pixel new-style picker)
  if ui_has "Pick skill" && ui_has "$SKILL_NAME" && ! ui_has "No directory"; then
    tap_by "$SKILL_NAME"; sleep 0.5
    dump_ui; tap_by "Add"; sleep 1.5
  else
    # Need to use file picker (Samsung flow or no skill pre-selected)
    tap_by "Pick file"; sleep 2

    pick_folder_in_browser
    dump_ui

    # Tap "USE THIS FOLDER" / "Use this folder" (case-insensitive grep)
    if grep -qi "use this folder" "$UI_DUMP_LOCAL" 2>/dev/null; then
      # Find the button by searching for text containing "USE THIS FOLDER" or "Use this folder"
      USE_FOLDER=$(python3 -c "
import xml.etree.ElementTree as ET, re
tree = ET.parse('$UI_DUMP_LOCAL')
for node in tree.iter('node'):
    t = node.get('text', '')
    if 'use this folder' in t.lower():
        m = re.findall(r'\d+', node.get('bounds', ''))
        if m: print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
        break
")
      [ -n "$USE_FOLDER" ] && tap $USE_FOLDER && sleep 1
    fi

    # Handle "Allow" / "ALLOW" permission dialog
    dump_ui
    if ui_has "Allow" && ui_has "Edge Gallery"; then
      ALLOW=$(python3 -c "
import xml.etree.ElementTree as ET, re
tree = ET.parse('$UI_DUMP_LOCAL')
for node in tree.iter('node'):
    t = node.get('text', '')
    if t.upper() == 'ALLOW':
        m = re.findall(r'\d+', node.get('bounds', ''))
        if m: print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
        break
")
      [ -n "$ALLOW" ] && tap $ALLOW && sleep 1
    fi

    # Now back to import dialog with skill selected — tap Add
    dump_ui
    if ui_has "Add"; then tap_by "Add"; sleep 1.5; fi
  fi

  # Handle Replace dialog
  dump_ui
  if ui_has "Replace existing"; then
    tap_by "Replace"; sleep 1.5
  fi

  echo "Imported '$SKILL_NAME'"
}

setup_skill() {
  open_skills_panel

  # Check if skill is installed
  INSTALLED=false
  for attempt in $(seq 1 15); do
    dump_ui
    if ui_has "$SKILL_NAME"; then INSTALLED=true; break; fi
    scroll_down; sleep 0.5
  done

  close_skills_panel

  if [ "$INSTALLED" = false ]; then
    echo -n "Skill not installed, importing... "
    import_skill
  fi

  # Re-open to enable
  open_skills_panel

  # Deselect all
  dump_ui; tap_by "Deselect all"; sleep 0.5

  # Scroll to find and enable target skill
  TOGGLE=""
  for attempt in $(seq 1 15); do
    dump_ui
    TOGGLE=$(find_skill_toggle)
    if [ -n "$TOGGLE" ]; then
      tap $TOGGLE; sleep 0.3
      echo "Enabled '$SKILL_NAME'"
      break
    fi
    scroll_down; sleep 0.5
  done

  if [ -z "$TOGGLE" ]; then
    echo "ERROR: Could not find skill toggle for '$SKILL_NAME'"
    close_skills_panel
    return 1
  fi

  close_skills_panel
  return 0
}

# =============================================
# Video recording
# =============================================
start_video() {
  $ADB shell rm -f "$DEVICE_VIDEO" >/dev/null 2>&1
  $ADB shell screenrecord --size 720x1560 "$DEVICE_VIDEO" &
  RECORD_PID=$!
  sleep 1
  echo "Screen recording started (PID $RECORD_PID)"
}

stop_video() {
  $ADB shell pkill -INT screenrecord >/dev/null 2>&1
  sleep 2
  wait $RECORD_PID 2>/dev/null
  mkdir -p "$RECORDING_DIR"
  $ADB pull "$DEVICE_VIDEO" "$RECORDING_DIR/recording.mp4" >/dev/null 2>&1
  $ADB shell rm -f "$DEVICE_VIDEO" >/dev/null 2>&1
  echo "Video saved: recording/$SKILL_NAME/recording.mp4"
}

# =============================================
# Main
# =============================================

echo "=========================================="
echo "Recording: $SKILL_NAME"
echo "=========================================="

# --- Step 1: Device check ---
echo -n "[1/5] Checking device... "
$ADB get-state >/dev/null 2>&1 || { echo "FAIL - No device"; exit 1; }
echo "OK"

# --- Step 2: Fresh app, navigate, setup skill ---
echo -n "[2/5] Setting up... "
$ADB shell am force-stop com.google.ai.edge.gallery.dev >/dev/null 2>&1
sleep 1
$ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
$ADB shell input keyevent KEYCODE_MENU >/dev/null 2>&1
sleep 0.5
$ADB shell am start --activity-clear-task -n com.google.ai.edge.gallery.dev/com.google.ai.edge.gallery.MainActivity >/dev/null 2>&1
sleep 3
navigate_to_chat
dismiss_overlay
dump_ui
if ! ui_has "Prompt input" && ! ui_has "Type prompt"; then
  echo "ERROR: Not on chat screen."
  exit 1
fi
reset_session
sleep 1
setup_skill || exit 1
echo "OK"

# --- Step 3: Start recording ---
echo ""
echo "[3/5] Starting video recording..."
start_video

# --- Step 4: Example 1 ---
echo ""
echo -n "[4/5] Example 1: \"$PROMPT1\"... "
send_prompt "$PROMPT1"

PASS1=false
if poll_ui "Called JS script" 60 3; then
  sleep 5
  echo "PASS"
  PASS1=true
else
  echo "FAIL (timeout)"
fi

# --- Step 5: Example 2 ---
echo ""
echo -n "[5/5] Example 2: \"$PROMPT2\"... "

sleep 2
dump_ui
if ! ui_has "Prompt input" && ! ui_has "Type prompt"; then
  scroll_down; sleep 0.5
fi

send_prompt "$PROMPT2"

sleep 5
poll_ui "Calling JS script" 30 3 >/dev/null 2>&1

PASS2=false
if poll_ui "Called JS script" 60 3; then
  sleep 5
  echo "PASS"
  PASS2=true
else
  echo "FAIL (timeout)"
fi

sleep 3

# --- Stop recording ---
echo ""
stop_video

# --- Summary ---
echo ""
echo "=========================================="
echo "SUMMARY: $SKILL_NAME"
echo "=========================================="
echo "Example 1: $([ "$PASS1" = true ] && echo 'PASS' || echo 'FAIL')"
echo "Example 2: $([ "$PASS2" = true ] && echo 'PASS' || echo 'FAIL')"
echo "Video:     recording/$SKILL_NAME/recording.mp4"

if [ "$PASS1" = true ] && [ "$PASS2" = true ]; then
  echo ""
  echo "RECORDING COMPLETE"
  exit 0
else
  echo ""
  echo "RECORDING INCOMPLETE - Some examples failed"
  exit 1
fi
