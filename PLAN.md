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

### Phase 5: Conversation Management (Complete)

Manage Claude CLI conversation history from the Chat UI.

#### Background
Claude CLI stores conversations as JSONL files in `~/.claude/projects/{project-path}/`:
- Each conversation is a UUID.jsonl file
- Messages have: type (user/assistant), content, timestamp, tool calls
- Storage path: `/data/data/com.anthroid/files/home/.claude/projects/-data-data-com-anthroid-files/`

#### Implementation (Done - Jan 2026)

**UI Components:**
- History button (clock icon) in main toolbar alongside terminal/settings
- Right-side sliding panel (70% width) for conversation history
- Dimmed left area (30%) - click to close panel
- "+" icon in panel header for new chat
- Conversation list with title, preview, timestamp, message count

**Core Classes:**
- `ConversationManager.kt` - JSONL parsing, conversation listing, storage stats
- `ConversationAdapter.kt` - RecyclerView adapter for conversation items
- Updated `ClaudeFragment.kt` - Side panel show/hide with animations
- Updated `ClaudeCliClient.kt` - `setConversationId()` for resume support

**Features:**
- [x] List past conversations sorted by recent
- [x] Preview shows first user message as title
- [x] Resume conversation with `--resume SESSION_ID` flag
- [x] Start new conversation (clears session)
- [x] Storage stats display (count, total size)
- [x] Slide-in panel animation from right
- [x] Click outside to dismiss panel

**Technical Notes:**
- Tool results filtered from message content display
- Empty conversations filtered from list
- Panel width set to 70% programmatically via `view.post{}`

---

### Phase 5b: Camera Input for Chat (Complete)

Take photos to add visual context to chat messages.

#### Features
- Camera icon in top toolbar (with history/settings/terminal)
- Photo preview before sending
- Multiple photos per message support
- Gallery picker support
- Pending images can be deleted before sending

#### Implementation (Done - Jan 2026)
- [x] Add camera button to toolbar
- [x] CameraCaptureActivity with CameraX
- [x] Photo preview/thumbnail display above input
- [x] Remove photo option (X button on preview)
- [x] Encode photos as base64 for Claude API
- [x] Update ClaudeCliClient to support --image flag
- [x] Gallery picker as alternative to camera

#### Technical Notes
- Images stored in context.cacheDir
- Max dimension 1024px, JPEG 85% quality
- CLI mode: `--image <path>` flag
- API mode: base64 in content array

---

### Phase 5c: QR Code in Chat Camera (Complete)

Integrate QR code scanning into the chat camera.

#### Features (Done - Jan 2026)
- [x] QR scan mode toggle in CameraCaptureActivity
- [x] Real-time QR detection using CameraX ImageAnalysis + ML Kit
- [x] Viewfinder overlay UI with blue corner accents
- [x] Instant text insertion (no confirmation step)
- [x] QR content copied to clipboard automatically
- [x] Insert QR content as text into chat input

#### Technical Notes
- Reused ML Kit BarcodeScanning from QRScannerActivity
- ImageAnalysis runs concurrently with Preview
- Toast notification on clipboard copy


---

### Phase 5d: Markdown Rendering (Complete)

Rich text display for Claude agent messages.

#### Features (Done - Jan 2026)
- [x] Markwon library for markdown rendering
- [x] Table support with proper formatting
- [x] Strikethrough text support
- [x] Auto-linkify URLs
- [x] Clickable links in messages
- [x] Light blue user message bubbles

#### Technical Notes
- Markwon singleton with thread-safe initialization
- TablePlugin, StrikethroughPlugin, LinkifyPlugin
- LinkMovementMethod for clickable links
- Only applied to assistant messages (user messages plain text)


---

### Phase 7: Voice I/O (Complete)

Voice input and output for hands-free interaction using sherpa-onnx (offline, Chinese + English).

#### Voice Input (STT) - sherpa-onnx

