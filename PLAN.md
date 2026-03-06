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

### Phase 9: Per-App VPN Proxy Tool (Complete)

Enable Anthroid to route specific app traffic through proxy while other apps connect directly.

#### Implementation Summary (Jan 2026)

**Components Built:**
1. **ProxyVpnService.kt** - SOCKS5 VPN service with hev-socks5-tunnel native library
2. **Tun2HttpVpnService.java** - HTTP VPN service with tun2http native library
3. **AuthProxyForwarder.java** - Local proxy for adding authentication headers
4. **ProxyConfigManager.kt** - JSON persistence for proxy server configuration
5. **ProxySettingsActivity.kt** - Full management UI for proxy servers and app lists

**Features:**
- [x] Per-app VPN routing (addAllowedApplication)
- [x] SOCKS5 proxy support (via hev-socks5-tunnel)
- [x] HTTP proxy support with authentication (via tun2http + AuthProxyForwarder)
- [x] Proxy server management UI (add/edit/delete servers)
- [x] Global app list for proxy routing
- [x] set_app_proxy tool uses global app list by default
- [x] Auto-save proxy servers when called via tool
- [x] VPN status detection (checks actual interface state)

**Commit:** `46d0e70` - Add proxy server management UI and fix VPN status detection

---

### Phase 10: Screen Automation Tools (In Progress)

Enable Claude to interact with phone screen - read content, take screenshots, click, type, and capture audio.

#### Implementation Progress (Jan 2026)

**Completed:**
- [x] Create AnthroidAccessibilityService.kt
- [x] Add accessibility_config.xml
- [x] Register service in AndroidManifest.xml
- [x] Add accessibility tools to AndroidTools.kt
- [x] ScreenAutomationOverlay with robot icon (red eyes active, black eyes inactive)
- [x] STOP button on overlay to interrupt operations
- [x] wait_for_element, focus_and_input, get_current_app tools
- [x] MCP server (NanoHTTPD) for tool access - bypasses am broadcast permission issue
- [x] Node.js stdio-to-HTTP bridge for Claude CLI MCP integration
- [x] Tool name cleanup in UI (strip "mcp__anthroid__" prefix)
- [x] Selectable/copyable text in chat messages
- [x] Persistent overlay until agent session ends

**Pending:**
- [x] Add permission management UI in Settings (overlay permission, Jan 2026)
- [ ] Create ScreenCaptureManager.kt for screenshots
- [ ] Implement take_screenshot tool
- [ ] Implement audio capture (API 29+)
- [ ] Test cross-app automation thoroughly

#### Available Tools (via MCP Server on localhost:8765)

| Tool | Input | Description |
|------|-------|-------------|
| `get_accessibility_status` | `{}` | Check if service is enabled |
| `get_screen_text` | `{}` | Get all visible text |
| `get_screen_elements` | `{"include_invisible": false}` | Get element tree as JSON |
| `find_element` | `{"text": "...", "exact_match": false}` | Find element by text |
| `click_element` | `{"text": "..."}` | Click element by text |
| `click_position` | `{"x": 500, "y": 800}` | Click at coordinates |
| `input_text` | `{"text": "..."}` | Type into focused field |
| `swipe` | `{"start_x": 500, "start_y": 1500, "end_x": 500, "end_y": 500}` | Swipe gesture |
| `long_press` | `{"x": 500, "y": 800, "duration_ms": 1000}` | Long press |
| `press_back` | `{}` | Press back button |
| `press_home` | `{}` | Press home button |
| `open_recents` | `{}` | Open recent apps |
| `open_notifications` | `{}` | Open notification panel |
| `scroll` | `{"direction": "up\|down"}` | Scroll |

#### User Setup Required

User must enable accessibility service:
1. Settings > Accessibility > Anthroid Screen Automation
2. Enable the service
3. Grant permission when prompted

#### Core Technologies

| Technology | Purpose | Requirement |
|------------|---------|-------------|
| AccessibilityService | Read screen, click, type, gestures | User enables in Settings |
| MediaProjection | Screenshots, screen recording | User permission dialog |
| AudioPlaybackCapture | System audio capture | API 29+, MediaProjection |

#### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Claude Agent                          │
├─────────────────────────────────────────────────────────┤
│  Tool Request ──► AndroidTools.kt                        │
│                        │                                 │
│         ┌──────────────┼──────────────┐                  │
│         ▼              ▼              ▼                  │
│  AccessibilityService  MediaProjection  AudioCapture     │
│  (UI Automation)       (Screenshots)    (System Sound)   │
└─────────────────────────────────────────────────────────┘
```

#### New Tools

**UI Automation (AccessibilityService):**
| Tool | Description |
|------|-------------|
| `get_screen_text` | Get all text visible on screen |
| `find_element` | Find element by text/id/class |
| `click_element` | Click element by text or description |
| `click_position` | Click at x,y coordinates |
| `input_text` | Type text into focused input field |
| `swipe` | Swipe gesture (scroll, drag) |
| `long_press` | Long press at position |
| `press_back` | Press system back button |
| `press_home` | Press system home button |

**Screen Capture (MediaProjection):**
| Tool | Description |
|------|-------------|
| `take_screenshot` | Capture screen, return image path |
| `start_recording` | Start screen recording |
| `stop_recording` | Stop recording, return video path |
| `capture_audio` | Record system audio (API 29+) |

#### Key Components

1. **AnthroidAccessibilityService.kt**
   ```kotlin
   class AnthroidAccessibilityService : AccessibilityService() {
       fun getScreenText(): List<String>
       fun clickByText(text: String): Boolean
       fun clickAt(x: Float, y: Float): Boolean
       fun inputText(text: String): Boolean
       fun performSwipe(startX, startY, endX, endY): Boolean
   }
   ```

2. **ScreenCaptureManager.kt**
   ```kotlin
   class ScreenCaptureManager(context: Context) {
       fun requestPermission(activity: Activity)
       fun captureScreen(): Bitmap?
       fun startRecording(outputPath: String)
       fun stopRecording()
       fun startAudioCapture(): AudioRecord  // API 29+
   }
   ```

3. **res/xml/accessibility_config.xml**
   ```xml
   <accessibility-service
       android:canRetrieveWindowContent="true"
       android:canPerformGestures="true"
       android:accessibilityFlags="flagRetrieveInteractiveWindows"/>
   ```

#### Permission Flow

```
User opens Anthroid Settings
    │
    ├─► [UI Automation] ──► Opens system Accessibility Settings
    │                       User enables AnthroidAccessibilityService
    │
    └─► [Screen Capture] ──► Shows MediaProjection permission dialog
                            User grants permission (one-time or persistent)
```

#### References

- [AccessibilityService Guide](https://developer.android.com/guide/topics/ui/accessibility/service)
- [MediaProjection API](https://developer.android.com/media/grow/media-projection)
- [AudioPlaybackCapture](https://developer.android.com/media/platform/av-capture)

#### Tasks

- [x] Create AnthroidAccessibilityService.kt skeleton
- [x] Add accessibility_config.xml
- [x] Implement get_screen_text tool
- [x] Implement click/input/swipe tools
- [x] Add wait_for_element, focus_and_input, get_current_app tools
- [x] Create ScreenAutomationOverlay.kt with robot icons
- [x] Implement MCP server (McpServer.kt) for tool access
- [x] Create mcp-http-bridge.js for Claude CLI integration
- [x] Strip MCP prefix from tool names in UI
- [x] Make chat messages selectable/copyable
- [x] Persistent overlay until agent session ends
- [ ] Create ScreenCaptureManager.kt
- [ ] Implement take_screenshot tool
- [x] Add permission management UI in Settings (overlay permission, Jan 2026)
- [ ] Implement audio capture (API 29+)
- [ ] Test cross-app automation

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
│   ├── ClaudeViewModel.kt       # State management (AgentMode: CLI/API/OPENCLAW)
│   ├── ClaudeApiClient.kt       # HTTP API client
│   ├── ClaudeCliClient.kt       # CLI wrapper
│   ├── OpenClawLocalClient.kt   # OpenClaw agent subprocess wrapper
│   └── ui/
│       └── MessageAdapter.kt    # Message list
└── shared/
    └── ... (existing)

assets/openclaw-agent-local/
├── run.mjs                      # Entry point (stream-json stdio adapter)
├── android-tools-bridge.mjs     # MCP :8765 → agent tools bridge
├── config.json                  # Model config (ppinfra proxy)
├── package.json                 # Node.js dependencies
├── agent/
│   └── run-BDwHy0wP.mjs        # Compiled pi-embedded-runner bundle
└── node_modules/                # ~60-80MB (installed on device)
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
| M9 | ✅ Done | Per-app VPN proxy tool |
| M10 | 🚧 In Progress | Screen automation tools |
| M11 | ✅ Done | Custom WebSearch/WebFetch |
| M12 | ✅ Done | AskUserQuestion tool |
| M13 | ⏸️ Deferred | TodoWrite UI (low priority for mobile) |
| M14 | ✅ Done | Edit session title + bulk delete |
| M15 | ✅ Done | Quick send candidates |
| M16 | ✅ Done | OpenClaw agent runtime (Phase 1+2+3) |
| M16b | ✅ Done | OpenClaw tool bar UI fix |
| M17 | ✅ Done | APK asset bundling + version-based auto-extraction |
| M18 | ✅ Done | Gateway connection (session sync, memory, notifications) |
| M19 | ✅ Done | OpenClaw auto-setup (auto mode, --ignore-scripts, stubs) |
| M20 | ✅ Done | Gateway message notifications (ForegroundService, per-session grouping) |

---

## Resources

- [Termux App](https://github.com/termux/termux-app)
- [Termux Packages](https://github.com/termux/termux-packages)
- [Claude CLI Documentation](https://docs.anthropic.com/claude-code)
- [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)

---

### Phase 11: Custom WebSearch and Proxy Fetch (Complete)

Re-implement server-side tools for third-party API compatibility.

#### Background

Original Claude Code CLI tools:
- **WebSearch** - Server-side API tool (`web_search_20250305`), only works with Anthropic's official API
- **WebFetch** - Client-side tool, no proxy support

When using custom base URL (e.g., proxy API), WebSearch fails because the server doesn't implement `web_search_20250305`.

#### Implementation Plan (Jan 2026)

**1. WebSearch with SerpAPI:**
- Replace server-side implementation with client-side SerpAPI call
- Input: `{query, allowed_domains?, blocked_domains?}`
- Uses `SERPAPI_API_KEY` environment variable
- Fallback to error message if API key not set

**2. WebFetch with Proxy Support:**
- Add optional `proxy` parameter to input schema
- Format: `http://host:port` or `http://user:pass@host:port`
- Uses `https-proxy-agent` or axios proxy config

