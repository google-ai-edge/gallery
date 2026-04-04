#!/bin/bash
# Multi-Device Parallel Skill Test Runner
# Usage: ./run-multi-device-test.sh [-s <skills-dir>] [skill1 skill2 ...]
#
# Detects all connected & authorized ADB devices, splits the skill list
# evenly across them, and runs tests in parallel for maximum speed.
#
# Options:
#   -s <skills-dir>   Skills directory name (default: "skills", use "new_skills" for new)
#
# Examples:
#   ./run-multi-device-test.sh -s new_skills                    # test all new skills
#   ./run-multi-device-test.sh -s new_skills astrology-finder trivia-game  # test specific skills
#   ./run-multi-device-test.sh                                  # test all existing skills

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_SUBDIR="skills"

while getopts "s:" opt; do
  case $opt in
    s) SKILLS_SUBDIR="$OPTARG" ;;
    *) echo "Usage: $0 [-s <skills-dir>] [skill1 skill2 ...]"; exit 1 ;;
  esac
done
shift $((OPTIND - 1))

SKILLS_DIR="$SCRIPT_DIR/$SKILLS_SUBDIR"
RESULTS_DIR="$SCRIPT_DIR/test-results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# =============================================
# Detect devices
# =============================================
DEVICES=()
while IFS= read -r line; do
  serial=$(echo "$line" | awk '{print $1}')
  status=$(echo "$line" | awk '{print $2}')
  if [ "$status" = "device" ] && [ -n "$serial" ]; then
    DEVICES+=("$serial")
  fi
done < <(adb devices | tail -n +2 | grep -v "^$")

NUM_DEVICES=${#DEVICES[@]}

if [ "$NUM_DEVICES" -eq 0 ]; then
  echo "ERROR: No authorized devices found. Run 'adb devices' to check."
  exit 1
fi

echo "=========================================="
echo "Multi-Device Parallel Test Runner"
echo "=========================================="
echo "Devices found: $NUM_DEVICES"
for i in "${!DEVICES[@]}"; do
  MODEL=$($ADB -s "${DEVICES[$i]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  echo "  [$((i+1))] ${DEVICES[$i]} ($MODEL)"
done
echo "Skills dir: $SKILLS_SUBDIR"
echo ""

# =============================================
# Build skill list
# =============================================
if [ $# -gt 0 ]; then
  # Specific skills provided as arguments
  SKILLS=("$@")
else
  # Auto-discover all skills with test-input.json
  SKILLS=()
  for d in "$SKILLS_DIR"/*/; do
    name=$(basename "$d")
    if [ -f "$d/testing/test-input.json" ] && [ -f "$d/scripts/index.html" ]; then
      SKILLS+=("$name")
    fi
  done
fi

NUM_SKILLS=${#SKILLS[@]}
if [ "$NUM_SKILLS" -eq 0 ]; then
  echo "ERROR: No skills found in $SKILLS_DIR"
  exit 1
fi

echo "Total skills to test: $NUM_SKILLS"

# =============================================
# Split skills across devices
# =============================================
# Create per-device skill lists
declare -A DEVICE_SKILLS
for i in "${!DEVICES[@]}"; do
  DEVICE_SKILLS[$i]=""
done

for i in "${!SKILLS[@]}"; do
  device_idx=$((i % NUM_DEVICES))
  DEVICE_SKILLS[$device_idx]="${DEVICE_SKILLS[$device_idx]} ${SKILLS[$i]}"
done

echo ""
echo "Skill distribution:"
for i in "${!DEVICES[@]}"; do
  count=$(echo "${DEVICE_SKILLS[$i]}" | wc -w | tr -d ' ')
  MODEL=$(adb -s "${DEVICES[$i]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  echo "  Device $((i+1)) (${DEVICES[$i]} / $MODEL): $count skills"
done
echo ""

# =============================================
# Run tests in parallel
# =============================================
PIDS=()
LOG_FILES=()

for i in "${!DEVICES[@]}"; do
  serial="${DEVICES[$i]}"
  skills_list="${DEVICE_SKILLS[$i]}"
  log_file="$RESULTS_DIR/device_${serial}_${TIMESTAMP}.log"
  LOG_FILES+=("$log_file")

  (
    passed=0
    failed=0
    skipped=0
    fail_list=""

    for skill in $skills_list; do
      echo "[$serial] Testing: $skill"
      # Check if TEST-PLAN.md has an entry for this skill; if not, skip baseline
      if ! grep -q "| $skill |" "$SCRIPT_DIR/TEST-PLAN.md" 2>/dev/null; then
        echo "[$serial] SKIP: $skill (no entry in TEST-PLAN.md)"
        skipped=$((skipped + 1))
        continue
      fi

      "$SCRIPT_DIR/run-device-test.sh" -d "$serial" -s "$SKILLS_SUBDIR" "$skill" >> "$log_file" 2>&1
      exit_code=$?

      if [ $exit_code -eq 0 ]; then
        echo "[$serial] PASS: $skill"
        passed=$((passed + 1))
      else
        echo "[$serial] FAIL: $skill"
        failed=$((failed + 1))
        fail_list="$fail_list $skill"
      fi
    done

    echo "" >> "$log_file"
    echo "==========================================" >> "$log_file"
    echo "DEVICE SUMMARY: $serial" >> "$log_file"
    echo "==========================================" >> "$log_file"
    echo "Passed: $passed" >> "$log_file"
    echo "Failed: $failed" >> "$log_file"
    echo "Skipped: $skipped" >> "$log_file"
    if [ -n "$fail_list" ]; then
      echo "Failed skills:$fail_list" >> "$log_file"
    fi

    echo ""
    echo "[$serial] DONE — Passed: $passed, Failed: $failed, Skipped: $skipped"
    if [ -n "$fail_list" ]; then
      echo "[$serial] Failed:$fail_list"
    fi
  ) &

  PIDS+=($!)
  echo "Started device $((i+1)) ($serial) — PID: ${PIDS[-1]}"
done

echo ""
echo "All devices running in parallel. Waiting for completion..."
echo ""

# =============================================
# Wait for all devices to finish
# =============================================
OVERALL_EXIT=0
for i in "${!PIDS[@]}"; do
  wait "${PIDS[$i]}"
  exit_code=$?
  if [ $exit_code -ne 0 ]; then
    OVERALL_EXIT=1
  fi
done

# =============================================
# Final summary
# =============================================
echo ""
echo "=========================================="
echo "MULTI-DEVICE TEST COMPLETE"
echo "=========================================="
echo "Timestamp: $TIMESTAMP"
echo "Devices: $NUM_DEVICES"
echo "Skills tested: $NUM_SKILLS"
echo ""
echo "Logs:"
for log in "${LOG_FILES[@]}"; do
  echo "  $log"
done
echo ""
echo "Per-device results:"
for log in "${LOG_FILES[@]}"; do
  echo "---"
  grep -A 5 "DEVICE SUMMARY" "$log" 2>/dev/null
done

exit $OVERALL_EXIT
