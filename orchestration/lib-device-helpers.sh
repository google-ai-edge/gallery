#!/bin/bash
# Shared helpers for on-device UI testing via ADB + uiautomator
# Source this file after setting: ADB, UI_XML, SKILL_NAME, SKILL_PATH
#
# Required variables:
#   ADB          - adb command (e.g. "adb" or "adb -s SERIAL")
#   UI_XML       - path to store UI XML dumps (e.g. /tmp/ui_default.xml)
#   SKILL_NAME   - name of the skill under test
#   SKILL_PATH   - local path to skill directory (only needed for import_skill)

_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# =============================================
# Device config — load calibrated coordinates
# =============================================

_load_device_config() {
  if [ -n "$_DEVICE_CONFIG_LOADED" ]; then return; fi

  local model conf_name conf_file
  model=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  conf_name=$(echo "$model" | tr '[:upper:]' '[:lower:]' | tr ' ' '-' | tr -cd 'a-z0-9-')
  conf_file="$_LIB_DIR/devices/${conf_name}.conf"

  if [ ! -f "$conf_file" ]; then
    echo ""
    echo "WARNING: No device config for '$model'."
    echo "Running calibration... (requires ANTHROPIC_API_KEY)"
    echo ""
    local serial_flag=""
    if [ -n "$DEVICE_SERIAL" ]; then serial_flag="-d $DEVICE_SERIAL"; fi
    "$_LIB_DIR/calibrate-device.sh" $serial_flag
    if [ ! -f "$conf_file" ]; then
      echo "ERROR: Calibration failed. Cannot continue without device config."
      echo "You can create one manually at: $conf_file"
      exit 1
    fi
  fi

  source "$conf_file"
  _DEVICE_CONFIG_LOADED=1
}

# =============================================
# XML-based helpers
# =============================================

dump_ui() {
  $ADB shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  $ADB pull /data/local/tmp/ui.xml "$UI_XML" >/dev/null 2>&1
}

find_element() {
  python3 -c "
import xml.etree.ElementTree as ET, re, sys
tree = ET.parse('$UI_XML')
target = '''$1'''
for node in tree.iter('node'):
    text = node.get('text', '')
    desc = node.get('content-desc', '')
    if text == target or desc == target:
        m = re.findall(r'\d+', node.get('bounds', ''))
        if m:
            print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
            sys.exit(0)
"
}

find_element_contains() {
  python3 -c "
import xml.etree.ElementTree as ET, re, sys
tree = ET.parse('$UI_XML')
target = '''$1'''.lower()
for node in tree.iter('node'):
    text = node.get('text', '').lower()
    desc = node.get('content-desc', '').lower()
    if target in text or target in desc:
        m = re.findall(r'\d+', node.get('bounds', ''))
        if m:
            print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
            sys.exit(0)
"
}

find_skill_toggle() {
  python3 -c "
import xml.etree.ElementTree as ET, re, sys
tree = ET.parse('$UI_XML')
nodes = list(tree.iter('node'))
skill_y = None
for node in nodes:
    if node.get('text', '') == '''$1''':
        m = re.findall(r'\d+', node.get('bounds', ''))
        if m: skill_y = (int(m[1]) + int(m[3])) // 2
        break
if skill_y is not None:
    best, best_dist = None, 999
    for node in nodes:
        if node.get('checkable', '') == 'true':
            m = re.findall(r'\d+', node.get('bounds', ''))
            if m:
                ty = (int(m[1]) + int(m[3])) // 2
                dist = abs(ty - skill_y)
                if dist < best_dist and dist < 100:
                    best_dist = dist
                    best = f'{(int(m[0])+int(m[2]))//2} {ty}'
    if best: print(best)
"
}

ui_has() { grep -qi "$1" "$UI_XML" 2>/dev/null; }

ui_text() {
  python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('$UI_XML')
for node in tree.iter('node'):
    t = node.get('text', '')
    if t and len(t) > 5: print(t[:500])
"
}

# Count occurrences of a pattern in UI XML
ui_count() { grep -oi "$1" "$UI_XML" 2>/dev/null | wc -l | tr -d ' '; }

tap() { $ADB shell input tap $1 $2; }

tap_element() {
  local coords
  coords=$(find_element "$1")
  if [ -n "$coords" ]; then
    tap $coords; return 0
  fi
  coords=$(find_element_contains "$1")
  if [ -n "$coords" ]; then
    tap $coords; return 0
  fi
  return 1
}

