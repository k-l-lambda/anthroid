/**
 * Anthroid Configuration for Claude Code
 *
 * This file documents the Android-specific patches applied to cli.js
 * The actual patches are inline in cli.js (search for "Anthroid:")
 *
 * To update cli.js with new upstream version:
 * 1. Download and beautify new cli.js
 * 2. Apply patches documented below
 * 3. Test on Android device
 */

// Android/Termux paths for shell detection
// Patched in cli.js function Gs5() around line 383015
export const ANDROID_SHELL_PATHS = [
    "/data/data/com.anthroid/files/usr/bin",
    "/data/data/com.termux/files/usr/bin",
];

// Android/Termux paths for ripgrep detection
// Patched in cli.js VuA function around line 15827
export const ANDROID_RIPGREP_PATHS = [
    "/data/data/com.anthroid/files/usr/bin/rg",
    "/data/data/com.termux/files/usr/bin/rg",
];

// Tool name constants (for reference)
// These are defined in cli.js and used for tool registration
export const TOOL_NAMES = {
    Bash: "Bash",      // Line 171632
    Edit: "Edit",      // Line 176836
    Read: "Read",      // Line 176868
    Glob: "Glob",      // Line 178414
    Grep: "Grep",      // Line 178436
    Write: "Write",    // Line 178438
};

// Custom Android tools to add (TODO)
export const ANDROID_TOOLS = {
    read_clipboard: {
        name: "read_clipboard",
        description: "Read text from the Android clipboard",
        inputSchema: {},
    },
    write_clipboard: {
        name: "write_clipboard",
        description: "Write text to the Android clipboard",
        inputSchema: {
            text: { type: "string", description: "Text to copy to clipboard" }
        },
    },
    // run_termux, read_terminal - require Android IPC, not implementable in CLI
};

/**
 * Patch locations in cli.js (for upstream updates):
 *
 * 1. Shell detection (Gs5 function):
 *    - Location: ~line 383015
 *    - Original: X = ["/bin", "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"]
 *    - Patched: Added ANDROID_SHELL_PATHS at beginning
 *
 * 2. Ripgrep detection (VuA function):
 *    - Location: ~line 15827
 *    - Added: Check ANDROID_RIPGREP_PATHS before vendor binary fallback
 */
