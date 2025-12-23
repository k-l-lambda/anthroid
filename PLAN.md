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

### Phase 2: Claude CLI Integration (Complete)

Integrate Claude CLI into the terminal environment.

#### 2.1 Custom Bootstrap Build (Done)
Built custom packages with com.anthroid paths on server 10.121.196.2:
- apt, dpkg, bash, nodejs compiled with correct paths
- Libraries extracted from Docker and installed on device

#### 2.2 Claude CLI Installation (Done)
```bash
# Installed via npm
node /data/data/com.anthroid/files/usr/lib/node_modules/npm/bin/npm-cli.js install -g @anthropic-ai/claude-code
# Version: 2.0.76
```

#### 2.3 Wrapper Script (Done)
Created `/data/data/com.anthroid/files/usr/bin/claude` wrapper:
```bash
#!/data/data/com.anthroid/files/usr/bin/bash
export HOME=/data/data/com.anthroid/files/home
export PATH=/data/data/com.anthroid/files/usr/bin
export PREFIX=/data/data/com.anthroid/files/usr
export LD_LIBRARY_PATH=/data/data/com.anthroid/files/usr/lib
export ANTHROPIC_BASE_URL="https://api.ppinfra.com/anthropic/"
export ANTHROPIC_AUTH_TOKEN="sk_xxx..."
export ANTHROPIC_MODEL="pa/claude-sonnet-4-5-20250929"
exec node /data/data/com.anthroid/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js "$@"
```

#### 2.4 ClaudeCliClient.kt (Next)
Kotlin wrapper for Claude CLI pipe mode communication.

```kotlin
class ClaudeCliClient(private val context: Context) {
    fun sendMessage(message: String): Flow<String>  // Streams response via --print mode
    fun isCliAvailable(): Boolean
}
```

---

### Phase 3: Chat UI (In Progress)

Native Android chat interface for Claude.

#### Components (Done)
- `ClaudeActivity.kt` - Main chat screen
- `ClaudeApiClient.kt` - HTTP API streaming client
- `ClaudeViewModel.kt` - State management with dual-mode support
- `MessageAdapter.kt` - RecyclerView adapter for messages

#### Remaining Tasks
- [ ] Integrate ClaudeCliClient for pipe mode
- [ ] Add mode switching (HTTP API vs CLI pipe)
- [ ] QR code scanner for API configuration

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

### Phase 5: Custom Bootstrap (Done)

Build bootstrap packages with com.anthroid paths for full compatibility.

| Package | Version | Status |
|---------|---------|--------|
| apt | 2.8.1-2 | Built |
| dpkg | 1.22.6-5 | Built |
| bash | 5.3.8 | Built |
| nodejs | 25.2.1 | Built |

Build environment: Docker on 10.121.196.2
Output: `/tmp/anthroid-packages/output/*.deb`

---

## File Structure (Current)

```
app/src/main/java/com/anthroid/
├── app/
│   ├── TermuxActivity.java      # Terminal UI (existing)
│   ├── TermuxService.java       # Background service (existing)
│   └── TermuxInstaller.java     # Bootstrap installer (modified)
├── claude/
│   ├── ClaudeActivity.kt        # Chat UI (done)
│   ├── ClaudeViewModel.kt       # State management (done)
│   ├── ClaudeApiClient.kt       # HTTP API client (done)
│   ├── ClaudeCliClient.kt       # CLI wrapper (todo)
│   └── ui/
│       └── MessageAdapter.kt    # Message list (done)
└── shared/
    └── ... (existing)
```

---

## API Keys & Authentication

#### Options
1. **User-provided**: User enters their Anthropic API key manually
2. **QR Code Scan**: Scan QR code containing API configuration (recommended for quick setup)
3. **OAuth**: Anthropic account login (if available)

#### QR Code Configuration Format
JSON-encoded configuration for quick setup via QR code scan:
```json
{
  "base_url": "https://api.ppinfra.com/anthropic/",
  "auth_token": "sk_xxx...",
  "model": "pa/claude-sonnet-4-5-20250929"
}
```

#### QR Scanner Implementation
- Use ML Kit Barcode Scanning or ZXing library
- Parse JSON and validate required fields
- Store credentials in SharedPreferences
- Update CLI wrapper script with new credentials
- Regenerate wrapper script when credentials change

#### Storage
- SharedPreferences for API configuration
- CLI wrapper script updated dynamically

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
| M2 | Done | Claude CLI runs in terminal |
| M2.5 | Done | CLI wrapper script working |
| M3 | Next | ClaudeCliClient pipe mode integration |
| M4 | - | QR code configuration scanner |
| M5 | - | Tool execution working |
| M6 | - | Production-ready release |

---

## Resources

- [Termux App](https://github.com/termux/termux-app)
- [Termux Packages](https://github.com/termux/termux-packages)
- [Claude CLI Documentation](https://docs.anthropic.com/claude-code)
- [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)