#### Files Modified

- `claude-code/cli.js` - WebSearch and WebFetch tool implementations
- `app/src/main/assets/claude-code/cli.js` - Copy updated file

#### Tasks

- [x] Implement WebSearch with SerpAPI in cli.js
- [x] Add proxy parameter to WebFetch tool
- [x] Copy updated cli.js to assets
- [x] Test with custom base URL

#### SerpAPI Integration

```javascript
// WebSearch call() implementation
async call(input) {
    const { query, allowed_domains, blocked_domains } = input;
    const apiKey = process.env.SERPAPI_API_KEY;
    if (!apiKey) {
        return { error: "SERPAPI_API_KEY not set" };
    }

    const params = new URLSearchParams({
        q: query,
        api_key: apiKey,
        engine: "google"
    });

    const response = await fetch(`https://serpapi.com/search?${params}`);
    const data = await response.json();

    // Parse organic_results into {title, url} format
    return { results: data.organic_results.map(r => ({
        title: r.title,
        url: r.link
    })) };
}
```

#### WebFetch Proxy Parameter

```javascript
// Input schema update
{
    url: string,
    prompt: string,
    proxy?: string  // Optional: "http://host:port" or "http://user:pass@host:port"
}

// Fetch with proxy
const agent = proxy ? new HttpsProxyAgent(proxy) : undefined;
const response = await fetch(url, { agent });
```

---

### Phase 12: AskUserQuestion Tool (Complete)

Enable Claude to ask the user multiple-choice questions for clarification, preferences, and decisions.

#### Background

Claude Code CLI has an `AskUserQuestion` tool that lets Claude gather information from users via multiple-choice questions. This is essential for:
- Clarifying ambiguous instructions
- Getting user preferences on implementation choices
- Making decisions during task execution
- Offering choices about direction

Currently not supported in Anthroid because the CLI tool requires terminal-based UI interaction.

#### Schema (from claude-code/cli.js)

```kotlin
data class AskUserQuestionInput(
    val questions: List<Question>,      // 1-4 questions
    val answers: Map<String, String>?   // Optional, filled by UI
)

data class Question(
    val question: String,       // Full question text, e.g. "Which auth method?"
    val header: String,         // Short label (max 12 chars), e.g. "Auth method"
    val options: List<Option>,  // 2-4 options
    val multiSelect: Boolean    // Allow multiple selections
)

data class Option(
    val label: String,          // Display text (1-5 words)
    val description: String     // Explanation of what option means
)
```

#### Tool Result Format

```
"User has answered your questions: "${q1}"="${a1}", "${q2}"="${a2}"..."
```

#### Implementation Plan (Option A: Dialog-based UI)

**1. Create AskUserQuestionActivity.kt**
```kotlin
class AskUserQuestionActivity : AppCompatActivity() {
    // Display questions one at a time in a ViewPager or scroll view
    // Each question shows:
    //   - Header as chip/tag
    //   - Question text
    //   - RadioGroup (single select) or CheckboxGroup (multi select)
    //   - "Other" option with EditText for custom input
    //   - Next/Previous buttons
    //   - Submit button on last question
    
    companion object {
        const val EXTRA_QUESTIONS = "questions"  // JSON serialized
        const val EXTRA_ANSWERS = "answers"      // Result JSON
    }
}
```

**2. Layout: activity_ask_user_question.xml**
```xml
<!-- ViewPager2 or NestedScrollView for questions -->
<!-- Each question card:
     - MaterialChip for header
     - TextView for question
     - RadioGroup/CheckboxGroup for options
     - TextInputEditText for "Other"
     - Navigation buttons -->
```

**3. Update ClaudeViewModel.kt**
- Add `_pendingQuestion` StateFlow for question UI trigger
- Handle `ask_user_question` tool call
- Launch AskUserQuestionActivity via ActivityResultLauncher
- Send answers back to Claude API

**4. Update ClaudeApiClient.kt**
- Add `ask_user_question` tool definition with schema
- Handle tool result with user answers

**5. Integration Flow**
```
Claude sends AskUserQuestion tool
    │
    ▼
ClaudeViewModel receives ToolUse event
    │
    ▼
Launch AskUserQuestionActivity with questions JSON
    │
    ▼
User answers questions (radio/checkbox/text)
    │
    ▼
Activity returns answers map
    │
    ▼
ClaudeViewModel sends tool result to API
    │
    ▼
