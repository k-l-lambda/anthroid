#!/data/data/com.anthroid/files/usr/bin/bash
# set_renv - Set runtime environment variable in Claude wrapper
# Usage: set_renv <VAR_NAME> <VALUE>
# Example: set_renv HTTP_PROXY http://proxy:ppio@10.121.196.2:1080
# Example: set_renv SERPAPI_API_KEY your_api_key_here
#
# To remove a variable, use: set_renv <VAR_NAME> ""

if [ $# -lt 1 ]; then
    echo "Usage: set_renv <VAR_NAME> [VALUE]"
    echo "Example: set_renv HTTP_PROXY http://proxy:ppio@host:port"
    echo "         set_renv SERPAPI_API_KEY your_key"
    echo "         set_renv HTTP_PROXY \"\"  # Remove variable"
    exit 1
fi

VAR_NAME="$1"
VALUE="$2"
WRAPPER="$PREFIX/bin/claude"

if [ ! -f "$WRAPPER" ]; then
    echo "Error: Wrapper not found at $WRAPPER"
    exit 1
fi

# Create temp file
TEMP_FILE=$(mktemp)
trap "rm -f $TEMP_FILE" EXIT

if [ -z "$VALUE" ]; then
    # Remove the variable
    grep -v "^export $VAR_NAME=" "$WRAPPER" > "$TEMP_FILE"
    echo "Removed $VAR_NAME from wrapper"
else
    # Check if variable already exists
    if grep -q "^export $VAR_NAME=" "$WRAPPER"; then
        # Update existing variable
        sed "s|^export $VAR_NAME=.*|export $VAR_NAME=\"$VALUE\"|" "$WRAPPER" > "$TEMP_FILE"
        echo "Updated $VAR_NAME in wrapper"
    else
        # Add new variable before the exec line
        head -n -1 "$WRAPPER" > "$TEMP_FILE"
        echo "export $VAR_NAME=\"$VALUE\"" >> "$TEMP_FILE"
        tail -n 1 "$WRAPPER" >> "$TEMP_FILE"
        echo "Added $VAR_NAME to wrapper"
    fi
fi

# Replace original file
cp "$TEMP_FILE" "$WRAPPER"
chmod +x "$WRAPPER"

echo "Current wrapper:"
cat "$WRAPPER"
