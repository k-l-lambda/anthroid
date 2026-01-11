#!/bin/bash
# Demo A - Fully Automated Recording
# Uses DEBUG_SEND_MESSAGE broadcast to send Chinese message automatically
set -e
WAIT_FOR_AUTOMATION=90
PROMPT="打开拼多多，搜索手机壳"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║     Anthroid Demo A - FULLY AUTOMATED Recording              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Prompt: $PROMPT"
echo ""

echo "[1/7] Checking device..."
adb devices | grep -q "device$" || { echo "ERROR: No device"; exit 1; }
echo "  ✓ Device connected"

echo "[2/7] Clean state..."
adb shell am force-stop com.xunmeng.pinduoduo 2>/dev/null || true
adb shell input keyevent KEYCODE_HOME
sleep 1
echo "  ✓ Ready"

echo "[3/7] Launching Anthroid..."
adb shell am start -n com.anthroid/.main.MainPagerActivity
sleep 2

echo "[4/7] Creating new chat..."
adb shell input tap 864 168
sleep 1
adb shell input tap 972 360
sleep 1
adb shell input tap 100 600
sleep 0.5
echo "  ✓ New chat ready"

echo "[5/7] Starting screen recording..."
adb shell input swipe 540 0 540 1500 400
sleep 3
adb shell input tap 660 350
echo "  Countdown 3s..."
sleep 4
adb shell am start -n com.anthroid/.main.MainPagerActivity
sleep 1
echo "  ✓ Recording started"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  SENDING MESSAGE: $PROMPT"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Send message via debug broadcast (auto-types and sends Chinese text)
adb shell "am broadcast -a com.anthroid.DEBUG_SEND_MESSAGE --es message '$PROMPT' -p com.anthroid"
sleep 1
echo "  ✓ Message sent via broadcast"

echo "[6/7] Waiting ${WAIT_FOR_AUTOMATION}s for automation to complete..."
for i in $(seq $WAIT_FOR_AUTOMATION -1 1); do
    printf "\r  🤖 Running: %2ds " $i
    sleep 1
done
echo ""

echo "[7/7] Returning to Anthroid for final message..."
# Tap notification banner area to return to Anthroid
adb shell am start -n com.anthroid/.main.MainPagerActivity
sleep 2
# Scroll up to see the final message
adb shell input swipe 540 800 540 1500 300
sleep 3
echo "  ✓ Final message visible"

echo "Stopping recording..."
adb shell input tap 1050 1130
sleep 0.2
adb shell input tap 1050 1130
sleep 2

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                 ✓ RECORDING COMPLETE                         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "To retrieve: adb pull /sdcard/DCIM/Screenshots/ ."
