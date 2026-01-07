#!/data/data/com.anthroid/files/usr/bin/bash
# set_wrapper - Update Claude API configuration in wrapper
# Usage: set_wrapper <base_url> <auth_token> <model>
# This script updates ANTHROPIC_BASE_URL, ANTHROPIC_AUTH_TOKEN, ANTHROPIC_MODEL,
# and ANTHROPIC_SMALL_FAST_MODEL without overwriting other environment variables.

if [ $# -lt 3 ]; then
    echo "Usage: set_wrapper <base_url> <auth_token> <model>"
    echo "Example: set_wrapper https://api.anthropic.com/ sk-ant-xxx claude-sonnet-4-5-20250929"
    exit 1
fi

BASE_URL="$1"
AUTH_TOKEN="$2"
MODEL="$3"
WRAPPER="$PREFIX/bin/claude"

# Check if wrapper exists
if [ ! -f "$WRAPPER" ]; then
    echo "Wrapper not found, creating new one..."
    cat > "$WRAPPER" << WRAPPER_EOF
#!/data/data/com.anthroid/files/usr/bin/bash
export HOME=/data/data/com.anthroid/files/home
export PATH=/data/data/com.anthroid/files/usr/bin
export PREFIX=/data/data/com.anthroid/files/usr
export LD_LIBRARY_PATH=/data/data/com.anthroid/files/usr/lib
export ANTHROPIC_BASE_URL="$BASE_URL"
export ANTHROPIC_AUTH_TOKEN="$AUTH_TOKEN"
export ANTHROPIC_MODEL="$MODEL"
export ANTHROPIC_SMALL_FAST_MODEL="$MODEL"
exec node \$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js "\$@"
WRAPPER_EOF
else
    # Update existing wrapper using set_renv
    RENV="$PREFIX/bin/set_renv"
    if [ -f "$RENV" ]; then
        "$RENV" ANTHROPIC_BASE_URL "$BASE_URL"
        "$RENV" ANTHROPIC_AUTH_TOKEN "$AUTH_TOKEN"
        "$RENV" ANTHROPIC_MODEL "$MODEL"
        "$RENV" ANTHROPIC_SMALL_FAST_MODEL "$MODEL"
    else
        # Fallback: use sed to update in place
        TEMP_FILE=$(mktemp)
        trap "rm -f $TEMP_FILE" EXIT

        cp "$WRAPPER" "$TEMP_FILE"

        # Update or add each variable
        for VAR_PAIR in "ANTHROPIC_BASE_URL=$BASE_URL" "ANTHROPIC_AUTH_TOKEN=$AUTH_TOKEN" "ANTHROPIC_MODEL=$MODEL" "ANTHROPIC_SMALL_FAST_MODEL=$MODEL"; do
            VAR_NAME="${VAR_PAIR%%=*}"
            VAR_VALUE="${VAR_PAIR#*=}"

            if grep -q "^export $VAR_NAME=" "$TEMP_FILE"; then
                sed -i "s|^export $VAR_NAME=.*|export $VAR_NAME=\"$VAR_VALUE\"|" "$TEMP_FILE"
            else
                # Add before exec line
                sed -i "/^exec /i export $VAR_NAME=\"$VAR_VALUE\"" "$TEMP_FILE"
            fi
        done

        cp "$TEMP_FILE" "$WRAPPER"
    fi
fi

chmod +x "$WRAPPER"
echo "Claude wrapper configured successfully."
echo "  Base URL: $BASE_URL"
echo "  Model: $MODEL"
echo ""
echo "Current wrapper:"
cat "$WRAPPER"
