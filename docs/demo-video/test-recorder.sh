#!/bin/bash
# Test script to debug screen recording via adb
set -e

echo "=== Screen Recorder Debug Test ==="

# Step 1: Go home
echo "[1] Going to home screen..."
adb shell input keyevent KEYCODE_HOME
sleep 1

# Step 2: Pull down quick settings
echo "[2] Pulling down quick settings..."
adb shell input swipe 540 0 540 1500 400
sleep 1.5

# Step 3: Screenshot to verify quick settings
echo "[3] Taking screenshot of quick settings..."
MSYS_NO_PATHCONV=1 adb shell screencap -p /sdcard/debug_qs.png
MSYS_NO_PATHCONV=1 adb pull /sdcard/debug_qs.png ./debug_qs.png
echo "    Saved: debug_qs.png - CHECK: Is 开始录屏 at position (340, 190)?"

# Step 4: Tap screen recording button
echo "[4] Tapping 开始录屏 at (340, 190)..."
adb shell input tap 340 190
sleep 1

# Step 5: Screenshot after tap
echo "[5] Taking screenshot after tap..."
MSYS_NO_PATHCONV=1 adb shell screencap -p /sdcard/debug_after_tap.png
MSYS_NO_PATHCONV=1 adb pull /sdcard/debug_after_tap.png ./debug_after_tap.png
echo "    Saved: debug_after_tap.png - CHECK: Did countdown start?"

# Step 6: Wait for recording to start
echo "[6] Waiting 5s for recording to start..."
sleep 5

# Step 7: Screenshot to verify recording is running
echo "[7] Taking screenshot to verify recording..."
MSYS_NO_PATHCONV=1 adb shell screencap -p /sdcard/debug_recording.png
MSYS_NO_PATHCONV=1 adb pull /sdcard/debug_recording.png ./debug_recording.png
echo "    Saved: debug_recording.png - CHECK: Is there a red recording indicator?"

# Step 8: Stop recording
echo "[8] Stopping recording (double tap right edge)..."
adb shell input tap 1050 1130
sleep 0.2
adb shell input tap 1050 1130
sleep 2

# Step 9: Check for new recording file
echo "[9] Checking for new recording..."
LATEST=$(adb shell "ls -t /sdcard/DCIM/Screenshots/*.mp4 2>/dev/null | head -1")
echo "    Latest recording: $LATEST"

echo ""
echo "=== Debug complete. Review the 3 screenshots to diagnose issues ==="
