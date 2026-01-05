#!/bin/bash
# Anthroid Data Backup Script
# Usage: bash backup_data.sh [output_dir]
# Requires: adb connected to device with root access or debuggable app

OUTPUT_DIR="${1:-./anthroid_backup_$(date +%Y%m%d_%H%M%S)}"
mkdir -p "$OUTPUT_DIR"

echo "Backing up Anthroid user data to $OUTPUT_DIR..."

# 1. Backup conversation history (JSONL files)
echo "Pulling conversations..."
adb shell "run-as com.anthroid ls /data/data/com.anthroid/files/home/.claude/projects/-data-data-com-anthroid-files/*.jsonl" 2>/dev/null | while read f; do
    filename=$(basename "$f")
    adb shell "run-as com.anthroid cat '$f'" > "$OUTPUT_DIR/$filename"
    echo "  Backed up: $filename"
done

# 2. Backup SharedPreferences
echo "Pulling shared preferences..."
mkdir -p "$OUTPUT_DIR/prefs"
adb shell "run-as com.anthroid cat /data/data/com.anthroid/shared_prefs/claude_config.xml" > "$OUTPUT_DIR/prefs/claude_config.xml" 2>/dev/null
adb shell "run-as com.anthroid cat /data/data/com.anthroid/shared_prefs/com.anthroid_preferences.xml" > "$OUTPUT_DIR/prefs/com.anthroid_preferences.xml" 2>/dev/null

# 3. Backup proxy config
echo "Pulling proxy config..."
adb shell "run-as com.anthroid cat /data/data/com.anthroid/files/proxy_config.json" > "$OUTPUT_DIR/proxy_config.json" 2>/dev/null

echo ""
echo "Backup completed to $OUTPUT_DIR"
echo "Contents:"
ls -la "$OUTPUT_DIR"
