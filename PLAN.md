# Anthroid Development Plan

## Vision

Anthroid = Android + Anthropic: A native Android app that brings Claude AI to the terminal, enabling AI-assisted command-line workflows on mobile devices.

## Phases

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

### Phase 2: Claude CLI Integration (Next)

Integrate Claude CLI into the terminal environment.

#### 2.1 Manual CLI Testing
```bash
# In Anthroid terminal:
pkg install nodejs
npm install -g @anthropic-ai/claude-code
claude --version
```

#### 2.2 ClaudeCliClient.kt
Kotlin wrapper for Claude CLI process communication.

```kotlin
class ClaudeCliClient(private val context: Context) {
    private var process: Process? = null

    fun startSession(): Flow<ClaudeEvent>
    fun sendMessage(message: String)
    fun cancelCurrentRequest()
    fun close()
}

sealed class ClaudeEvent {
    data class Text(val content: String) : ClaudeEvent()
    data class ToolUse(val name: String, val input: String) : ClaudeEvent()
    data class Error(val message: String) : ClaudeEvent()
    object Done : ClaudeEvent()
}
```

#### 2.3 ClaudeViewModel.kt
State management for Claude chat UI.

```kotlin
class ClaudeViewModel : ViewModel() {
    val messages: StateFlow<List<Message>>
    val isProcessing: StateFlow<Boolean>

    fun sendMessage(content: String)
    fun cancelRequest()
}
```

#### 2.4 Bootstrap Script
Auto-install Claude CLI on first launch.

```bash
#!/data/data/com.anthroid/files/usr/bin/bash
# bootstrap-claude.sh

if ! command -v node &> /dev/null; then
    pkg install -y nodejs
fi

if ! command -v claude &> /dev/null; then
    npm install -g @anthropic-ai/claude-code
fi

echo "Claude CLI ready"
```

---

### Phase 3: Chat UI

Native Android chat interface for Claude.

#### Components
- `ClaudeActivity.kt` - Main chat screen
- `MessageAdapter.kt` - RecyclerView adapter for messages
- `MessageView.kt` - Custom view for message bubbles
- `CodeBlockView.kt` - Syntax-highlighted code blocks

#### Features
- Markdown rendering
- Code syntax highlighting
- Copy/share messages
- Message history persistence

---

### Phase 4: Tool Integration

Enable Claude to execute terminal commands.

#### Architecture
```
User Message → Claude → Tool Request → Terminal Execution → Result → Claude → Response
```

#### Tool Types
1. **bash**: Execute shell commands
2. **read**: Read file contents
3. **write**: Write/create files
4. **edit**: Modify existing files

#### Security Considerations
- Sandboxed execution in app's data directory
- User confirmation for dangerous operations
- No network access from tool execution by default

---

### Phase 5: Custom Bootstrap (Optional)

Build bootstrap packages with com.anthroid paths for full compatibility.

#### Requirements
- Linux build environment (Docker or native)
- Clone termux-packages repository
- Set `TERMUX_APP__PACKAGE_NAME="com.anthroid"`

#### Build Steps
```bash
# In termux-packages directory
./scripts/run-docker.sh ./build-package.sh bash
./scripts/generate-bootstraps.sh
```

#### Files to Update
- `app/build.gradle` - Update bootstrap URLs/checksums
- Remove LD_LIBRARY_PATH workaround

---

## File Structure (Target)

```
app/src/main/java/com/anthroid/
├── app/
│   ├── TermuxActivity.java      # Terminal UI (existing)
│   ├── TermuxService.java       # Background service (existing)
│   └── TermuxInstaller.java     # Bootstrap installer (modified)
├── claude/
│   ├── ClaudeActivity.kt        # Chat UI (new)
│   ├── ClaudeViewModel.kt       # State management (new)
│   ├── ClaudeCliClient.kt       # CLI wrapper (new)
│   └── ui/
│       ├── MessageAdapter.kt    # Message list (new)
│       ├── MessageView.kt       # Message bubble (new)
│       └── CodeBlockView.kt     # Code display (new)
└── shared/
    └── ... (existing)
```

---

## API Keys & Authentication

#### Options
1. **User-provided**: User enters their Anthropic API key
2. **OAuth**: Anthropic account login (if available)
3. **Bundled**: Ship with limited trial key (not recommended)

#### Storage
- Android Keystore for secure key storage
- Encrypted SharedPreferences as fallback

---

## Testing Strategy

### Unit Tests
- ClaudeCliClient message parsing
- ClaudeViewModel state transitions

### Integration Tests
- CLI process lifecycle
- Terminal command execution

### Manual Tests
- Install on physical device
- Test various Android versions (API 24+)
- Test with/without network

---

## Milestones

| Milestone | Target | Description |
|-----------|--------|-------------|
| M1 | Done | Terminal fork working |
| M2 | - | Claude CLI runs in terminal |
| M3 | - | ClaudeCliClient wrapper complete |
| M4 | - | Basic chat UI functional |
| M5 | - | Tool execution working |
| M6 | - | Production-ready release |

---

## Resources

- [Termux App](https://github.com/termux/termux-app)
- [Termux Packages](https://github.com/termux/termux-packages)
- [Claude CLI Documentation](https://docs.anthropic.com/claude-code)
- [Android Terminal Emulator](https://github.com/nickoala/nicko-terminal)
