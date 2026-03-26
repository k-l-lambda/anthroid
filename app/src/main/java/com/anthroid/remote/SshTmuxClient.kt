package com.anthroid.remote

import android.util.Base64
import android.util.Log
import com.anthroid.claude.TerminalCommandBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SSH + tmux interaction helper for remote agent sessions.
 *
 * Uses TerminalCommandBridge to execute SSH commands in Termux.
 * Requires SSH key auth configured in ~/.ssh/ on the device.
 */
class SshTmuxClient {
    companion object {
        private const val TAG = "SshTmuxClient"
        // Conservative patterns to prevent shell injection
        private val SAFE_HOSTNAME = Regex("[A-Za-z0-9._@:-]+")
        private val SAFE_SESSION = Regex("[A-Za-z0-9._:-]+")

        fun isSafeHostname(hostname: String): Boolean {
            val safe = hostname.isNotEmpty() && hostname.matches(SAFE_HOSTNAME)
            if (!safe) Log.w(TAG, "Rejected unsafe hostname: $hostname")
            return safe
        }
        fun isSafeSession(session: String): Boolean {
            val safe = session.isNotEmpty() && session.matches(SAFE_SESSION)
            if (!safe) Log.w(TAG, "Rejected unsafe session: $session")
            return safe
        }
    }

    /**
     * List tmux sessions on a remote host.
     */
    suspend fun listSessions(hostname: String): List<RemoteSessionInfo> = withContext(Dispatchers.IO) {
        if (!TerminalCommandBridge.isAvailable()) {
            throw IllegalStateException("Terminal bridge not available")
        }

        val result = TerminalCommandBridge.executeCommand(
            "ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new $hostname " +
                "'tmux list-sessions -F \"#{session_name}|#{session_windows}|#{session_activity}\" 2>/dev/null'",
            timeout = 15000
        )

        if (!result.success) {
            Log.w(TAG, "tmux list-sessions failed: ${result.output}")
            throw IllegalStateException("SSH failed: ${result.output.take(200)}")
        }

        parseTmuxListOutput(result.output, hostname)
    }

    /**
     * Create a temporary tmux window linked to the target session, resized to the given columns.
     * Returns the temporary window name (e.g. "starry-anthroid") or null on failure.
     * This avoids resizing the original window, which affects other viewers.
     */
    suspend fun createTempWindow(hostname: String, session: String, columns: Int): String? = withContext(Dispatchers.IO) {
        if (!TerminalCommandBridge.isAvailable()) return@withContext null
        if (!isSafeHostname(hostname) || !isSafeSession(session)) return@withContext null
        if (columns < 20) return@withContext null

        val tempWindow = "$session-anthroid"
        Log.i(TAG, "createTempWindow: $hostname:$tempWindow (${columns}col)")
        // Link a new window to the session's current pane, resize it, then select original window back
        val cmd = "ssh -o ConnectTimeout=5 $hostname '" +
            "tmux new-window -t $session -n anthroid 2>/dev/null; " +
            "tmux resize-window -t $session:anthroid -x $columns 2>/dev/null; " +
            "tmux select-window -t $session:0 2>/dev/null" +
            "'"
        val result = TerminalCommandBridge.executeCommand(cmd, timeout = 10000)
        if (!result.success) {
            Log.w(TAG, "createTempWindow failed: ${result.output.take(100)}")
            return@withContext null
        }
        tempWindow
    }

    /**
     * Kill the temporary anthroid window. Best-effort — failure doesn't affect the original session.
     */
    suspend fun killTempWindow(hostname: String, session: String) = withContext(Dispatchers.IO) {
        if (!TerminalCommandBridge.isAvailable()) return@withContext
        if (!isSafeHostname(hostname) || !isSafeSession(session)) return@withContext

        Log.i(TAG, "killTempWindow: $hostname:$session:anthroid")
        TerminalCommandBridge.executeCommand(
            "ssh -o ConnectTimeout=5 $hostname 'tmux kill-window -t $session:anthroid 2>/dev/null'",
            timeout = 10000
        )
    }