type_text() {
  local encoded=$(echo "$1" | sed 's/ /%s/g; s/(/\\(/g; s/)/\\)/g; s/&/\\&/g; s/;/\\;/g; s/|/\\|/g; s/</\\</g; s/>/\\>/g; s/"/\\"/g; s/'"'"'/\\'"'"'/g; s/#/\\#/g; s/\$/\\$/g')
  $ADB shell input text "$encoded"
}

# Scroll down in the skills panel list
scroll_down() {
  _load_device_config
  $ADB shell input swipe $SKILLS_SCROLL_X $SKILLS_SCROLL_FROM_Y $SKILLS_SCROLL_X $SKILLS_SCROLL_TO_Y 200
}

# Scroll chat content upward — used after model responses to see new content
scroll_up_from_bottom() {
  _load_device_config
  $ADB shell input swipe $CHAT_SCROLL_X $CHAT_SCROLL_FROM_Y $CHAT_SCROLL_X $CHAT_SCROLL_TO_Y 200
}

take_screenshot() {
  $ADB shell screencap -p /data/local/tmp/screenshot.png
  $ADB pull /data/local/tmp/screenshot.png "$1" >/dev/null 2>&1
}

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

# Poll until pattern appears at least N times
poll_ui_count() {
  local pattern="$1" min_count="$2" timeout_s="${3:-60}" interval="${4:-3}" elapsed=0
  while [ $elapsed -lt $timeout_s ]; do
    dump_ui
    local count=$(ui_count "$pattern")
    if [ "$count" -ge "$min_count" ] 2>/dev/null; then return 0; fi
    sleep "$interval"
    elapsed=$((elapsed + interval))
  done
  return 1
}

# =============================================
# Navigation helpers
# =============================================

navigate_to_chat() {
  dump_ui

  # Handle first-launch TOS dialog
  if ui_has "Accept" && ui_has "continue"; then
    tap_element "Accept & continue" || tap_element "Accept"; sleep 3
    dump_ui
  fi

  # Already on chat screen
  if ui_has "Type prompt" || ui_has "Prompt input"; then return 0; fi

  # On model page — Try it or Download
  if ui_has "Try it"; then
    tap_element "Try it"; sleep 5
    poll_ui "Type prompt" 60 3
    return 0
  fi
  if ui_has "Download" && ui_has "Gemma"; then
    echo -n "(downloading model) "
    tap_element "Download"; sleep 2
    poll_ui "Try it" 300 5
    dump_ui; tap_element "Try it"; sleep 5
    poll_ui "Type prompt" 60 3
    return 0
  fi

  # On intro screen
  if ui_has "Introducing"; then
    tap_element "Type prompt" || tap_element "Prompt input"; sleep 1
    dump_ui; if ui_has "Type prompt" || ui_has "Prompt input"; then return 0; fi
  fi

  # On home screen — navigate to Agent Skills
  if ui_has "Agent Skills" || ui_has "Explore other" || ui_has "Experimental"; then
    tap_element "Agent Skills" || tap_element "Experimental"; sleep 3
    dump_ui
    if ui_has "Try it"; then
      tap_element "Try it"; sleep 5
      poll_ui "Type prompt" 60 3
    elif ui_has "Download" && ui_has "Gemma"; then
      echo -n "(downloading model) "
      tap_element "Download"; sleep 2
      poll_ui "Try it" 300 5
      dump_ui; tap_element "Try it"; sleep 5
      poll_ui "Type prompt" 60 3
    fi
    return 0
  fi

  # Unknown state — relaunch
  $ADB shell am start --activity-clear-task -n com.google.ai.edge.gallery/.MainActivity >/dev/null 2>&1
  sleep 3
  dump_ui
  if ui_has "Accept" && ui_has "continue"; then
    tap_element "Accept & continue" || tap_element "Accept"; sleep 3
    dump_ui
  fi
  tap_element "Agent Skills" || tap_element "Experimental"; sleep 3
  dump_ui
  if ui_has "Try it"; then
    tap_element "Try it"; sleep 5
    poll_ui "Type prompt" 60 3
  elif ui_has "Download" && ui_has "Gemma"; then
    echo -n "(downloading model) "
    tap_element "Download"; sleep 2
    poll_ui "Try it" 300 5
    dump_ui; tap_element "Try it"; sleep 5
    poll_ui "Type prompt" 60 3
  fi
}

fresh_app() {
  $ADB shell am force-stop com.google.ai.edge.gallery >/dev/null 2>&1
  sleep 1
  $ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  $ADB shell input keyevent KEYCODE_MENU >/dev/null 2>&1
  sleep 0.5
  $ADB shell am start --activity-clear-task -n com.google.ai.edge.gallery/.MainActivity >/dev/null 2>&1
  sleep 3
  navigate_to_chat
}

