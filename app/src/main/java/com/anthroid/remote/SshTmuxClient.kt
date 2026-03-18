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
     * Resize the tmux window. Pass columns > 0 to set width, or -1 to restore auto-size.
     */
    suspend fun resizeWindow(hostname: String, session: String, columns: Int) = withContext(Dispatchers.IO) {
        if (!TerminalCommandBridge.isAvailable()) return@withContext

        val cmd = if (columns < 0) {
            // Restore auto-size: -A adjusts to largest attached client
            Log.i(TAG, "resizeWindow: restoring auto-size for $hostname:$session")
            "ssh -o ConnectTimeout=5 $hostname 'tmux resize-window -t $session -A 2>/dev/null'"
        } else {
            if (columns < 20) return@withContext
            Log.i(TAG, "resizeWindow: host=$hostname session=$session columns=$columns")
            "ssh -o ConnectTimeout=5 $hostname 'tmux resize-window -t $session -x $columns 2>/dev/null'"
        }
        val result = TerminalCommandBridge.executeCommand(cmd, timeout = 10000)
        if (!result.success) {
            Log.w(TAG, "resize-window failed (non-fatal): ${result.output.take(100)}")
        }
    }

    /**
     * Capture tmux pane content from a remote session.
     * Returns last 500 lines of the pane.
     */
    suspend fun capturePaneContent(hostname: String, session: String): String = withContext(Dispatchers.IO) {
        if (!TerminalCommandBridge.isAvailable()) {
            throw IllegalStateException("Terminal bridge not available")
        }

        val result = TerminalCommandBridge.executeCommand(
            "ssh -o ConnectTimeout=5 $hostname 'tmux capture-pane -t $session -p -S -500 2>/dev/null'",
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
