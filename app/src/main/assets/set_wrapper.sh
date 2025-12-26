#!/data/data/com.anthroid/files/usr/bin/bash
# set_wrapper - Configure Claude CLI wrapper
# Usage: set_wrapper <base_url> <auth_token> <model>

if [ $# -lt 3 ]; then
    echo "Usage: set_wrapper <base_url> <auth_token> <model>"
    echo "Example: set_wrapper https://api.anthropic.com/ sk-ant-xxx claude-sonnet-4-5-20250929"
    exit 1
fi

BASE_URL="$1"
AUTH_TOKEN="$2"
MODEL="$3"

cat > $PREFIX/bin/claude << WRAPPER_EOF
#!/data/data/com.anthroid/files/usr/bin/bash
export HOME=/data/data/com.anthroid/files/home
export PATH=/data/data/com.anthroid/files/usr/bin
export PREFIX=/data/data/com.anthroid/files/usr
export LD_LIBRARY_PATH=/data/data/com.anthroid/files/usr/lib
export ANTHROPIC_BASE_URL="$BASE_URL"
export ANTHROPIC_AUTH_TOKEN="$AUTH_TOKEN"
export ANTHROPIC_MODEL="$MODEL"
exec node \$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js "\$@"
WRAPPER_EOF

chmod +x $PREFIX/bin/claude
echo "Claude wrapper configured successfully."
echo "  Base URL: $BASE_URL"
echo "  Model: $MODEL"