reset_session() {
  dump_ui
  tap_element "Reset session"; sleep 0.5
  dump_ui
  tap_element "Confirm" || tap_element "Reset" || tap_element "Yes"
  sleep 0.5
}

send_prompt() {
  dump_ui
  tap_element "Type prompt" || tap_element "Prompt input"
  sleep 0.5
  type_text "$1"; sleep 0.3
  dump_ui
  tap_element "Send prompt" || { $ADB shell input keyevent 66; }
}

import_skill() {
  dump_ui; tap_element "Skills"; sleep 1.5
  dump_ui
  if ui_has "Manage skills"; then tap_element "Close"; sleep 0.5; fi
  dump_ui; tap_element "Skills"; sleep 1.5
  dump_ui; tap_element "Add"; sleep 1.5
  dump_ui; tap_element "Import local skill"; sleep 2
  dump_ui
  if ui_has "Agree" || ui_has "third-party"; then
    tap_element "Agree"; sleep 1.5
  fi
  dump_ui; tap_element "Pick file"; sleep 2
  dump_ui
  if ui_has "Files in $SKILL_NAME"; then
    :
  elif ui_has "Files in Download" || ui_has "Download"; then
    if ! ui_has "Files in Download"; then
      tap_element "Download"; sleep 1; dump_ui
    fi
    tap_element "$SKILL_NAME"; sleep 1
  else
    tap_element "Download"; sleep 1; dump_ui
    tap_element "$SKILL_NAME"; sleep 1
  fi
  dump_ui
  tap_element "USE THIS FOLDER" || tap_element "Use this folder"
  sleep 1.5
  dump_ui
  if ui_has "Allow Edge Gallery" || ui_has "ALLOW" || ui_has "access folder"; then
    ALLOW_XY=$(python3 -c "
import xml.etree.ElementTree as ET, re, sys
tree = ET.parse('$UI_XML')
for node in tree.iter('node'):
    t = node.get('text', '')
    if t in ('Allow', 'ALLOW'):
        m = re.findall(r'\d+', node.get('bounds', ''))
        if m:
            print(f'{(int(m[0])+int(m[2]))//2} {(int(m[1])+int(m[3]))//2}')
            sys.exit(0)
")
    if [ -n "$ALLOW_XY" ]; then
      tap $ALLOW_XY
    fi
    sleep 1.5
  fi
  dump_ui
  if ui_has "Cancel" && ui_has "Add"; then
    tap_element "Add"; sleep 1.5
  fi
  dump_ui
  if ui_has "Replace existing skill" || ui_has "replace"; then
    echo -n "(replacing) "
    tap_element "Replace"; sleep 1.5
  fi
  dump_ui
  tap_element "Turn off all" || tap_element "Deselect all"; sleep 0.5
  for attempt in $(seq 1 15); do
    dump_ui
    TOGGLE=$(find_skill_toggle "$SKILL_NAME")
    if [ -n "$TOGGLE" ]; then
      tap $TOGGLE; sleep 0.5
      break
    fi
    scroll_down; sleep 0.5
  done
  dump_ui; tap_element "Close"; sleep 0.5
}

# =============================================
# APK download and install
# =============================================

ensure_app_installed() {
  local apk_path="$1"
  if $ADB shell pm list packages 2>/dev/null | grep -q "com.google.ai.edge.gallery"; then
    echo "[Setup] App already installed"
    return 0
  fi

  echo -n "[Setup] App not installed. "

  if [ ! -f "$apk_path" ]; then
    echo "Downloading APK..."
    local download_url
    download_url=$(curl -s https://api.github.com/repos/google-ai-edge/gallery/releases/latest \
      | python3 -c "import sys,json; assets=json.load(sys.stdin).get('assets',[]); urls=[a['browser_download_url'] for a in assets if a['name'].endswith('.apk')]; print(urls[0] if urls else '')")
    if [ -z "$download_url" ]; then
      echo "ERROR: Could not find APK in latest release."
      echo "Download manually from: https://github.com/google-ai-edge/gallery/releases"
      echo "Place it at: $apk_path"
      exit 1
    fi
    curl -L -o "$apk_path" "$download_url"
    echo "Downloaded to: $apk_path"
  fi

  echo -n "Installing APK... "
  $ADB install "$apk_path" >/dev/null 2>&1
  echo "OK"
}
