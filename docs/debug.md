# Anthroid Debug Guide

This document covers debugging techniques for the Anthroid app, including APK installation automation, ADB broadcast commands, and log analysis.

## APK Installation Automation

### Setup uiautomator2

Install the required Python packages:

```bash
pip install -U uiautomator2 pillow
```

### Install atx-agent on Device

```bash
# Download atx-agent for ARM64
curl -L -o /tmp/atx-agent.tar.gz \
    "https://github.com/openatx/atx-agent/releases/download/0.10.0/atx-agent_0.10.0_linux_arm64.tar.gz"

# Extract
tar -xzf /tmp/atx-agent.tar.gz -C /tmp/

# Push to device
MSYS_NO_PATHCONV=1 adb push /tmp/atx-agent /data/local/tmp/atx-agent

# Start daemon
MSYS_NO_PATHCONV=1 adb shell "chmod 755 /data/local/tmp/atx-agent && /data/local/tmp/atx-agent server -d"
```

### Auto-Install Script

Save as `auto_install_click.py`:

```python
import uiautomator2 as u2
import time
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s")

d = u2.connect()
logging.info(f"Connected to device: {d.info.get('productName', 'unknown')}")

INSTALL_KEYS = [
    "继续安装",      # ColorOS - "Continue Install"
    "仍要安装",      # "Install anyway"
    "安装完成",      # "Install complete"
    "完成",          # "Done"
    "确定",          # "OK"
    "允许",          # "Allow"
    "安装",          # Generic "Install"
    "Install",
    "Continue",
    "Done",
    "OK"
]

def click_when_found():
    for txt in INSTALL_KEYS:
        try:
            btn = d(textContains=txt, clickable=True)
            if btn.exists:
                btn.click()
                logging.info(f"Clicked: [{txt}]")
                time.sleep(1.5)
                return True
        except:
            continue
    return False

def watch_and_click(timeout=120):
    logging.info(f"Watching for install dialogs for {timeout}s...")
    t_end = time.time() + timeout
    clicks = 0
    while time.time() < t_end:
        if click_when_found():
            clicks += 1
            if clicks > 10:
                break
        time.sleep(0.3)
    logging.info(f"Done. Total clicks: {clicks}")

if __name__ == "__main__":
    import sys
    timeout = int(sys.argv[1]) if len(sys.argv) > 1 else 120
    watch_and_click(timeout)
```

### Installation Workflow

**Terminal 1: Start watcher**
```bash
python auto_install_click.py 60
```

**Terminal 2: Install APK**
```bash
# Build APK
cd /path/to/anthroid
./gradlew assembleDebug

# Push to device
MSYS_NO_PATHCONV=1 adb push app/build/outputs/apk/debug/anthroid-app_*_arm64-v8a.apk /data/local/tmp/anthroid.apk

# Install
MSYS_NO_PATHCONV=1 adb shell pm install -r /data/local/tmp/anthroid.apk
```

---

## ADB Broadcast APIs

All broadcast commands require explicit component targeting to work reliably.

### Send Message to Claude Agent

```bash
MSYS_NO_PATHCONV=1 adb shell am broadcast \
    -a com.anthroid.DEBUG_SEND_MESSAGE \
    -p com.anthroid \
    --es message "Your message here"
```

### Configure API Key

```bash
MSYS_NO_PATHCONV=1 adb shell am broadcast \
    -a com.anthroid.DEBUG_CONFIG_API \
    -p com.anthroid \
    --es api_key "sk-ant-xxx..."
```

With optional base URL and model:
```bash
MSYS_NO_PATHCONV=1 adb shell am broadcast \
    -a com.anthroid.DEBUG_CONFIG_API \
    -p com.anthroid \
    --es api_key "sk-ant-xxx..." \
    --es base_url "https://api.anthropic.com" \
    --es model "claude-sonnet-4-20250514"
```

---

## Log Analysis

### View Claude-related logs

```bash
MSYS_NO_PATHCONV=1 adb logcat -d | grep -i "claude\|ClaudeViewModel\|ClaudeCliClient"
```

### Real-time log monitoring

```bash
MSYS_NO_PATHCONV=1 adb logcat | grep -i "claude"
```

### Filter by tag

```bash
MSYS_NO_PATHCONV=1 adb logcat -s ClaudeViewModel:* ClaudeCliClient:* DebugReceiver:*
```

---

## Device Screenshots

### Take screenshot via ADB

```bash
MSYS_NO_PATHCONV=1 adb exec-out screencap -p > screenshot.png
```

### Using uiautomator2

```python
import uiautomator2 as u2
d = u2.connect()
d.screenshot("screenshot.png")
```

---

## UI Inspection

### Dump UI hierarchy

```python
import uiautomator2 as u2
d = u2.connect()
print(d.dump_hierarchy())
```

### Find clickable elements

```python
for el in d(clickable=True):
    info = el.info
    text = info.get('text', '') or info.get('contentDescription', '')
    print(f"text={text}, bounds={info.get('bounds')}")
```

### Visual UI inspector

```bash
pip install weditor
python -m weditor
```

---

## App Launch

### Launch main activity

```bash
MSYS_NO_PATHCONV=1 adb shell am start -n com.anthroid/.app.TermuxActivity
```

### Launch Claude UI

```bash
MSYS_NO_PATHCONV=1 adb shell am start -n com.anthroid/.claude.ClaudeActivity
```

### Force stop app

```bash
MSYS_NO_PATHCONV=1 adb shell am force-stop com.anthroid
```

---

## Shell Access

### Run commands in Anthroid environment

```bash
MSYS_NO_PATHCONV=1 adb shell "run-as com.anthroid sh -c 'export PATH=/data/data/com.anthroid/files/usr/bin; export LD_LIBRARY_PATH=/data/data/com.anthroid/files/usr/lib; YOUR_COMMAND'"
```

### Check Claude CLI version

```bash
MSYS_NO_PATHCONV=1 adb shell "run-as com.anthroid sh -c 'export PATH=/data/data/com.anthroid/files/usr/bin; export LD_LIBRARY_PATH=/data/data/com.anthroid/files/usr/lib; claude --version'"
```

---

## Troubleshooting

### atx-agent not running

```bash
# Check status
MSYS_NO_PATHCONV=1 adb shell "ps -A | grep atx"

# Restart
MSYS_NO_PATHCONV=1 adb shell "/data/local/tmp/atx-agent server -d"
```

### Path conversion issues (Git Bash on Windows)

Always prefix adb commands with `MSYS_NO_PATHCONV=1` to prevent path mangling:

```bash
# Wrong (path gets mangled)
adb shell ls /sdcard

# Correct
MSYS_NO_PATHCONV=1 adb shell ls /sdcard
```

### Install timeout

If `adb install` hangs, use pm install instead:

```bash
MSYS_NO_PATHCONV=1 adb push app.apk /data/local/tmp/
MSYS_NO_PATHCONV=1 adb shell pm install -r /data/local/tmp/app.apk
```

### Device not found

```bash
# List devices
adb devices

# Restart ADB server
adb kill-server
adb start-server
```
