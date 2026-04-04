#!/bin/bash
# Run skill recordings on two devices in parallel.
# Splits all 16 skills evenly across the two connected devices.
#
# Usage: ./run-all-recordings.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$SCRIPT_DIR/skills"
LOG_DIR="$SCRIPT_DIR/recording/logs"
mkdir -p "$LOG_DIR"

# Get connected devices
DEVICES=($(adb devices | grep -w "device" | awk '{print $1}'))

if [ ${#DEVICES[@]} -lt 2 ]; then
  echo "ERROR: Need 2 devices connected. Found ${#DEVICES[@]}."
  echo "Connected: ${DEVICES[*]}"
  exit 1
fi

DEV1="${DEVICES[0]}"
DEV2="${DEVICES[1]}"
echo "Device 1: $DEV1"
echo "Device 2: $DEV2"
echo ""

# All 16 skills
ALL_SKILLS=($(ls "$SKILLS_DIR"))
TOTAL=${#ALL_SKILLS[@]}
HALF=$(( (TOTAL + 1) / 2 ))

# Split: first half → device 1, second half → device 2
SKILLS_DEV1=("${ALL_SKILLS[@]:0:$HALF}")
SKILLS_DEV2=("${ALL_SKILLS[@]:$HALF}")

echo "Device 1 ($DEV1): ${SKILLS_DEV1[*]}"
echo "Device 2 ($DEV2): ${SKILLS_DEV2[*]}"
echo ""
echo "=========================================="
echo "Starting parallel recordings..."
echo "=========================================="
echo ""

# Run device 1 skills sequentially in background
(
  PASS=0; FAIL=0
  for skill in "${SKILLS_DEV1[@]}"; do
    echo "[DEV1] Recording: $skill"
    "$SCRIPT_DIR/run-skill-recording.sh" -s "$DEV1" "$skill" > "$LOG_DIR/${skill}_dev1.log" 2>&1
    if [ $? -eq 0 ]; then
      echo "[DEV1] $skill: PASS"
      PASS=$((PASS + 1))
    else
      echo "[DEV1] $skill: FAIL (see $LOG_DIR/${skill}_dev1.log)"
      FAIL=$((FAIL + 1))
    fi
  done
  echo ""
  echo "[DEV1] Done: $PASS passed, $FAIL failed"
) &
PID1=$!

# Run device 2 skills sequentially in background
(
  PASS=0; FAIL=0
  for skill in "${SKILLS_DEV2[@]}"; do
    echo "[DEV2] Recording: $skill"
    "$SCRIPT_DIR/run-skill-recording.sh" -s "$DEV2" "$skill" > "$LOG_DIR/${skill}_dev2.log" 2>&1
    if [ $? -eq 0 ]; then
      echo "[DEV2] $skill: PASS"
      PASS=$((PASS + 1))
    else
      echo "[DEV2] $skill: FAIL (see $LOG_DIR/${skill}_dev2.log)"
      FAIL=$((FAIL + 1))
    fi
  done
  echo ""
  echo "[DEV2] Done: $PASS passed, $FAIL failed"
) &
PID2=$!

# Wait for both to finish
wait $PID1
EXIT1=$?
wait $PID2
EXIT2=$?

echo ""
echo "=========================================="
echo "ALL RECORDINGS COMPLETE"
echo "=========================================="
echo "Videos: recording/<skill-name>/recording.mp4"
echo "Logs:   recording/logs/<skill-name>_dev*.log"
echo ""
ls -1 "$SCRIPT_DIR/recording/" | grep -v logs

if [ $EXIT1 -ne 0 ] || [ $EXIT2 -ne 0 ]; then
  echo ""
  echo "Some recordings failed. Check logs for details."
  exit 1
fi
