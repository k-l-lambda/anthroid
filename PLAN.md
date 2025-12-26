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

## In Progress / Planned Phases

### Phase 4: QR Code Configuration (Next)

Quick setup via QR code scan for API configuration.

#### QR Code Format
```json
{
  "base_url": "https://api.ppinfra.com/anthropic/",
  "auth_token": "sk_xxx...",
  "model": "pa/claude-sonnet-4-5-20250929"
}
```

#### Implementation
- [ ] Integrate ML Kit Barcode Scanning or ZXing library
- [ ] Parse JSON and validate required fields
- [ ] Store credentials in SharedPreferences
- [ ] Update CLI wrapper script with new credentials

---

### Phase 5: Tool Integration

Enable Claude to execute terminal commands.

#### Tool Types
1. **bash**: Execute shell commands
2. **read**: Read file contents
3. **write**: Write/create files
4. **edit**: Modify existing files

#### Security Considerations
- Sandboxed execution in app's data directory
- User confirmation for dangerous operations

---

### Phase 6: Voice I/O

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

### Phase 7: Production Release

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
│   └── TermuxInstaller.java     # Bootstrap installer
├── main/
│   ├── MainPagerActivity.kt     # Main launcher with ViewPager2
│   ├── ClaudeFragment.kt        # Chat UI fragment
│   └── TerminalFragment.kt      # Terminal launcher fragment
├── claude/
│   ├── ClaudeActivity.kt        # Standalone chat (backup)
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
| M4 | 🔄 Next | QR code configuration scanner |
| M5 | ⏳ | Tool execution working |
| M6 | ⏳ | Voice I/O (STT + TTS) |
| M7 | ⏳ | Production-ready release |

---

## Resources

- [Termux App](https://github.com/termux/termux-app)
- [Termux Packages](https://github.com/termux/termux-packages)
- [Claude CLI Documentation](https://docs.anthropic.com/claude-code)
- [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