Claude continues with user's answers
```

#### Files to Create

| File | Description |
|------|-------------|
| `AskUserQuestionActivity.kt` | Question display and answer collection UI |
| `activity_ask_user_question.xml` | Layout with ViewPager/scroll |
| `item_question.xml` | Single question card layout |

#### Files to Modify

| File | Change |
|------|--------|
| `ClaudeViewModel.kt` | Handle ask_user_question tool, launch activity |
| `ClaudeApiClient.kt` | Add tool definition |
| `ClaudeFragment.kt` | Register ActivityResultLauncher |
| `AndroidManifest.xml` | Register AskUserQuestionActivity |

#### Tasks

- [x] Create AskUserQuestionActivity.kt skeleton
- [x] Create activity_ask_user_question.xml layout
- [x] Create item_question.xml for question cards
- [x] Implement single question display with options
- [x] Implement "Other" text input option
- [x] Implement multi-select support (checkboxes)
- [x] Add navigation between questions (scroll view)
- [x] Add tool definition to McpServer.kt (CLI mode)
- [x] Handle tool call in ClaudeViewModel.kt
- [x] Register ActivityResultLauncher in ClaudeFragment.kt
- [x] Test with Claude asking questions

#### Implementation Summary (Jan 2026)

**CLI Mode (MCP Server):**
- Added `ask_user_question` tool to McpServer.kt
- Tool blocks until user responds using CompletableDeferred
- McpServer.onAskUserQuestion callback triggers ClaudeFragment to launch AskUserQuestionActivity
- Answer key uses question text (not indexed like "q0")

**Bug Fixes:**
- Fixed crash "specified child already has parent" when adding otherRadio to radioGroup
- Fixed RadioButton tracking for nested layouts (options with descriptions)
- Fixed answer key mismatch in formatAnswersResult()

**Commit:** `7246252 Fix ask_user_question tool for CLI mode (MCP)`

---

### Phase 13: TodoWrite UI (Deferred)

Display Claude's task progress in native Android UI.

**Status:** Deferred - Mobile Anthroid is a lightweight agent where complex multi-step tasks are less common. TodoWrite UI may be reconsidered if usage patterns show demand.

#### Background

Claude Code CLI has a `TodoWrite` tool that displays task progress in the terminal. In Android, this needs a native UI representation to show:
- Task list with status (pending/in_progress/completed)
- Active task indicator
- Progress tracking

#### Claude Code Native Tools Status

| Tool | Status | Notes |
|------|--------|-------|
| Bash | ✅ Works | CLI built-in |
| Read/Write/Edit | ✅ Works | CLI built-in |
| Glob/Grep | ✅ Works | CLI built-in |
| Agent (Task) | ✅ Works | CLI built-in |
| TaskOutput | ✅ Works | CLI built-in |
| KillShell | ✅ Works | CLI built-in |
| WebSearch | ✅ Re-implemented | Phase 11 - SerpAPI |
| WebFetch | ✅ Re-implemented | Phase 11 - proxy support |
| AskUserQuestion | ✅ Re-implemented | Phase 12 - MCP server |
| TodoWrite | ⏸️ Deferred | Low priority for lightweight mobile agent |
| NotebookEdit | ❌ Not needed | Jupyter not relevant for mobile |
| ListMcpResources | ✅ Works | Via MCP server |
| ReadMcpResource | ✅ Works | Via MCP server |

#### Implementation Plan

**Option A: Floating Overlay**
- Show task list as floating overlay on screen automation
- Update in real-time as tasks change
- Minimal UI impact

**Option B: Chat Integration**
- Show task list as special message type in chat
- Update existing message as tasks change
- Integrated with conversation flow

#### Tasks

- [ ] Design TodoWrite UI approach (overlay vs chat)
- [ ] Create TodoListView.kt component
- [ ] Handle TodoWrite tool events from CLI
- [ ] Update UI in real-time
- [ ] Test with multi-step tasks


---

### Phase 14: Edit Agent Session Title (Complete)

Allow users to edit the title of agent conversation sessions.

#### Implementation (Jan 2026)

**Features:**
- [x] Long-press session item to enter edit mode
- [x] Tap title to edit via dialog
- [x] Custom title stored in SharedPreferences
- [x] "Reset" button to restore auto-generated title
- [x] Bulk delete with multi-selection mode

**Files Modified:**
- `ConversationManager.kt` - Add setCustomTitle(), getCustomTitle() methods
- `ConversationAdapter.kt` - Long-press enter edit mode, title tap listener
- `ClaudeFragment.kt` - Edit dialog, delete confirmation, bulk delete

**Release:** v0.10.4

---

### Phase 15: Quick Send Candidates (Complete)

Frequently used short messages as quick send buttons for faster interaction.

#### Implementation (Jan 2026)

**Features:**
- [x] Track messages < 16 chars by frequency (SharedPreferences)
- [x] Strip "🎤 " prefix from voice input before tracking
- [x] Show top 5 candidates with count >= 5
- [x] Display only when keyboard is visible
- [x] Right-aligned chips above input bar
- [x] Tap chip to send immediately
- [x] Refresh after each message send

**Files Created:**
- `QuickSendManager.kt` - Frequency tracking and persistence
- `QuickSendAdapter.kt` - RecyclerView adapter for chips
- `item_quick_send.xml` - Material Chip layout with FrameLayout wrapper

**Files Modified:**
- `fragment_claude.xml` - RecyclerView for candidates
- `ClaudeFragment.kt` - Keyboard visibility detection, show/hide logic

**Technical Notes:**
- ViewTreeObserver.OnGlobalLayoutListener detects keyboard visibility
- Keyboard detected when screen height reduced by >15%
- Chips wrapped in FrameLayout for right-alignment (layout_gravity="end")

**Release:** v0.10.5

---

### Phase 16: OpenClaw Agent Integration (In Progress)

Replace Claude CLI agent with OpenClaw's `pi-embedded-runner` for sophisticated tool-use, model selection, and context management.

**Key principle**: Anthroid works independently without OpenClaw gateway. Gateway features (memory sync, session sync, notifications) are optional add-ons.

#### Architecture

```
┌──────────────────────────────────────────────────┐
│  Anthroid App (Android)                          │
│                                                  │
│  ┌────────────────────────────────────────────┐  │
│  │  Kotlin Layer                              │  │
│  │  ├─ Chat UI (existing MessageAdapter)      │  │
│  │  ├─ ClaudeViewModel (AgentMode.OPENCLAW)   │  │
│  │  ├─ MCP Server (NanoHTTPD :8765)           │  │
│  │  └─ AndroidTools (60+ tools)               │  │
│  └──────────────┬─────────────────────────────┘  │
│                 │ HTTP localhost:8765              │
│  ┌──────────────┴─────────────────────────────┐  │
│  │  Termux / Node.js Layer                    │  │
│  │  ├─ openclaw-agent-local/                  │  │
│  │  │   ├─ run.mjs (entry point)             │  │
│  │  │   ├─ pi-embedded-runner (compiled JS)   │  │
│  │  │   ├─ android-tools-bridge.mjs           │  │
│  │  │   ├─ config.json (models, API keys)     │  │
│  │  │   └─ node_modules/ (~60-80MB)           │  │
│  │  └─ [optional] memory/ (node:sqlite)       │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

