# Anthroid

Android terminal application with Claude AI integration, forked from [Termux](https://github.com/termux/termux-app).

## Overview

Anthroid is an Android app that combines:
- **Terminal Emulator**: Full Linux terminal environment on Android
- **Claude AI Integration**: Native Claude CLI support for AI-assisted terminal workflows

## Current Status

**Phase 1: Terminal Fork** - Complete

| Feature | Status |
|---------|--------|
| App builds as `com.anthroid` | Done |
| Terminal emulator works | Done |
| Bash shell functional | Done |
| Basic commands (echo, pwd, ls) | Done |
| Terminal output logging | Done |
| Package manager (apt/pkg) | Partial (hardcoded paths) |

## Building

### Prerequisites

- Android Studio or command-line Android SDK
- JDK 11+
- Android NDK (installed via SDK Manager)

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Output APKs in app/build/outputs/apk/debug/
# - anthroid-app_apt-android-7-debug_arm64-v8a.apk (33 MB)
# - anthroid-app_apt-android-7-debug_universal.apk (112 MB)
```

### Install

```bash
adb install app/build/outputs/apk/debug/anthroid-app_apt-android-7-debug_arm64-v8a.apk
```

## Development

### Project Structure

```
anthroid/
├── app/                    # Main application module
├── terminal-emulator/      # Terminal emulation library
├── terminal-view/          # Terminal UI component
└── termux-shared/          # Shared utilities
```

### Key Modifications from Termux

1. **Package Rename**: `com.termux` → `com.anthroid`
2. **JNI Functions**: All native functions renamed for new package
3. **Bootstrap Patching**: Script files patched during extraction
4. **LD_LIBRARY_PATH**: Set to override hardcoded paths in ELF binaries
5. **Terminal Logging**: Output logged to logcat with tag `TerminalOutput`

### Debugging Terminal Output

```bash
# View terminal output via logcat
adb logcat | grep "TerminalOutput"

# Run command and capture output
adb shell "input text 'echo hello'" && adb shell "input keyevent 66"
sleep 2
adb logcat -d -t 50 | grep "TerminalOutput"
```

## Known Issues

1. **Cosmetic startup error**: `bash: /data/data/com.termux/files/usr/etc/profile: Permission denied`
   - Cause: bash binary has hardcoded com.termux paths
   - Impact: Cosmetic only, terminal works normally

2. **apt/pkg partial functionality**: Package manager has hardcoded paths
   - Solution: Build custom bootstrap packages (see PLAN.md)

## Architecture

```
┌─────────────────────────────────────────────┐
│                 Anthroid App                │
├─────────────────────────────────────────────┤
│  TermuxActivity     │   ClaudeActivity      │
│  (Terminal UI)      │   (Chat UI) [TODO]    │
├─────────────────────────────────────────────┤
│  TerminalSession    │   ClaudeCliClient     │
│  (PTY management)   │   (CLI wrapper) [TODO]│
├─────────────────────────────────────────────┤
│           Linux Environment                 │
│  /data/data/com.anthroid/files/usr/         │
│  ├── bin/  (bash, etc)                      │
│  ├── lib/  (shared libraries)              │
│  └── etc/  (config files)                   │
└─────────────────────────────────────────────┘
```

## License

Same license as Termux. See [LICENSE.md](LICENSE.md).

## Credits

- [Termux](https://github.com/termux/termux-app) - Original terminal emulator
- [Anthropic](https://anthropic.com) - Claude AI
