#!/usr/bin/env bash
# Test script for set_wrapper.sh
# Run in WSL: bash test_set_wrapper.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SET_WRAPPER="$SCRIPT_DIR/app/src/main/assets/set_wrapper.sh"
TESTDIR="$(mktemp -d)"
trap "rm -rf $TESTDIR" EXIT

pass() { echo "[PASS] $1"; }
fail() { echo "[FAIL] $1"; FAILED=1; }
FAILED=0

export PREFIX="$TESTDIR/usr"
export HOME="$TESTDIR/home"
mkdir -p "$PREFIX/bin"
mkdir -p "$HOME/openclaw-agent-local"

# ── Helpers ──────────────────────────────────────────────────────────────────

run_wrapper() {
    bash "$SET_WRAPPER" "$@" 2>&1
}

# ─────────────────────────────────────────────────────────────────────────────
# Test 1: Create wrapper from scratch (no existing wrapper, no set_renv)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test 1: Create wrapper from scratch ==="
rm -f "$PREFIX/bin/claude"
cat > "$HOME/openclaw-agent-local/config.json" <<'EOF'
{
  "provider": "anthropic",
  "model": "claude-sonnet-4-20250514",
  "timeoutMs": 300000,
  "sessionDir": ".sessions",
  "workspaceDir": "."
}
EOF

run_wrapper "https://api.anthropic.com/" "sk-ant-test123" "claude-opus-4-6"

# Check wrapper created
if [ -f "$PREFIX/bin/claude" ]; then
    pass "wrapper file created"
else
    fail "wrapper file NOT created"
fi

# Check wrapper is executable
if [ -x "$PREFIX/bin/claude" ]; then
    pass "wrapper is executable"
else
    fail "wrapper is NOT executable"
fi

# Check wrapper contains correct BASE_URL
if grep -q 'ANTHROPIC_BASE_URL="https://api.anthropic.com/"' "$PREFIX/bin/claude"; then
    pass "wrapper has correct BASE_URL"
else
    fail "wrapper missing correct BASE_URL"
    echo "  Wrapper content:"
    cat "$PREFIX/bin/claude"
fi

# Check wrapper contains correct AUTH_TOKEN
if grep -q 'ANTHROPIC_AUTH_TOKEN="sk-ant-test123"' "$PREFIX/bin/claude"; then
    pass "wrapper has correct AUTH_TOKEN"
else
    fail "wrapper missing correct AUTH_TOKEN"
fi

# Check wrapper contains correct MODEL
if grep -q 'ANTHROPIC_MODEL="claude-opus-4-6"' "$PREFIX/bin/claude"; then
    pass "wrapper has correct ANTHROPIC_MODEL"
else
    fail "wrapper missing correct ANTHROPIC_MODEL"
fi

# Check config.json model updated
CONFIG_MODEL=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$HOME/openclaw-agent-local/config.json','utf8')).model)")
if [ "$CONFIG_MODEL" = "claude-opus-4-6" ]; then
    pass "config.json model updated to: $CONFIG_MODEL"
else
    fail "config.json model NOT updated (got: $CONFIG_MODEL)"
fi

# Check config.json preserved other fields
CONFIG_PROVIDER=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$HOME/openclaw-agent-local/config.json','utf8')).provider)")
CONFIG_TIMEOUT=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$HOME/openclaw-agent-local/config.json','utf8')).timeoutMs)")
if [ "$CONFIG_PROVIDER" = "anthropic" ]; then
    pass "config.json provider preserved: $CONFIG_PROVIDER"
else
    fail "config.json provider changed unexpectedly (got: $CONFIG_PROVIDER)"
fi
if [ "$CONFIG_TIMEOUT" = "300000" ]; then
    pass "config.json timeoutMs preserved: $CONFIG_TIMEOUT"
else
    fail "config.json timeoutMs changed unexpectedly (got: $CONFIG_TIMEOUT)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Test 2: Update existing wrapper (no set_renv → sed fallback)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test 2: Update existing wrapper (sed fallback) ==="
run_wrapper "https://proxy.ppinfra.com/v1/" "sk-ant-newkey456" "pa/claude-opus-4-6"

if grep -q 'ANTHROPIC_BASE_URL="https://proxy.ppinfra.com/v1/"' "$PREFIX/bin/claude"; then
    pass "wrapper BASE_URL updated"
else
    fail "wrapper BASE_URL NOT updated"
fi
if grep -q 'ANTHROPIC_AUTH_TOKEN="sk-ant-newkey456"' "$PREFIX/bin/claude"; then
    pass "wrapper AUTH_TOKEN updated"
else
    fail "wrapper AUTH_TOKEN NOT updated"
fi
if grep -q 'ANTHROPIC_MODEL="pa/claude-opus-4-6"' "$PREFIX/bin/claude"; then
    pass "wrapper MODEL updated"
else
    fail "wrapper MODEL NOT updated"
fi

CONFIG_MODEL=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$HOME/openclaw-agent-local/config.json','utf8')).model)")
if [ "$CONFIG_MODEL" = "pa/claude-opus-4-6" ]; then
    pass "config.json model updated to: $CONFIG_MODEL"
else
    fail "config.json model NOT updated (got: $CONFIG_MODEL)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Test 3: Update existing wrapper with set_renv helper
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test 3: Update via set_renv helper ==="
# Create a minimal set_renv implementation for testing
cat > "$PREFIX/bin/set_renv" <<'RENV_EOF'
#!/usr/bin/env bash
# Minimal set_renv: sets export VAR="VALUE" line in $PREFIX/bin/claude
VAR_NAME="$1"
VAR_VALUE="$2"
WRAPPER="$PREFIX/bin/claude"
if grep -q "^export $VAR_NAME=" "$WRAPPER"; then
    sed -i "s|^export $VAR_NAME=.*|export $VAR_NAME=\"$VAR_VALUE\"|" "$WRAPPER"
else
    sed -i "/^exec /i export $VAR_NAME=\"$VAR_VALUE\"" "$WRAPPER"
fi
RENV_EOF
chmod +x "$PREFIX/bin/set_renv"

run_wrapper "https://api.anthropic.com/" "sk-ant-viasrenv789" "claude-sonnet-4-6"

if grep -q 'ANTHROPIC_AUTH_TOKEN="sk-ant-viasrenv789"' "$PREFIX/bin/claude"; then
    pass "wrapper AUTH_TOKEN updated via set_renv"
else
    fail "wrapper AUTH_TOKEN NOT updated via set_renv"
fi
CONFIG_MODEL=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$HOME/openclaw-agent-local/config.json','utf8')).model)")
if [ "$CONFIG_MODEL" = "claude-sonnet-4-6" ]; then
    pass "config.json model updated to: $CONFIG_MODEL"
else
    fail "config.json model NOT updated (got: $CONFIG_MODEL)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Test 4: config.json missing (graceful skip)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test 4: config.json missing (should skip gracefully) ==="
rm "$HOME/openclaw-agent-local/config.json"
OUTPUT=$(run_wrapper "https://api.anthropic.com/" "sk-ant-xxx" "claude-opus-4-6")
if echo "$OUTPUT" | grep -q "skipped"; then
    pass "gracefully skipped missing config.json"
else
    fail "did not report skip for missing config.json"
    echo "  Output: $OUTPUT"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo ""
if [ "$FAILED" -eq 0 ]; then
    echo "All tests PASSED"
else
    echo "Some tests FAILED"
    exit 1
fi