#### Sub-phase 16.1: Extract & Bundle Agent Runtime ✅

Extracted minimal agent from OpenClaw `src/agents/pi-embedded-runner/` into standalone package.

**Commits:**
- `424ff41` feat: add OpenClaw agent runtime with Android tools bridge (Phase 1+2)

**Files created:**
- `assets/openclaw-agent-local/run.mjs` — entry point, stream-json stdio adapter
- `assets/openclaw-agent-local/android-tools-bridge.mjs` — MCP :8765 → agent tools
- `assets/openclaw-agent-local/config.json` — model config (ppinfra proxy)
- `assets/openclaw-agent-local/agent/` — compiled pi-embedded-runner bundle
- `assets/openclaw-agent-local/package.json` — dependencies

#### Sub-phase 16.2: Android Tools Bridge ✅

Created `android-tools-bridge.mjs` that wraps anthroid's MCP server tools as OpenClaw-compatible tool definitions.

- Dynamic tool discovery via `tools/list` MCP call
- All 60+ Android tools available to the agent
- Base coding tools (read, write, edit, exec) coexist with Android tools

#### Sub-phase 16.3: Kotlin Integration ✅

Created `OpenClawLocalClient.kt` — subprocess wrapper similar to `ClaudeCliClient.kt`.

**Commits:**
- `bc9928f` feat: add OpenClaw agent mode with Kotlin integration (Phase 3)
- `34261d4` fix: address code review issues in OpenClawLocalClient
- `c2ca473` fix: add ppinfra proxy support and fix text delta duplication

**Key files:**
- `app/src/.../claude/OpenClawLocalClient.kt` — subprocess management via ProcessBuilder
- `app/src/.../claude/ClaudeViewModel.kt` — added `AgentMode.OPENCLAW`

**Stream-json compatibility:** run.mjs outputs same event format as ClaudeCliClient expects:
```json
{"type":"message_start","message":{"id":"msg_xxx","role":"assistant"}}
{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}
{"type":"tool_use","id":"tu_xxx","name":"exec","input":"{}"}
{"type":"tool_result","tool_use_id":"tu_xxx","content":"..."}
{"type":"message_stop"}
```

#### Sub-phase 16.4: Tool Bar UI Fix ✅

Fixed tool bar not displaying during OpenClaw agent tool usage.

**Commit:** `eb333ca` feat: add tool bar UI support for OpenClaw agent mode

**Root cause:** run.mjs emitted tool output as plain text deltas instead of structured tool events.

**Fix:**
- run.mjs: Added `emitToolUse()` / `emitToolResultEvent()` using `onAgentEvent` callback
- OpenClawLocalClient.kt: Added `tool_use` and `tool_result` event parsing in `parseStreamEvent()`

#### Sub-phase 16.5: APK Asset Bundling ✅

**Commit:** `e016195` feat: bundle OpenClaw agent in APK assets with auto-extraction

- Gradle `copyOpenClawAgent` task bundles core files (~6MB) excluding node_modules
- `TermuxInstaller.installOpenClawAgent()` extracts on fresh install (bootstrap)
- `OpenClawLocalClient.updateAgentIfNeeded()` re-extracts on app version change
- `OpenClawLocalClient.ensureDependencies()` runs npm install if node_modules missing
- `ClaudeViewModel` pre-chat dependency check for OpenClaw mode

#### Sub-phase 16.6: Gateway Connection (Optional) ✅

**Status: Done**

Add optional connectivity to OpenClaw gateway for session sync, memory, and notifications.

**Prerequisites:**
- OpenClaw gateway running on camus-station (101.43.33.48:40445)
- Ed25519 device identity for authentication

**Implementation:**
- [x] Create `DeviceIdentityStore.kt` — Ed25519 keypair via BouncyCastle lightweight API
- [x] Create `DeviceAuthPayload.kt` — v3 pipe-delimited auth payload
- [x] Create `DeviceAuthStore.kt` — Token persistence via SharedPreferences
- [x] Create `GatewaySession.kt` — OkHttp WebSocket with reconnection loop
- [x] Create `GatewayManager.kt` — High-level gateway lifecycle + session sync
- [x] Add OkHttp + BouncyCastle dependencies to build.gradle
- [x] Integrate into ClaudeViewModel (auto-connect from prefs, session sync at SessionEnded)
- [x] Add gateway settings UI (host, port, token, enable switch)
- [x] Add `DEBUG_CONFIG_GATEWAY` broadcast for ADB testing
- [x] Implement session sync → push conversation to "Anthroid" session via `chat.inject`
- [x] Implement notification channel (receive `notification.push` events)
- [x] Optional: GatewayForegroundService for background WebSocket → implemented in 16.7
- [x] Testing on device — connected to real gateway via SSH tunnel + ADB reverse

