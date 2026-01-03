# Anthroid

**Your AI Agent on Mobile** - A native Android app that brings Claude AI to your pocket with voice, camera, and terminal capabilities.

## Overview

Anthroid (Android + Anthropic) is a mobile AI agent platform that enables:

- **Conversational AI**: Native chat interface with Claude, featuring markdown rendering and conversation history
- **Multimodal Input**: Voice recognition, camera photos, QR code scanning
- **Tool Execution**: Claude can execute commands, manage files, access location, calendar, and more
- **Offline Capable**: Voice input works without internet using on-device speech recognition

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

### Terminal Environment
Built-in Linux terminal for advanced users:
- Full bash shell environment
- Package manager (apt/pkg)
- Node.js, Python, and more available

## Installation

### Download APK
Get the latest release from [GitHub Releases](https://github.com/k-l-lambda/anthroid/releases).

### Build from Source

\`\`\`bash
# Clone repository
git clone https://github.com/k-l-lambda/anthroid.git
cd anthroid

# Build debug APK
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/anthroid-app_apt-android-7-debug_arm64-v8a.apk
\`\`\`

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

\`\`\`
┌─────────────────────────────────────────────────┐
│                 Anthroid App                     │
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
│   - sherpa-onnx (offline ASR)                  │
│   - ML Kit (QR scanning)                       │
│   - CameraX (photo capture)                    │
└─────────────────────────────────────────────────┘
\`\`\`

## Project Structure

\`\`\`
anthroid/
├── app/                          # Main application
│   └── src/main/java/com/anthroid/
│       ├── main/                 # Chat UI (ClaudeFragment)
│       ├── claude/               # Claude integration
│       │   ├── ClaudeViewModel   # State management
│       │   ├── ClaudeApiClient   # HTTP API client
│       │   └── ui/MessageAdapter # Message rendering
│       └── app/                  # Terminal (TermuxActivity)
├── terminal-emulator/            # Terminal emulation library
├── terminal-view/                # Terminal UI component
└── termux-shared/                # Shared utilities
\`\`\`

## Development

See [PLAN.md](PLAN.md) for detailed development phases and roadmap.

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
