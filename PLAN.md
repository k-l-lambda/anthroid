# Anthroid Development Plan

## Vision

Anthroid = Android + Anthropic: A native Android app that brings Claude AI to the terminal, enabling AI-assisted command-line workflows on mobile devices.

---

## Completed Phases

### Phase 1: Terminal Fork (Complete)

Fork Termux and rebrand as Anthroid.

| Task | Status | Notes |
|------|--------|-------|
| Copy Termux source | Done | |
| Rename package to com.anthroid | Done | All Java packages renamed |
| Fix JNI function names | Done | Native code updated |
| Fix HiddenApiBypass crash | Done | Downgraded to v4.3 |
| Patch bootstrap scripts | Done | com.termux → com.anthroid |
| Set LD_LIBRARY_PATH | Done | Override ELF RUNPATH |
| Add terminal output logging | Done | Tag: TerminalOutput |
| Test basic terminal | Done | bash, echo, pwd, ls work |

**Known Limitations:**
- ELF binaries have hardcoded com.termux paths (cosmetic errors)
- apt/pkg partially broken due to hardcoded paths

---

### Phase 2: Claude CLI Integration (Complete)

Integrate Claude CLI into the terminal environment.

#### 2.1 Custom Bootstrap Build (Done)
Built custom packages with com.anthroid paths on server 10.121.196.2.

| Package | Version | Status |
|---------|---------|--------|
| apt | 2.8.1-2 | Built |
| dpkg | 1.22.6-5 | Built |
| bash | 5.3.8 | Built |
| nodejs | 25.2.1 | Built |

#### 2.2 Claude CLI Installation (Done)
Installed via npm: @anthropic-ai/claude-code

#### 2.3 Wrapper Script (Done)
Created wrapper at /data/data/com.anthroid/files/usr/bin/claude

#### 2.4 ClaudeCliClient.kt (Done)
Kotlin wrapper for Claude CLI pipe mode communication.

---

### Phase 3: Chat UI (Complete)

Native Android chat interface for Claude with swipe navigation.

#### Architecture (Done - Dec 2025)
- `MainPagerActivity.kt` - Main launcher with ViewPager2 + TabLayout
- `ClaudeFragment.kt` - Chat UI (default tab, index 0)
- `TerminalFragment.kt` - Terminal launcher (swipe to access, index 1)

---


### Phase 4: QR Code Configuration (Complete)

Quick setup via QR code scan for API configuration.

#### Implementation (Done - Dec 2025)
- [x] Web-based QR generator (tools/qr-generator.html)
- [x] set_wrapper script for easy CLI configuration
- [x] QR scanner button in terminal drawer
- [x] ML Kit Barcode Scanning integration
- [x] CameraX for camera preview
- [x] Scanned text inserted to terminal via paste() (safe, no auto-execute)

---

### Phase 6: Tool Integration (Complete)

Enable Claude to execute terminal commands and Android features.

#### Implementation Summary (Dec 2025)

**Problems Solved:**
1. CLI's built-in tools fail on Android - Platform detection, sandbox checks, ripgrep binary
2. MCP tools can't receive results - Broadcasts from subprocess blocked by Android security
3. termux `am` wrapper crashes - Hardcoded com.termux paths

**Solution: Multi-layer approach**

1. **Forked Claude Code Package** - Patched CLI v2.0.76 with Android paths, platform support, sandbox bypass
2. **TOOL_CALL Broadcast + Direct Interception** - Bypass subprocess broadcast limitation
3. **Environment Separation** - Handle system vs termux commands separately

#### Working Tools

| Tool | Type |
|------|------|
| Bash, Read/Write/Edit/Glob/Grep | CLI built-in |
| show_notification, clipboard, open_url | Android |
| launch_app, get_location, query_calendar | Android |

---


## In Progress / Planned Phases

### Phase 5: Conversation Management (Next)

Manage Claude CLI conversation history from the Chat UI.

#### Background
Claude CLI stores conversations as JSONL files in `~/.claude/projects/{project-path}/`:
- Each conversation is a UUID.jsonl file (e.g., `3d6acbeb-4407-4c77-a429-103ee213e34b.jsonl`)
- Messages have: type (user/assistant), content, timestamp, parentUuid (threading), tool calls
- Agent sessions stored separately as `agent-*.jsonl`
- Currently 177+ conversation files on test device, many empty (abandoned sessions)

