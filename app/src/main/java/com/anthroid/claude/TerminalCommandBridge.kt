package com.anthroid.claude

import android.util.Log
import com.anthroid.app.TermuxService
import com.anthroid.shared.shell.ShellUtils
import com.anthroid.shared.termux.shell.command.runner.terminal.TermuxSession
import com.anthroid.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Mutex to serialize terminal command execution — only one command at a time
    private val commandMutex = Mutex()

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

        // Serialize commands — only one at a time on the shared terminal session
        commandMutex.withLock {
            executeCommandLocked(service, command, sessionId, timeout)
        }
    }

    private suspend fun executeCommandLocked(
        service: TermuxService,
        command: String,
        sessionId: String?,
        timeout: Long
    ): CommandResult {

        // Get target session (with retry for sessions still being created)
        var targetSessionId: String = ""
        var session: TerminalSession? = null

        for (attempt in 1..20) { // Wait up to 2 seconds for session
            if (sessionId != null) {
                val termuxSession = findSessionByName(service, sessionId)
                targetSessionId = sessionId
                session = termuxSession?.terminalSession
            } else {
                val currentSession = currentSessionGetter?.invoke()
                targetSessionId = currentSession?.mSessionName ?: getDefaultSessionId(service)
                session = currentSession
            }

            if (session != null && session.isRunning) break

            if (attempt == 1) {
                Log.d(TAG, "No active session yet (sessions=${service.termuxSessions.size}), waiting...")
            }
            delay(100)
        }

        if (session == null || !session.isRunning) {
            return CommandResult.error(
                "No active terminal session (sessions=${service.termuxSessions.size})",
                sessionId = targetSessionId
            )
        }

        // Wait for emulator to initialize (may take a moment after session creation)
        var emulator = session.emulator
        if (emulator == null) {
            Log.d(TAG, "Emulator null for session '$targetSessionId', waiting for initialization...")
            for (i in 1..30) { // Wait up to 3 seconds
                delay(100)
                emulator = session.emulator
                if (emulator != null) {
                    Log.d(TAG, "Emulator initialized after ${i * 100}ms")
                    break
                }
            }
        }
        if (emulator == null) {
            Log.e(TAG, "Terminal emulator not initialized for session '$targetSessionId' after waiting")
            return CommandResult.error(
                "Terminal emulator not initialized. Please open Termux first.",
                sessionId = targetSessionId
            )
        }

        Log.i(TAG, "Executing command in session '$targetSessionId': $command")

        // Generate unique marker for output detection
        val marker = "===ANTHROID_END_${UUID.randomUUID().toString().take(8)}==="
        Log.d(TAG, "Using marker: $marker")

        // Execute command with end marker
        // Use write() to send command directly to terminal
        val fullCommand = "$command; echo '$marker'\n"
        Log.d(TAG, "Sending command via write()")
        session.write(fullCommand)

        // Wait for marker to appear in transcript
        // Note: transcript is a circular buffer — we search the FULL transcript for the
        // unique marker rather than tracking by position, since old content gets pushed out.
        val startTime = System.currentTimeMillis()
        var output: String? = null
        var lastLogTime = 0L
        val markerLineRegex = Regex("^\\s*" + Regex.escape(marker) + "\\s*$", RegexOption.MULTILINE)

        while (System.currentTimeMillis() - startTime < timeout) {
            delay(100) // Check every 100ms

            // Use non-joined transcript (linesJoined=false) so each terminal row is a
            // separate line. The joined version merges full-width rows, which can fuse
            // the marker with the preceding SSH output line.
            val transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, false) ?: ""

            // Log progress every 2 seconds
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 2000) {
                Log.d(TAG, "Waiting... transcript len=${transcript.length}, hasMarker=${transcript.contains(marker)}")
                lastLogTime = now
            }

            if (markerLineRegex.containsMatchIn(transcript)) {
                // Find marker position and extract output between command echo and marker
                val lines = transcript.lines()
                val markerLineIndex = lines.indexOfLast { it.trim() == marker }

                if (markerLineIndex >= 0) {
                    // Search backwards from marker to find the command echo line
                    val echoPattern = "echo '$marker'"
                    var cmdLineIndex = markerLineIndex - 1
                    while (cmdLineIndex >= 0) {
                        if (lines[cmdLineIndex].contains(echoPattern)) break
                        cmdLineIndex--
                    }

                    if (cmdLineIndex >= 0) {
                        // Output is between command echo line and marker line
                        val outputLines = lines.subList(cmdLineIndex + 1, markerLineIndex)
                        output = outputLines.joinToString("\n").trim()
                    } else {
                        // Fallback: couldn't find command echo, take some lines before marker
                        output = ""
                    }
                }
                break
            }
        }

        return if (output == null) {
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