**Selected Model**: `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`
- Bilingual: Chinese + English
- Streaming recognition with real-time results
- Offline (no internet required)
- Model size: ~100MB

**Architecture**:
```
┌─────────────────────────────────────────────┐
│            ClaudeFragment                    │
├─────────────────────────────────────────────┤
│  ┌─────────────┐    ┌───────────────────┐   │
│  │ Microphone  │ -> │  SherpaOnnxManager │   │
│  │ (AudioRecord)│    │  (OnlineRecognizer)│   │
│  └─────────────┘    └─────────┬─────────┘   │
│                               │             │
│                     ┌─────────▼─────────┐   │
│                     │  Real-time text   │   │
│                     │  -> Input field   │   │
│                     └───────────────────┘   │
└─────────────────────────────────────────────┘
```

**Key Features**:
- Press-and-hold microphone button to speak
- Real-time transcription displayed in input field
- Endpoint detection (automatic sentence segmentation)
- Language auto-detection (Chinese/English)

#### Voice Output (TTS)
- Android TextToSpeech API (Phase 7b, future)
- Optional: Kokoro TTS for higher quality

#### Files to Create

##### 1. `app/src/main/java/com/anthroid/claude/SherpaOnnxManager.kt`
```kotlin
class SherpaOnnxManager(private val context: Context) {
    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun initialize()  // Load model from assets
    fun startRecording(onResult: (String) -> Unit)
    fun stopRecording()
    fun release()
}
```

##### 2. Model files in `app/src/main/assets/`
```
sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/
├── encoder-epoch-99-avg-1.onnx
├── decoder-epoch-99-avg-1.onnx
├── joiner-epoch-99-avg-1.onnx
└── tokens.txt
```

##### 3. Copy sherpa-onnx Kotlin API files
From: https://github.com/k2-fsa/sherpa-onnx/tree/master/sherpa-onnx/kotlin-api
- `OnlineRecognizer.kt`
- `OnlineStream.kt`
- `FeatureConfig.kt`

#### Files to Modify

##### 1. `app/build.gradle.kts`
- Add sherpa-onnx-jni native library dependency
- Or build from source and include .so files

##### 2. `app/src/main/res/layout/fragment_claude.xml`
- Add microphone ImageButton next to send button

##### 3. `app/src/main/java/com/anthroid/main/ClaudeFragment.kt`
- Initialize SherpaOnnxManager
- Handle microphone button press/release
- Update input field with recognition results

##### 4. `app/src/main/AndroidManifest.xml`
- RECORD_AUDIO permission (already exists)

#### Implementation Order

1. Download sherpa-onnx model files (~100MB)
2. Copy sherpa-onnx Kotlin API files to project
3. Add JNI library (.so files for arm64-v8a, armeabi-v7a)
4. Create SherpaOnnxManager.kt
5. Add microphone button to fragment_claude.xml
6. Integrate voice input in ClaudeFragment.kt
7. Test on device

#### Model Download URLs

```bash
# Bilingual model (Chinese + English)
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2
```

#### Technical Notes

- Sample rate: 16000 Hz
- Audio format: PCM 16-bit mono
- Buffer interval: 100ms
- Endpoint detection: Built-in (rule-based silence detection)
- JNI library: `libsherpa-onnx-jni.so`

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
| M5 | ✅ Done | Conversation management |
| M5b | ✅ Done | Camera input for chat |
| M5c | ✅ Done | QR code in chat camera |
| M6 | ✅ Done | Tool execution working |
| M5d | ✅ Done | Markdown rendering |
| M7 | ✅ Done | Voice I/O (sherpa-onnx STT) |
| M8 | ⏳ | Production-ready release |

---

## Resources

- [Termux App](https://github.com/termux/termux-app)
- [Termux Packages](https://github.com/termux/termux-packages)
- [Claude CLI Documentation](https://docs.anthropic.com/claude-code)
- [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