    /**
     * Capture tmux pane content from a remote session.
     * Returns last 500 lines of the pane.
     */
    /**
     * Capture tmux pane content. If useTempWindow is true, captures from the
     * anthroid temp window (which has the correct width for mobile display).
     */
    suspend fun capturePaneContent(hostname: String, session: String, useTempWindow: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!TerminalCommandBridge.isAvailable()) {
            throw IllegalStateException("Terminal bridge not available")
        }
        if (!isSafeHostname(hostname) || !isSafeSession(session)) {
            throw IllegalArgumentException("Unsafe hostname or session name")
        }

        val target = if (useTempWindow) "$session:anthroid" else session
        val result = TerminalCommandBridge.executeCommand(
            "ssh -o ConnectTimeout=5 $hostname 'tmux capture-pane -t $target -p -S -500 2>/dev/null'",
            timeout = 15000
        )

        if (!result.success) {
            Log.w(TAG, "capture-pane failed: ${result.output}")
            throw IllegalStateException("capture-pane failed: ${result.output.take(200)}")
        }

        result.output
    }

    /**
     * Send keystrokes to a tmux pane on the remote host.
     * Sequence:
     *   1. Send literal "0" — dismisses Claude Code feedback prompt if showing
     *      (feedback expects 0-3 as input; if no feedback, "0" is typed into input)
     *   2. Sleep 0.3s to let feedback dismiss
     *   3. End + 100 BSpace — move to end and clear any input (removes the "0" if typed)
     *   4. Send actual text via -l (literal) flag using base64 to avoid all quoting issues
     *   5. Sleep 1s to let the TUI process the text before Enter
     *   6. Enter to submit
     */
    suspend fun sendKeys(hostname: String, session: String, text: String) = withContext(Dispatchers.IO) {
        if (!TerminalCommandBridge.isAvailable()) {
            throw IllegalStateException("Terminal bridge not available")
        }
        if (!isSafeHostname(hostname) || !isSafeSession(session)) {
            throw IllegalArgumentException("Unsafe hostname or session name")
        }

        Log.i(TAG, "sendKeys: host=$hostname session=$session text='${text.take(30)}'")

        // Base64-encode text to avoid all shell quoting issues
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val bspaces = List(100) { "BSpace" }.joinToString(" ")
        val cmd = "ssh -o ConnectTimeout=5 $hostname " +
            "'tmux send-keys -t $session -l 0 ; " +
            "sleep 0.3 ; " +
            "tmux send-keys -t $session End $bspaces ; " +
            "tmux send-keys -t $session -l \"\$(echo $b64 | base64 -d)\" ; " +
            "sleep 1 ; " +
            "tmux send-keys -t $session Enter'"
        Log.d(TAG, "sendKeys cmd: $cmd")
        val result = TerminalCommandBridge.executeCommand(cmd, timeout = 20000)

        if (!result.success) {
            Log.w(TAG, "send-keys failed: ${result.output}")
            throw IllegalStateException("send-keys failed: ${result.output.take(200)}")
        }
        Log.i(TAG, "sendKeys: success")
    }

    private fun parseTmuxListOutput(output: String, hostname: String): List<RemoteSessionInfo> {
        return output.lines()
            .filter { it.contains("|") }
            .mapNotNull { line ->
                val parts = line.trim().split("|")
                if (parts.size < 3) return@mapNotNull null
                val name = parts[0].trim()
                val windows = parts[1].trim()
                val activityEpoch = parts[2].trim().toLongOrNull() ?: 0L
                RemoteSessionInfo(
                    sessionKey = name,
                    displayName = "$name ($windows windows)",
                    lastActivity = activityEpoch * 1000, // tmux gives seconds, we use ms
                    status = "active",
                    source = RemoteSessionInfo.Source.SSH_TMUX,
                    hostname = hostname,
                )
            }
    }
}
