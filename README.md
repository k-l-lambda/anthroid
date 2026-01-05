# Anthroid

**Your AI Agent on Mobile** - A native Android app that brings Claude AI to your pocket with voice, camera, and terminal capabilities.

## Overview

Anthroid (Android + Anthropic) is a mobile implementation of [Claude Code](https://docs.anthropic.com/claude-code), designed around mobile-native input methods and device capabilities.

**Design Goals:**
- **Mobile APIs** - Access to GPS location, calendar, notifications, and other Android system services
- **Voice input** - Speech recognition (sherpa-onnx) for hands-free interaction, supporting zh/en/ja/ko/yue
- **Visual context** - Camera integration for image-based queries and QR code scanning
- **Full toolset** - Bash, file operations, web search - same capabilities as desktop Claude Code

## Features

### Chat Interface
- Native Android chat UI with streaming responses
- **Markdown rendering** - Tables, bold, italic, code blocks, links
- **Conversation history** - Resume past conversations
- Light blue user bubbles, gray assistant bubbles

### Voice Input
- **Offline speech recognition** using sherpa-onnx
- Supports Chinese, English, Japanese, Korean, Cantonese
- Press-and-hold microphone button to speak
- Real-time transcription display

### Camera & Vision
- Take photos to add visual context to messages
- Gallery picker for existing images
- Multiple images per message
- **QR code scanning** with instant text insertion and clipboard copy

### AI Agent Tools
Claude can execute tools on your device:

| Tool | Description |
|------|-------------|
| Bash | Run terminal commands |
| Read/Write/Edit | File operations |
| Glob/Grep | Search files and content |
| Notification | Show Android notifications |
| Clipboard | Read/write clipboard |
| Open URL | Launch browser |
| Launch App | Open installed apps |
| Location | Get GPS coordinates |
| Calendar | Query calendar events |
| Screenshot | Capture device screen |
| Screen Tap/Swipe | UI automation |

### Screen Automation Overlay
When Claude launches other apps or performs actions outside Anthroid:
- **Floating banner** appears at the top of screen showing agent status
- **Streaming text** displays what Claude is currently doing
- **Stop button** to cancel the operation at any time
- **Auto-hides** after task completion (tap to return to Anthroid)
- Requires overlay permission (Draw over other apps)

### Terminal Environment
Built-in Linux terminal for advanced users:
- Full bash shell environment
- Package manager (apt/pkg)
- Node.js, Python, and more available

## Installation

### Download APK
Get the latest release from [GitHub Releases](https://github.com/k-l-lambda/anthroid/releases).

### Build from Source

```bash
# Clone repository
git clone https://github.com/k-l-lambda/anthroid.git
cd anthroid

# Build debug APK
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/anthroid-app_apt-android-7-debug_arm64-v8a.apk
```

### Requirements
- Android 7.0+ (API 24)
- ARM64 device recommended
- ~200MB storage (+ optional 239MB for voice model)

## Setup

### API Configuration
1. Get your Claude API key from [Anthropic Console](https://console.anthropic.com/)
2. In Anthroid, go to **Settings** > **API Configuration**
3. Enter your API key and base URL

Or use QR code for quick setup:
1. Generate QR code with API credentials
2. Open Camera > QR scan mode
3. Scan the QR code

### Voice Input Setup
1. Go to **Settings** > **Components**
2. Download ASR Model (239MB, one-time)
3. Wait for model initialization
4. Microphone button appears in chat

## Architecture

```
┌─────────────────────────────────────────────────┐
│                 Anthroid App                    │
├─────────────────────────────────────────────────┤
│   Chat UI          │   Terminal UI              │
│   - Messages       │   - Full Linux shell       │
│   - Voice input    │   - Package manager        │
│   - Camera/QR      │   - Claude CLI             │
├─────────────────────────────────────────────────┤
│   Claude Integration                            │
│   - Streaming API responses                     │
│   - Tool execution (Bash, Files, Android APIs)  │
│   - Conversation management                     │
├─────────────────────────────────────────────────┤
│   Native Components                             │
│   - sherpa-onnx (offline ASR)                   │
│   - ML Kit (QR scanning)                        │
│   - CameraX (photo capture)                     │
└─────────────────────────────────────────────────┘
```

### Key Technologies
- **Kotlin** - Primary language
- **CameraX** - Camera capture
- **ML Kit** - QR code scanning
- **sherpa-onnx** - Offline speech recognition
- **Markwon** - Markdown rendering

## License

GPLv3 - Same license as Termux. See [LICENSE.md](LICENSE.md).

## Credits

- [Termux](https://github.com/termux/termux-app) - Terminal emulator foundation
- [Anthropic](https://anthropic.com) - Claude AI
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - Speech recognition
- [Markwon](https://github.com/noties/Markwon) - Markdown rendering