#### Sub-phase 16.7: Gateway Message Notifications ✅

IM-like system notifications for incoming gateway messages when app is backgrounded.

**Commit:** `9152b3b`

**Architecture:**
- `GatewayForegroundService` — keeps WebSocket alive via persistent low-importance notification
- `GatewayNotificationHelper` — per-session grouped notifications (HIGH importance)
- `GatewayManager` — handles `"chat"` events (`state=final`, `role=assistant`) from gateway

**Changes:**
1. New `GatewayNotificationHelper.kt` — multi-session notification grouping, summary when 2+ sessions
2. New `GatewayForegroundService.kt` — owns GatewayManager lifecycle, START_STICKY for auto-restart
3. Modified `GatewayManager.kt` — added `"chat"` event parsing, `onChatMessage` callback
4. Modified `ClaudeViewModel.kt` — delegates gateway to service, nullable access
5. Modified `MainPagerActivity.kt` — clears notifications on resume, handles deep-link tap
6. Modified `ClaudeFragment.kt` — debug gateway config uses service instead of direct manager
7. Registered service in `AndroidManifest.xml`

#### Sub-phase 16.7.1: Direct API Response Notifications ✅

Show system notification when Claude API response completes while app is backgrounded. Phase 16.7 only covered gateway events — direct API responses arrived silently.

**Changes:**
1. Modified `ClaudeViewModel.kt` — added `showDirectResponseNotification()` method, called at `SessionEnded` when `!isAppInForeground`
2. Modified `MainPagerActivity.kt` — cancel direct notification (ID 49999) in `onResume()`

Uses same `gateway_messages` notification channel (idempotent channel creation). Fixed notification ID `49999` since only one direct conversation runs at a time.

#### Sub-phase 16.8: Auto-Setup & Auto Mode ✅

Fix three issues preventing OpenClaw from working out-of-the-box on fresh install.

**Commit:** `cd2fe15`

**Problems fixed:**
1. **Auto mode ignores OpenClaw** — `auto` preference always picked API when configured. Now: OpenClaw > API > CLI.
2. **npm install fails** — koffi native addon build fails without CMake. Added `--ignore-scripts`.
3. **Stubs never created** — `.install_deps.sh` didn't run `create-stubs.mjs`. Added auto-stub creation after npm install.

**Files modified:**
- `ClaudeViewModel.kt` — auto mode priority: `openclawAvailable && apiConfigured → OPENCLAW`
- `TermuxInstaller.java` — `.install_deps.sh` adds `--ignore-scripts` and `node create-stubs.mjs`
- `OpenClawLocalClient.kt` — `ensureDependencies()` checks stub marker, adds `runCreateStubs()` helper

#### Sub-phase 16.9: Remote Agent View (In Progress)

View and interact with remote AI agent sessions directly from Anthroid.

**Architecture:**
- `/list-remotes` slash command — lists OpenClaw sessions (no args) or tmux sessions on a host (with hostname arg)
- `RemoteAgentFragment` — full-screen overlay with message list (OpenClaw) or terminal view (SSH+tmux)
- `RemoteAgentViewModel` — state management for both modes

**Two remote agent sources:**
1. **OpenClaw sessions** — subscribe to gateway events filtered by sessionKey, send via `chat.inject`
2. **SSH+tmux sessions** — periodic `tmux capture-pane` via SSH, send via `tmux send-keys`

**New files:**
- `remote/RemoteSessionInfo.kt` — data model for remote sessions
- `remote/SshTmuxClient.kt` — SSH + tmux interaction helper
- `remote/RemoteAgentFragment.kt` — full-screen overlay Fragment
- `remote/RemoteAgentViewModel.kt` — state management
- `res/layout/fragment_remote_agent.xml` — layout

**Modified files:**
- `ClaudeViewModel.kt` — slash command framework (`/list-remotes`, `/compact`)
- `ClaudeFragment.kt` — observe slash command events, session list dialog, fragment transaction
- `GatewayManager.kt` — `listSessions()`, `injectMessage()`, `onRemoteSessionEvent` callback

**Dependencies:**
- Gateway API: `session.list` RPC (needs server-side verification)
- SSH keys configured in Termux `~/.ssh/`

---

#### Sub-phase 16.10: Gateway "anthroid" Mode ✅ COMPLETE

**Problem:** `chat.send` messages from Anthroid appear as `channel="webchat"` on the remote agent because the gateway maps `CLIENT_MODE="ui"` → webchat channel/provider/surface. Passing `channel` in `chat.send` params is rejected by the API (`unexpected property`).

**Solution:** Register a dedicated `mode="anthroid"` in the OpenClaw gateway server.

**Requirements for the new mode:**
- `channel`: `"anthroid"`
- `provider`: `"KL"`
- `surface`: `"anthroid"`
- Markdown rendering: **enabled** (same as `"ui"` mode — Anthroid renders markdown)

**Android client change:**
- `GatewayManager.kt`: change `CLIENT_MODE = "ui"` → `CLIENT_MODE = "anthroid"`

