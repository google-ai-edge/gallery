#!/bin/bash
# 5-hand blackjack device test with session resets
# Resets chat every 5 hands to prevent model degradation

export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"

DEVICE_SERIAL=""
SKILLS_SUBDIR="../agent_skills"
SKILL_NAME="blackjack"
NUM_HANDS=5
RESET_EVERY=5

while getopts "d:s:n:" opt; do
  case $opt in
    d) DEVICE_SERIAL="$OPTARG" ;;
    s) SKILLS_SUBDIR="$OPTARG" ;;
    n) NUM_HANDS="$OPTARG" ;;
    *) echo "Usage: $0 [-d <device-serial>] [-s <skills-dir>] [-n <num-hands>]"; exit 1 ;;
  esac
done

if [ -n "$DEVICE_SERIAL" ]; then
  ADB="adb -s $DEVICE_SERIAL"
else
  ADB="adb"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$(cd "$SCRIPT_DIR/$SKILLS_SUBDIR" 2>/dev/null && pwd)"
SKILL_PATH="$SKILLS_DIR/$SKILL_NAME"
SCREENSHOTS_DIR="$SCRIPT_DIR/screenshots/$SKILL_NAME/5hand"
APK_PATH="$SCRIPT_DIR/gallery.apk"
UI_XML="/tmp/ui_${DEVICE_SERIAL:-default}.xml"

if [ ! -d "$SKILL_PATH" ]; then
  echo "ERROR: Skill '$SKILL_NAME' not found in $SKILLS_DIR"
  exit 1
fi

mkdir -p "$SCREENSHOTS_DIR"

# Load shared helpers
source "$SCRIPT_DIR/lib-device-helpers.sh"

# =============================================
# Play a single hand
# =============================================

play_hand() {
  local hand_num=$1
  echo ""
  echo "==========================================="
  echo "  HAND $hand_num / $NUM_HANDS"
  echo "==========================================="

  # Count existing "Called JS script" before deal
  dump_ui
  local before_deal=$(ui_count "Called JS script")

  # Deal
  echo -n "[Deal] "
  send_prompt "Play%sblackjack"
  if poll_ui_count "Called JS script" $((before_deal + 1)) 45 3; then
    sleep 5; dump_ui
    echo "OK"
    take_screenshot "$SCREENSHOTS_DIR/hand${hand_num}_deal.png"
    scroll_up_from_bottom
    sleep 1
    take_screenshot "$SCREENSHOTS_DIR/hand${hand_num}_deal_scroll.png"
  else
    echo "TIMEOUT"
    take_screenshot "$SCREENSHOTS_DIR/hand${hand_num}_deal_timeout.png"
    return 1
  fi

  # Count before stand
  dump_ui
  local before_stand=$(ui_count "Called JS script")

  # Stand
  echo -n "[Stand] "
  send_prompt "stand"
  if poll_ui_count "Called JS script" $((before_stand + 1)) 45 3; then
    sleep 5; dump_ui
    echo "OK"
    take_screenshot "$SCREENSHOTS_DIR/hand${hand_num}_stand.png"
    scroll_up_from_bottom
    sleep 1
    take_screenshot "$SCREENSHOTS_DIR/hand${hand_num}_stand_scroll.png"
  else
    echo "TIMEOUT"
    take_screenshot "$SCREENSHOTS_DIR/hand${hand_num}_stand_timeout.png"
    return 1
  fi

  echo "Hand $hand_num complete"
}

# =============================================
# Main
# =============================================

DEVICE_MODEL=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "==========================================="
echo "  5-Hand Blackjack Test"
echo "  Device: $DEVICE_MODEL (${DEVICE_SERIAL:-default})"
echo "==========================================="

# --- Setup: ensure app is installed, push skill ---
ensure_app_installed "$APK_PATH"

echo -n "[Setup] Pushing skill to device... "
$ADB shell rm -rf "/sdcard/Download/$SKILL_NAME" >/dev/null 2>&1
$ADB shell mkdir -p "/sdcard/Download/$SKILL_NAME" >/dev/null 2>&1
$ADB push "$SKILL_PATH/SKILL.md" "/sdcard/Download/$SKILL_NAME/SKILL.md" >/dev/null 2>&1
$ADB push "$SKILL_PATH/scripts" "/sdcard/Download/$SKILL_NAME/scripts" >/dev/null 2>&1
if [ -d "$SKILL_PATH/assets" ]; then
  $ADB push "$SKILL_PATH/assets" "/sdcard/Download/$SKILL_NAME/assets" >/dev/null 2>&1
fi
echo "OK"

echo -n "[Setup] Installing skill... "
fresh_app
reset_session
import_skill
echo "OK"

# --- Play hands with periodic resets ---
PASSED=0
FAILED=0

for hand in $(seq 1 $NUM_HANDS); do
  # Reset session every RESET_EVERY hands (except before hand 1)
  if [ $hand -gt 1 ] && [ $(( (hand - 1) % RESET_EVERY )) -eq 0 ]; then
    echo ""
    echo "[Reset] Resetting session before hand $hand..."
    reset_session
    sleep 1
  fi

  if play_hand $hand; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
  fi
done

# --- Summary ---
echo ""
echo "==========================================="
echo "  RESULTS: $PASSED passed, $FAILED failed (of $NUM_HANDS hands)"
echo "  Screenshots: $SCREENSHOTS_DIR/"
echo "==========================================="
ls "$SCREENSHOTS_DIR/"*.png 2>/dev/null

exit $FAILED
