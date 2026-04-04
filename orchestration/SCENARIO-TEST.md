# Edge Gallery App - On-Device Scenario Test Guide

This document describes how to run an end-to-end scenario test for JS skills on a physical Android device using the Edge Gallery app and ADB.

## Prerequisites

- macOS with Homebrew
- ADB installed (`brew install android-platform-tools`)
- Android device connected via USB with USB debugging enabled
- Edge Gallery dev app installed (`com.google.ai.edge.gallery.dev`)
- Gemma-4-2B-it model downloaded in the app

## Device Setup

1. Connect the Android device via USB.
2. Enable USB debugging on the device (Settings > Developer options > USB debugging).
3. Authorize the computer when prompted on the device.
4. Verify connection:
   ```bash
   adb devices
   ```

## Test Steps

### 1. Push Skill to Device

Push the skill folder to the device's Downloads directory:

```bash
adb push <skill-folder> /sdcard/Download/<skill-name>
```

Example:
```bash
adb push uuid-generator /sdcard/Download/uuid-generator
```

### 2. Launch the App

```bash
adb shell am start -n com.google.ai.edge.gallery.dev/com.google.ai.edge.gallery.MainActivity
```

### 3. Navigate to Agent Chat

1. Tap the **Experimental** tab at the bottom of the home screen.
2. Tap **Agent Chat**.
3. Select **Gemma-4-2B-it** model (tap "Try it").
4. Wait for the chat screen to load.

### 4. Import the Skill

1. Tap the **Skills** button at the bottom of the chat screen.
2. **Disable all other skills** first (toggle them off) to ensure only the skill under test is active. This prevents the model from picking the wrong skill.
3. Tap the **+** (Add) button in the skills panel.
4. Tap **Import local skill** from the bottom menu.
5. Navigate to the **Download** folder in the file picker.
6. Select the skill folder (e.g., `uuid-generator`).
7. Wait for the skill to load. Verify it is toggled on and all other skills are toggled off.
8. Close the skills panel and return to the chat screen.

### 5. Delete a Skill (for re-testing or cleanup)

If the skill is already installed (e.g., from a previous test) and you need to update it, delete the old version first:

1. Tap the **Skills** button at the bottom of the chat screen.
2. Scroll to find the skill you want to delete.
3. Tap the **Delete** button next to the skill.
4. In the confirmation dialog ("Are you sure you want to delete the skill?"), tap **Delete**.
5. The skill is now removed from the list. You can re-import the updated version.

### 6. Send a Test Prompt

Type a test prompt in the chat input and send it. The on-device model should recognize the skill, call the JS script, and return results.

Example prompts per skill (refer to each skill's `SKILL.md` for more):

| Skill | Test Prompt | Expected Behavior |
|-------|------------|-------------------|
| uuid-generator | "Generate 5 UUIDs" | Calls `uuid-generator/index.html`, returns 5 valid UUIDs |
| password-generator | "Generate a password" | Returns a random password string |

### 7. Verify the Response

Check that the model response includes:
- **"Calling JS script"** message indicating skill invocation
- **"Called JS script"** message indicating successful execution
- Valid output matching the skill's expected format

## ADB Automation Reference

For automated testing via ADB, use these commands:

```bash
# Take a screenshot
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png /tmp/screenshot.png

# Dump UI hierarchy (for finding element coordinates)
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml /tmp/ui.xml

# Tap at coordinates (device resolution: e.g., 1080x2340 for Pixel 7 Pro)
adb shell input tap <x> <y>

# Type text (use %s for spaces)
adb shell input text "Generate%s5%sUUIDs"

# Press Enter/Send
adb shell input keyevent 66
```

### Finding UI Element Coordinates

1. Dump the UI hierarchy: `adb shell uiautomator dump`
2. Parse the XML to find the target element's `bounds` attribute.
3. Calculate the center: `x = (x1 + x2) / 2`, `y = (y1 + y2) / 2`.
4. Tap at the center coordinates.

## Test Validation Checklist

- [ ] Device connected and authorized via ADB
- [ ] Skill folder pushed to `/sdcard/Download/`
- [ ] App launched and navigated to Agent Chat
- [ ] Skill imported and enabled (toggle on)
- [ ] Test prompt sent and response received
- [ ] Model called the correct JS script
- [ ] Output matches expected format (e.g., valid UUIDs, correct count)

## Notes

- The app uses Jetpack Compose, so `uiautomator dump` returns Compose-based view hierarchies.
- The device screen may lock during long operations; keep it unlocked or disable auto-lock.
- The on-device model (Gemma-4-2B-it) runs on CPU and may take 10-20 seconds to respond.
- App package: `com.google.ai.edge.gallery.dev`
- Main activity: `com.google.ai.edge.gallery.MainActivity`