**Gateway server change:**
- Register surface/channel mapping for `mode="anthroid"` in the gateway's client mode registry
- Ensure markdown flag is set (same as `"ui"`)

**Files to modify:**
- Android: `app/src/main/java/com/anthroid/gateway/GatewayManager.kt` (1-line change)
- Gateway server: wherever `"ui"` mode is defined and mapped to channel attributes

---

#### Bug Fix: OpenClaw Mode Image Input — HTTP 400 Error ✅ FIXED

**Problem:** In OpenClaw mode, when the user attaches an image to a message, the API returns `HTTP 400: messages.0.content.1.image.source.base64.media_type: Field required`.

**Root cause:** `run.mjs` extracted images from stdin JSON in Claude API format (`{ type: "base64", media_type, data }`) instead of the pi-ai `ImageContent` format (`{ type: "image", data, mimeType }`). When the pi-ai Anthropic provider serialized images for the API, `block.mimeType` was `undefined` (the property was named `media_type`), so `media_type: undefined` was omitted from JSON.

**Fix:** Changed `run.mjs` lines 522-526 to construct correct `ImageContent` objects:
```javascript
// Before (wrong): { type: "base64", media_type: block.source.media_type, data: block.source.data }
// After (correct): { type: "image", data: block.source.data, mimeType: block.source.media_type }
```

**Files modified:** `assets/openclaw-agent-local/run.mjs`

---

#### Sub-phase 16.11: Remote Agent Session Result as Local Tool Bar ✅ COMPLETE

**Context:** When the user sends a message to a remote agent via the Remote Agent View, the remote agent processes it and generates a response. Currently the response only appears in the Remote Agent View. It would be useful to embed the result back into the current local conversation as a tool bar item, giving the local context awareness of remote session activity.

**Behavior:**
- After a remote agent conversation exchange completes (user sends + agent responds), a tool bar entry is added to the **current local conversation** in `ClaudeActivity`
- Tool bar **input**: the remote session's entry info (session key, agent display name, user message sent)
- Tool bar **output**: the final response content from the remote agent session
- The tool bar is non-interactive (read-only annotation); tapping shows the detail dialog with full content
- Multiple back-and-forth exchanges in the remote view each create one tool bar entry in the local conversation

**Architecture:**
```
RemoteAgentViewModel.sendMessage()
  ↓ user sends to remote session
GatewayManager.sendChatMessage()
  ↓ agent responds (onRemoteSessionEvent)
RemoteAgentViewModel.onResponse()
  ↓ emit RemoteAgentResultEvent(sessionKey, userMessage, responseText)
ClaudeViewModel.onRemoteAgentResult()
  ↓ append Message(role=TOOL, toolName="remote:$displayName", toolInput=userMessage, toolOutput=response)
ClaudeActivity MessageAdapter renders tool bar
```

**New components:**
- `RemoteAgentResultEvent` broadcast (LocalBroadcastManager or SharedFlow)
- `ClaudeViewModel.remoteAgentResultFlow` collector
- Tool bar display uses existing `ToolViewHolder` — `toolName` = remote agent label, `toolInput` = sent text, `toolOutput` = agent response

**Files to modify:**
- `remote/RemoteAgentViewModel.kt` — emit result event after response received
- `claude/ClaudeViewModel.kt` — collect remote result events, append TOOL messages
- `claude/ui/MessageAdapter.kt` — possibly add special styling for remote tool bars
- `remote/RemoteAgentFragment.kt` — pass activity reference or use shared flow

---

### Phase 17: OpenClaw Local Agent — Independent Workspace with Gateway Sync (Planned)

**Vision:** The local Anthroid OpenClaw agent runs as a fully independent agent on the Android device with its own persistent workspace, memory, and skill set. It can optionally synchronize data with gateway agents through preset sync skills, enabling a "distributed agent" model.

#### 17.1 Independent Local Workspace

**Current state:** The local agent (`pi-embedded-runner`) uses a single `.sessions/` directory for conversation history and no persistent workspace or memory.

