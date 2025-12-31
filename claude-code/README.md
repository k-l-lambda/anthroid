# Claude Code - Anthroid Fork

Fork of `@anthropic-ai/claude-code` v2.0.76 with Android/Termux support.

## Patches Applied

### 1. Shell Detection (line ~383015)
Added Android paths to shell search:
```javascript
X = ["/data/data/com.anthroid/files/usr/bin", "/data/data/com.termux/files/usr/bin", ...]
```

### 2. Ripgrep Detection (line ~15827)
Added fallback to system ripgrep for Android:
```javascript
// Anthroid: Try Android/Termux ripgrep paths
let androidRgPaths = ["/data/data/com.anthroid/files/usr/bin/rg", ...];
for (let rgPath of androidRgPaths) {
    if (require("fs").existsSync(rgPath)) return { mode: "system", command: rgPath, args: [] };
}
```

## Installation on Anthroid

```bash
# Install ripgrep (required for Grep tool)
pkg install ripgrep

# Install this forked package
npm install -g /path/to/anthroid/claude-code
```

## Tool Compatibility

| Tool | Status | Notes |
|------|--------|-------|
| Bash | ✅ Works | Uses system bash |
| Read | ✅ Works | Pure Node.js fs |
| Write | ✅ Works | Pure Node.js fs |
| Edit | ✅ Works | Pure Node.js fs |
| Glob | ✅ Works | Pure JavaScript |
| Grep | ✅ Works | Requires `pkg install ripgrep` |

## Updating from Upstream

1. Download new version: `npm pack @anthropic-ai/claude-code`
2. Extract and beautify: `js-beautify cli.js -o cli.js`
3. Apply patches (see `anthroid-config.js` for locations)
4. Test on device

## Files

- `cli.js` - Main CLI (beautified, 17MB)
- `anthroid-config.js` - Documents patch locations
- `package.json` - Package metadata
- `vendor/ripgrep/` - Ripgrep binaries (arm64-linux only)
- `*.wasm` - Tree-sitter and resvg WASM modules