#### Features
- List past conversations with preview (first user message as title)
- View full conversation history with message details
- Resume conversation (continue from previous session)
- Delete individual conversations
- Bulk cleanup of empty/abandoned sessions
- Search within conversations
- Storage stats (total size, conversation count)

#### Implementation Tasks
- [ ] Create ConversationManager.kt for JSONL parsing
- [ ] Add conversation list UI (RecyclerView in drawer or separate activity)
- [ ] Parse JSONL format: extract messages, timestamps, tool calls
- [ ] Generate conversation title from first user message
- [ ] Implement conversation resume with --resume flag
- [ ] Add delete functionality with confirmation
- [ ] Add storage stats display
- [ ] Implement search across conversations

#### Technical Notes
- JSONL format: one JSON object per line
- Each message has UUID linking to parent (conversation threading)
- CLI supports `--resume SESSION_ID` to continue conversation
- Storage path: `/data/data/com.anthroid/files/home/.claude/projects/-data-data-com-anthroid-files/`

---

### Phase 5b: Camera Input for Chat

Take photos to add visual context to chat messages.

#### Features
- Camera button in chat input area
- Photo preview before sending
- Multiple photos per message support
- Photos sent as base64 encoded images with next message

#### Implementation Tasks
- [ ] Add camera button to ClaudeFragment input bar
- [ ] CameraX integration for photo capture
- [ ] Photo preview/thumbnail display above input
- [ ] Remove photo option (X button on preview)
- [ ] Encode photos as base64 for Claude API
- [ ] Update ClaudeCliClient to support image content
- [ ] Gallery picker as alternative to camera

#### Technical Notes
- Claude CLI --print mode supports multimodal input
- Images need to be base64 encoded in JSON format
- Max image size considerations for API limits


---

### Phase 7: Voice I/O

Voice input and output for hands-free interaction.

#### Voice Input (STT)
- Android SpeechRecognizer API
- Press-and-hold microphone button to speak
- Real-time transcription to text input

#### Voice Output (TTS)
- Android TextToSpeech API
- Optional: Kokoro TTS for higher quality
- Per-message play button

#### UI Components
- Microphone button next to input field
- Speaker button on message bubbles
- Settings: Voice selection, speech rate, auto-play toggle

#### Implementation Tasks
- [ ] Add microphone button to ClaudeFragment
- [ ] Integrate SpeechRecognizer for STT
- [ ] Add TTS engine initialization
- [ ] Message bubble play/stop controls
- [ ] Voice settings in preferences

---

### Phase 8: Production Release

Final polish and release preparation.

- [ ] App icon and branding
- [ ] Play Store listing
- [ ] Privacy policy
- [ ] Performance optimization
- [ ] Crash reporting (Firebase Crashlytics)

---

## File Structure (Current)

```
app/src/main/java/com/anthroid/
├── app/
│   ├── TermuxActivity.java      # Terminal UI
│   ├── TermuxService.java       # Background service
│   ├── TermuxInstaller.java     # Bootstrap installer
│   └── QRScannerActivity.kt     # QR code scanner
├── main/
│   ├── MainPagerActivity.kt     # Main launcher with ViewPager2
│   ├── ClaudeFragment.kt        # Chat UI fragment
│   └── TerminalFragment.kt      # Terminal launcher fragment
├── claude/
│   ├── ClaudeViewModel.kt       # State management
│   ├── ClaudeApiClient.kt       # HTTP API client
│   ├── ClaudeCliClient.kt       # CLI wrapper
│   └── ui/
│       └── MessageAdapter.kt    # Message list
└── shared/
    └── ... (existing)
```

---

## Milestones

| Milestone | Status | Description |
|-----------|--------|-------------|
| M1 | ✅ Done | Terminal fork working |
| M2 | ✅ Done | Claude CLI runs in terminal |
| M3 | ✅ Done | Chat UI with ViewPager2 navigation |
| M4 | ✅ Done | QR code configuration scanner |
| M5 | 🔄 Next | Conversation management |
| M5b | ⏳ | Camera input for chat |
| M6 | ✅ Done | Tool execution working |
| M7 | ⏳ | Voice I/O (STT + TTS) |
| M8 | ⏳ | Production-ready release |

---

## Resources

- [Termux App](https://github.com/termux/termux-app)
- [Termux Packages](https://github.com/termux/termux-packages)
- [Claude CLI Documentation](https://docs.anthropic.com/claude-code)
- [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