**Goal:** Treat the local agent as a first-class agent peer with:
- **Workspace**: a dedicated directory (`~/workspace/` or `~/agent-workspace/`) that persists across sessions, for files, notes, and agent-generated content
- **Memory**: local memory store (similar to OpenClaw's gateway memory), persisted in SQLite or JSONL
- **Skills**: a set of local skills that the agent can invoke, including sync skills
- **Identity**: a local agent ID and profile that distinguishes it from gateway agents

**Architecture:**
```
Android device
├─ openclaw-agent-local/
│   ├─ run.mjs                        (entry point)
│   ├─ agent/                         (pi-embedded-runner bundle)
│   ├─ .sessions/                     (conversation history)
│   └─ workspace/                     (persistent agent workspace)
│       ├─ memory/                    (local memory store)
│       ├─ skills/                    (local skill definitions)
│       └─ data/                      (agent-generated files)
```

**Initialization:**
- When the user first sets up the local agent, they can optionally select a gateway agent to copy baseline settings from
- Copied items: skill definitions, agent profile/persona, tool configurations
- NOT copied: conversation history, diary entries, memory entries (fresh start)
- The user can also start from scratch without copying from any gateway agent

#### 17.2 Gateway-Local Data Sync via Preset Skills

**Concept:** Define a set of "sync skills" that can be triggered manually or on a schedule:

| Skill | Direction | What syncs |
|-------|-----------|-----------|
| `sync-memory-pull` | Gateway → Local | Pull selected memory entries from a gateway agent |
| `sync-memory-push` | Local → Gateway | Push local memory entries to a gateway agent |
| `sync-workspace-pull` | Gateway → Local | Pull specific workspace files from a gateway agent |
| `sync-workspace-push` | Local → Gateway | Push local workspace files to a gateway agent |
| `sync-skills-pull` | Gateway → Local | Update local skills from a gateway agent's skill set |

**Sync mechanism:**
- Sync skills use the existing `GatewayManager.sendChatMessage()` / `chat.inject` to communicate with the gateway agent
- The gateway agent has corresponding "sync handler" skills that respond to sync requests
- Sync state is tracked locally (last sync timestamp per item type)

**Sync trigger options:**
- Manual: user triggers sync from settings or a slash command (`/sync-memory`, `/sync-workspace`)
- Scheduled: configurable interval (e.g. daily) using Android `WorkManager`
- Event-driven: sync after certain actions (e.g. after local conversation ends, push summary to gateway)

#### 17.3 Baseline Initialization from Gateway Agent

**Flow:**
1. User opens Anthroid for first time (or resets local agent)
2. App checks if gateway is connected
3. If connected and gateway has agents: offer to copy baseline from a selected agent
4. User selects an agent (or "start fresh")
5. App fetches agent's skill definitions and profile via gateway RPC
6. Creates local workspace with those settings
7. Local agent starts with the copied baseline, fresh conversation history

**Gateway RPCs needed:**
- `agent.getProfile(agentId)` — fetch agent profile/persona
- `agent.getSkills(agentId)` — fetch skill definitions
- These are read-only; no write to gateway agent

#### 17.4 Implementation Notes

**Priority order:**
1. Independent workspace directory (minimal change to `buildEnvironment()`)
2. Local memory store (extend ConversationManager or add MemoryManager)
3. Sync skills (implement as built-in slash commands)
4. Gateway initialization flow (UI in settings/onboarding)

**Key files:**
- `claude/OpenClawLocalClient.kt` — add `WORKSPACE_DIR`, memory config to environment
- `claude/ConversationManager.kt` — or new `MemoryManager.kt` for local memory
- `main/ClaudeFragment.kt` — sync slash commands (`/sync-memory`, `/sync-workspace`)
- `gateway/GatewayManager.kt` — new RPCs for agent profile/skill fetch
- Settings UI — baseline initialization wizard

---

#### Sub-phase 16.12: Anthroid Timed Message Delivery — Polling Queue ✅ COMPLETE

**Problem:** The `message` tool's scheduled delivery cannot push to Anthroid clients.
Root cause: "webchat" (internal WebSocket) has no persistent delivery mechanism.

**Solution: Pending message queue + 60s foreground-service polling**
- Target latency: ≤ 60s (max poll interval)
- No FCM/GMS dependency (works on OPPO/ColorOS)
- Uses existing `GatewayForegroundService` (already a foreground service → no battery restriction)

**Architecture:**

```
[agent message tool, timer fires]
        ↓
  channel-selection.ts: detect "anthroid"/"webchat" internal channel
        ↓
  Store in pending_messages table (gateway SQLite/memory)
        ↓  ← no "Unknown channel" error
  Response: { ok: true, queued: true }

[Anthroid app, every 60s or on WebSocket reconnect]
        ↓
  session.drainPending(sessionKey) RPC
        ↓
  Gateway returns pending messages + marks delivered
        ↓
  Android: append to message list + show system notification
```

**Gateway changes (openclaw `kl/develop`):**

1. `src/infra/outbound/channel-selection.ts`:
   - When `normalized === INTERNAL_MESSAGE_CHANNEL` and context is a gateway session:
     store message in pending queue, return `{ channel: "anthroid", queued: true }`

2. New `src/gateway/server-methods/pending.ts`:
   - `session.drainPending({ sessionKey })` RPC
   - Returns array of pending messages for that session
   - Marks them as delivered (idempotent)
   - Backed by in-memory store (Map<sessionKey, PendingMessage[]>) — simple, no persistence needed for now

3. `src/gateway/server.ts` or existing router: register the new handler

**Android changes (`anthroid-openclaw`):**

1. `GatewayManager.kt`:
   - `suspend fun drainPendingMessages(sessionKey: String): List<PendingMessage>` — calls `session.drainPending` RPC
   - Data class `PendingMessage(id: String, content: String, createdAt: Long)`

2. `GatewayForegroundService.kt`:
   - Add `startPollingLoop()` coroutine (60s interval) in `serviceScope`
   - Polls all observed sessions: `gatewayManager.drainPendingMessages(sessionKey)`
   - Sends results as `RemoteSessionEvent` to `GatewayManager.remoteSessionEventFlow`
   - Also trigger on WebSocket reconnect (for immediate delivery after offline)

3. `ClaudeActivity.kt` or `GatewayForegroundService.kt`:
   - Show Android system notification when pending messages arrive while app is in background

**Files to modify:**
- openclaw: `src/infra/outbound/channel-selection.ts`, new `src/gateway/server-methods/pending.ts`
- Android: `gateway/GatewayManager.kt`, `gateway/GatewayForegroundService.kt`, `claude/ClaudeActivity.kt`

**Note:** In-memory pending store resets on gateway restart. For persistence, a SQLite-backed store can be added later.
