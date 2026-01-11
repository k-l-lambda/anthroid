#!/bin/bash
# Demo A Recording Script - WORKING coordinates verified
set -e
WAIT_FOR_TYPING=15
WAIT_FOR_AUTOMATION=30

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║     Anthroid Demo A - Screen Automation Recording            ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo "Prompt: 打开拼多多，搜索手机壳"
echo ""

echo "[1/7] Checking device..."
adb devices | grep -q "device$" || { echo "ERROR: No device"; exit 1; }
echo "  ✓ Device connected"

echo "[2/7] Preparing clean state..."
adb shell am force-stop com.xunmeng.pinduoduo 2>/dev/null || true
adb shell input keyevent KEYCODE_HOME
sleep 1

echo "[3/7] Launching Anthroid..."
adb shell am start -n com.anthroid/.main.MainPagerActivity
sleep 2

echo "[4/7] Creating new chat..."
adb shell input tap 864 168   # History icon
sleep 1
adb shell input tap 972 360   # + New chat
sleep 1
adb shell input tap 100 600   # Close panel
sleep 0.5
echo "  ✓ New chat ready"

echo "[5/7] Starting screen recording..."
adb shell input swipe 540 0 540 1500 400
sleep 2  # Wait for animation
adb shell input tap 330 180   # 开始录屏 - VERIFIED WORKING
echo "  Countdown 3s..."
sleep 4
adb shell am start -n com.anthroid/.main.MainPagerActivity
sleep 1
echo "  ✓ Recording started"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  TYPE NOW: 打开拼多多，搜索手机壳   Then tap SEND            ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

adb shell input tap 456 2217
sleep 1

echo "[6/7] Waiting ${WAIT_FOR_TYPING}s..."
for i in $(seq $WAIT_FOR_TYPING -1 1); do printf "\r  Type: %2ds " $i; sleep 1; done
echo ""

echo "[7/7] Waiting ${WAIT_FOR_AUTOMATION}s..."
for i in $(seq $WAIT_FOR_AUTOMATION -1 1); do printf "\r  Auto: %2ds " $i; sleep 1; done
echo ""

echo "Stopping..."
adb shell input tap 1050 1130; sleep 0.2; adb shell input tap 1050 1130; sleep 2

echo ""
echo "✓ DONE - Recording at: /sdcard/DCIM/Screenshots/Record_*.mp4"
