package com.anthroid.claude

import android.util.Log
import com.anthroid.app.TermuxService
import com.anthroid.shared.shell.ShellUtils
import com.anthroid.shared.termux.shell.command.runner.terminal.TermuxSession
import com.anthroid.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Singleton bridge for executing commands in Termux terminal from Claude.
 * Must be registered when TermuxActivity connects to TermuxService.
 */
object TerminalCommandBridge {
    private const val TAG = "TerminalCommandBridge"
    private const val PREFIX = "/data/data/com.anthroid/files/usr"

    private var termuxService: TermuxService? = null
    private var currentSessionGetter: (() -> TerminalSession?)? = null

    /**
     * Register the bridge with TermuxService.
     * Called from TermuxActivity.onServiceConnected()
     */
    fun register(service: TermuxService, getCurrentSession: () -> TerminalSession?) {
        termuxService = service
        currentSessionGetter = getCurrentSession
        Log.i(TAG, "Bridge registered with TermuxService")
    }

    /**
     * Unregister the bridge.
     * Called from TermuxActivity.onServiceDisconnected()
     */
    fun unregister() {
        termuxService = null
        currentSessionGetter = null
        Log.i(TAG, "Bridge unregistered")
    }

    /**
     * Check if the bridge is available (TermuxService connected).
     */
    fun isAvailable(): Boolean = termuxService != null

    /**
     * Execute a command in the terminal and capture output.
     *
     * @param command The command to execute
     * @param sessionId Optional session ID (default: current session)
     * @param timeout Timeout in milliseconds (default: 30000)
     * @return CommandResult with output and session ID
     */
    suspend fun executeCommand(
        command: String,
        sessionId: String? = null,
        timeout: Long = 30000
    ): CommandResult = withContext(Dispatchers.IO) {
        val service = termuxService
            ?: return@withContext CommandResult.error("Termux service not available")

        // Get target session
        val targetSessionId: String
        val session: TerminalSession? = if (sessionId != null) {
            // Find session by name
            val termuxSession = findSessionByName(service, sessionId)
            targetSessionId = sessionId
            termuxSession?.terminalSession
        } else {
            // Use current session
            val currentSession = currentSessionGetter?.invoke()
            targetSessionId = currentSession?.mSessionName ?: getDefaultSessionId(service)
            currentSession
        }

        if (session == null || !session.isRunning) {
            return@withContext CommandResult.error(
                "No active terminal session",
                sessionId = targetSessionId
            )
        }

        val emulator = session.emulator
        if (emulator == null) {
            Log.e(TAG, "Terminal emulator not initialized for session '$targetSessionId'")
            return@withContext CommandResult.error(
                "Terminal emulator not initialized. Please open Termux first.",
                sessionId = targetSessionId
            )
        }

        Log.i(TAG, "Executing command in session '$targetSessionId': $command")

        // Generate unique marker for output detection
        val marker = "===ANTHROID_END_${UUID.randomUUID().toString().take(8)}==="
        Log.d(TAG, "Using marker: $marker")

        // Get current transcript length before command
        val transcriptBefore = ShellUtils.getTerminalSessionTranscriptText(session, true, false)
        val startPos = transcriptBefore?.length ?: 0
        Log.d(TAG, "Transcript before length: $startPos")

        // Execute command with end marker
        // Use write() to send command directly to terminal
        val fullCommand = "$command; echo '$marker'\n"
        Log.d(TAG, "Sending command via write()")
        session.write(fullCommand)

        // Wait for marker to appear in output
        val startTime = System.currentTimeMillis()
        var output: String? = null
        var lastLogTime = 0L

        while (System.currentTimeMillis() - startTime < timeout) {
            delay(100) // Check every 100ms

            val transcriptNow = ShellUtils.getTerminalSessionTranscriptText(session, true, false) ?: ""
            val newContent = if (transcriptNow.length > startPos) {
                transcriptNow.substring(startPos)
            } else ""

            // Log progress every 2 seconds
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 2000) {
                Log.d(TAG, "Waiting... newContent len=${newContent.length}, hasMarker=${newContent.contains(marker)}")
                if (newContent.isNotEmpty() && newContent.length < 500) {
                    Log.d(TAG, "Content: $newContent")
                }
                lastLogTime = now
            }

            if (newContent.contains(marker)) {
                // Extract output before marker
                output = newContent.substringBefore(marker).trim()

                // Remove the echoed command from beginning
                // The command line typically includes the prompt + command + newline
                val cmdLine = command.trim()
                val lines = output.lines()
                if (lines.isNotEmpty()) {
                    // Find where the actual output starts (after command echo)
                    val outputStartIndex = lines.indexOfFirst { line ->
                        !line.contains(cmdLine) && !line.contains("echo '$marker'")
                    }
                    if (outputStartIndex > 0) {
                        output = lines.drop(outputStartIndex).joinToString("\n").trim()
                    } else if (outputStartIndex == -1) {
                        // All lines contain command - likely no output
                        output = ""
                    }
                }
                break
            }
        }

        if (output == null) {
            Log.w(TAG, "Command timed out after ${timeout}ms")
            CommandResult(
                success = false,
                output = "Command timed out after ${timeout}ms",
                exitCode = -1,
                sessionId = targetSessionId
            )
        } else {
            Log.i(TAG, "Command completed, output length: ${output.length}")
            CommandResult(
                success = true,
                output = output,
                exitCode = 0,
                sessionId = targetSessionId
            )
        }
    }


    /**
     * Read the full terminal session transcript.
     *
     * @param sessionId Optional session ID (default: current session)
     * @param maxLines Maximum number of lines to return (0 = unlimited)
     * @return The terminal transcript text
     */
    suspend fun readTerminalSession(
        sessionId: String? = null,
        maxLines: Int = 0
    ): CommandResult = withContext(Dispatchers.IO) {
        val service = termuxService
            ?: return@withContext CommandResult.error("Termux service not available")

        // Get target session
        val targetSessionId: String
        val session: TerminalSession? = if (sessionId != null) {
            val termuxSession = findSessionByName(service, sessionId)
            targetSessionId = sessionId
            termuxSession?.terminalSession
        } else {
            val currentSession = currentSessionGetter?.invoke()
            targetSessionId = currentSession?.mSessionName ?: getDefaultSessionId(service)
            currentSession
        }

        if (session == null) {
            Log.e(TAG, "No terminal session found. Service sessions: ${service.termuxSessions.size}")
            return@withContext CommandResult.error(
                "No terminal session found",
                sessionId = targetSessionId
            )
        }

        Log.i(TAG, "Reading terminal session '$targetSessionId', isRunning=${session.isRunning}")

        // Check emulator state
        val emulator = session.emulator
        if (emulator == null) {
            Log.e(TAG, "Terminal emulator is null for session '$targetSessionId'")
            return@withContext CommandResult.error(
                "Terminal emulator not initialized",
                sessionId = targetSessionId
            )
        }

        Log.d(TAG, "Emulator rows=${emulator.mRows}, cols=${emulator.mColumns}")

        // Get full transcript
        val transcript = ShellUtils.getTerminalSessionTranscriptText(session, true, false)
        if (transcript == null) {
            Log.e(TAG, "getTerminalSessionTranscriptText returned null")
            return@withContext CommandResult.error(
                "Failed to read terminal transcript",
                sessionId = targetSessionId
            )
        }

        // Apply line limit if specified
        val output = if (maxLines > 0) {
            val lines = transcript.lines()
            if (lines.size > maxLines) {
                lines.takeLast(maxLines).joinToString("\n")
            } else {
                transcript
            }
        } else {
            transcript
        }

        Log.i(TAG, "Read ${output.lines().size} lines from terminal")

        CommandResult(
            success = true,
            output = output,
            exitCode = 0,
            sessionId = targetSessionId
        )
    }

    /**
     * List available terminal sessions.
     */
    fun listSessions(): List<SessionInfo> {
        val service = termuxService ?: return emptyList()
        val currentSession = currentSessionGetter?.invoke()

        return service.termuxSessions.mapIndexed { index, ts ->
            val terminalSession = ts.terminalSession
            SessionInfo(
                id = terminalSession?.mSessionName ?: "session-$index",
                name = terminalSession?.title ?: "Terminal $index",
                isRunning = terminalSession?.isRunning == true,
                isCurrent = terminalSession == currentSession
            )
        }
    }

    private fun findSessionByName(service: TermuxService, name: String): TermuxSession? {
        return service.termuxSessions.find { ts ->
            ts.terminalSession?.mSessionName == name
        }
    }

    private fun getDefaultSessionId(service: TermuxService): String {
        val sessions = service.termuxSessions
        return if (sessions.isNotEmpty()) {
            sessions[0].terminalSession?.mSessionName ?: "session-0"
        } else {
            "none"
        }
    }

    /**
     * Result of command execution.
     */
    data class CommandResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int,
        val sessionId: String = ""
    ) {
        companion object {
            fun error(message: String, sessionId: String = "") =
                CommandResult(false, message, -1, sessionId)
        }

        /**
         * Format result for Claude tool response.
         */
        fun toToolResult(): String = buildString {
            append("Session: $sessionId\n")
            if (success) {
                append("Output:\n$output")
            } else {
                append("Error: $output")
            }
        }
    }

    /**
     * Information about a terminal session.
     */
    data class SessionInfo(
        val id: String,
        val name: String,
        val isRunning: Boolean,
        val isCurrent: Boolean
    )
}
