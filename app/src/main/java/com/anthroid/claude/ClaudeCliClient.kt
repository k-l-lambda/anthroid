package com.anthroid.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.*

/**
 * Client for communicating with Claude CLI process.
 * Wraps the claude command-line tool and provides a Flow-based API.
 */
class ClaudeCliClient(private val context: Context) {

    companion object {
        private const val TAG = "ClaudeCliClient"
        private const val CLAUDE_CMD = "claude"
        private const val PREFIX_PATH = "/data/data/com.anthroid/files/usr"
    }

    private var process: Process? = null
    private var outputWriter: BufferedWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Persistent session state
    private var persistentProcess: Process? = null
    private var persistentWriter: BufferedWriter? = null
    private var persistentReader: InputStreamReader? = null
    private var sessionActive = false
    private var conversationId: String? = null

    // Tool input accumulator for streaming tool_use events
    private var pendingToolId: String? = null
    private var pendingToolName: String? = null
    private val pendingToolInput = StringBuilder()

    /**
     * Check if Claude CLI is installed in the Termux environment.
     */
    fun isClaudeInstalled(): Boolean {
        val claudePath = File("$PREFIX_PATH/bin/$CLAUDE_CMD")
        return claudePath.exists() && claudePath.canExecute()
    }

    /**
     * Get the path to the Claude executable.
     */
    fun getClaudePath(): String {
        return "$PREFIX_PATH/bin/$CLAUDE_CMD"
    }

    /**
     * Send a message using pipe mode (--print) and stream the response.
     * This is simpler than interactive mode - just stdin/stdout.
     *
     * @param message The message to send to Claude
     * @return Flow of ClaudeEvent objects with streaming response
     */
    fun chatPipe(message: String): Flow<ClaudeEvent> = channelFlow {
        val env = buildEnvironment()
        val claudePath = getClaudePath()

        Log.i(TAG, "Starting Claude pipe mode for message: " + message.take(50) + "...")

        try {
            val processBuilder = ProcessBuilder(claudePath, "--print")
                .directory(File("$PREFIX_PATH/.."))
                .redirectErrorStream(false)

            processBuilder.environment().putAll(env)

            val proc = processBuilder.start()
            process = proc

            // Send message to stdin
            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            writer.write(message)
            writer.close()

            send(ClaudeEvent.MessageStart("pipe-" + System.currentTimeMillis()))

            // Read stderr in background (for warnings)
            val stderrJob = launch {
                val reader = BufferedReader(InputStreamReader(proc.errorStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // Skip linker warnings
                        if (line!!.contains("unused DT entry")) continue
                        Log.w(TAG, "Claude stderr: $line")
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e(TAG, "Error reading stderr", e)
                    }
                } finally {
                    reader.close()
                }
            }

            // Read stdout character by character for true streaming
            val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
            val buffer = CharArray(64)
            var charsRead: Int

            try {
                while (stdoutReader.read(buffer).also { charsRead = it } != -1) {
                    val chunk = String(buffer, 0, charsRead)
                    send(ClaudeEvent.TextDelta(chunk))
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Error reading stdout", e)
                }
            } finally {
                stdoutReader.close()
            }

            // Wait for process to exit
            val exitCode = proc.waitFor()
            Log.i(TAG, "Claude pipe process exited with code: $exitCode")

            stderrJob.cancelAndJoin()

            send(ClaudeEvent.MessageEnd)
            send(ClaudeEvent.SessionEnded(exitCode))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to run Claude pipe mode", e)
            send(ClaudeEvent.Error("Failed to run Claude: " + e.message))
        } finally {
            process = null
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a message using streaming JSON mode for real-time response.
     * Uses --output-format stream-json for true streaming.
     *
     * @param message The message to send to Claude
     * @return Flow of ClaudeEvent objects with real-time streaming
     */
    fun chatStreaming(message: String): Flow<ClaudeEvent> = channelFlow {
        val env = buildEnvironment()
        val claudePath = getClaudePath()

        Log.i(TAG, "Starting Claude streaming mode for message: " + message.take(50) + "...")

        try {
            val cmdArgs = mutableListOf(
                claudePath,
                "--output-format", "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--print",
                "--dangerously-skip-permissions",
                // Note: CLI's built-in Bash tool is enabled for file operations
            )
            // Add conversation flag to resume previous session (reduces latency)
            conversationId?.let {
                cmdArgs.add("--resume")
                cmdArgs.add(it)
            }
            // Add system prompt with Android tool descriptions
            cmdArgs.add("--append-system-prompt")
            cmdArgs.add(getAndroidToolsPrompt())
            cmdArgs.add(message)

            val processBuilder = ProcessBuilder(cmdArgs)
                .directory(File("$PREFIX_PATH/.."))
                .redirectErrorStream(false)

            processBuilder.environment().putAll(env)

            val proc = processBuilder.start()
            process = proc
            // Close stdin so Claude starts processing (--print mode requirement)
            proc.outputStream.close()
            Log.i(TAG, "Stdin closed, Claude processing")

            // Read stderr in background (for warnings)
            val stderrJob = launch {
                val reader = BufferedReader(InputStreamReader(proc.errorStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.contains("unused DT entry")) continue
                        Log.w(TAG, "Claude stderr: $line")
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e(TAG, "Error reading stderr", e)
                    }
                } finally {
                    reader.close()
                }
            }

            // Read stdout character by character (unbuffered for real-time streaming)
            val stdoutReader = InputStreamReader(proc.inputStream)
            try {
                val lineBuilder = StringBuilder()
                var charCode: Int
                while (stdoutReader.read().also { charCode = it } != -1) {
                    if (charCode == '\n'.code) {
                        val line = lineBuilder.toString()
                        lineBuilder.clear()
                        if (line.isNotBlank()) {
                            Log.d(TAG, "Stream event: " + line.take(100))
                            parseStreamEvent(line)?.let { event ->
                                // Capture conversation ID from system event for session persistence
                                if (event is ClaudeEvent.MessageStart && event.messageId != "unknown") {
                                    // Parse session_id from system event for future reuse
                                    try {
                                        val json = JSONObject(line)
                                        if (json.optString("type") == "system") {
                                            val sessionId = json.optString("session_id", "")
                                            if (sessionId.isNotEmpty()) {
                                                conversationId = sessionId
                                                Log.i(TAG, "Captured session ID: $sessionId")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Ignore parsing errors
                                    }
                                }
                                send(event)
                            }
                        }
                    } else {
                        lineBuilder.append(charCode.toChar())
                    }
                }
                // Handle any remaining content
                if (lineBuilder.isNotEmpty()) {
                    val line = lineBuilder.toString()
                    if (line.isNotBlank()) {
                        parseStreamEvent(line)?.let { event ->
                            send(event)
                        }
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Error reading stdout", e)
                }
            } finally {
                stdoutReader.close()
            }

            val exitCode = proc.waitFor()
            Log.i(TAG, "Claude streaming process exited with code: $exitCode")

            stderrJob.cancelAndJoin()

            send(ClaudeEvent.MessageEnd)
            send(ClaudeEvent.SessionEnded(exitCode))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to run Claude streaming mode", e)
            send(ClaudeEvent.Error("Failed to run Claude: " + e.message))
        } finally {
            process = null
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * Parse streaming JSON event from Claude CLI.
     */
    private fun parseStreamEvent(line: String): ClaudeEvent? {
        return try {
            val json = JSONObject(line)
            val type = json.optString("type", "")

            when (type) {
                "system" -> {
                    // Init event with session info
                    val sessionId = json.optString("session_id", "unknown")
                    ClaudeEvent.MessageStart(sessionId)
                }
                "stream_event" -> {
                    // Real-time streaming event with nested event object
                    val event = json.optJSONObject("event") ?: return null
                    val eventType = event.optString("type", "")
                    Log.d(TAG, "stream_event type: $eventType")
                    when (eventType) {
                        "content_block_delta" -> {
                            val delta = event.optJSONObject("delta")
                            val deltaType = delta?.optString("type", "")
                            Log.d(TAG, "delta type: $deltaType")
                            when (deltaType) {
                                "text_delta" -> {
                                    val text = delta?.optString("text", "") ?: ""
                                    Log.d(TAG, "text_delta text: '$text'")
                                    if (text.isNotEmpty()) ClaudeEvent.TextDelta(text) else null
                                }
                                "input_json_delta" -> {
                                    // Accumulate tool input JSON chunks
                                    val partialJson = delta?.optString("partial_json", "") ?: ""
                                    if (partialJson.isNotEmpty()) {
                                        pendingToolInput.append(partialJson)
                                        Log.d(TAG, "Accumulated tool input: ${pendingToolInput.length} chars")
                                    }
                                    null  // Don't emit event yet, wait for content_block_stop
                                }
                                else -> null
                            }
                        }
                        "message_start" -> {
                            val msgObj = event.optJSONObject("message")
                            ClaudeEvent.MessageStart(msgObj?.optString("id", "unknown") ?: "unknown")
                        }
                        "message_stop" -> ClaudeEvent.MessageEnd
                        "content_block_start" -> {
                            val contentBlock = event.optJSONObject("content_block")
                            val blockType = contentBlock?.optString("type", "")
                            if (blockType == "tool_use") {
                                // Save tool info, input will come in input_json_delta events
                                pendingToolId = contentBlock.optString("id", "")
                                pendingToolName = contentBlock.optString("name", "")
                                pendingToolInput.clear()
                                Log.i(TAG, "Tool use started: $pendingToolName (id: $pendingToolId)")
                                null  // Don't emit yet, wait for input and content_block_stop
                            } else if (blockType == "text") {
                                null  // Text blocks are handled via text_delta
                            } else null
                        }
                        "content_block_stop" -> {
                            // Emit tool use event now that we have complete input
                            if (pendingToolId != null && pendingToolName != null) {
                                val toolId = pendingToolId!!
                                val toolName = pendingToolName!!
                                val toolInput = if (pendingToolInput.isNotEmpty()) pendingToolInput.toString() else "{}"
                                Log.i(TAG, "Tool use complete: $toolName with input: ${toolInput.take(100)}")
                                pendingToolId = null
                                pendingToolName = null
                                pendingToolInput.clear()
                                ClaudeEvent.ToolUse(toolId, toolName, toolInput)
                            } else null
                        }
                        else -> null
                    }
                }
                "assistant" -> {
                    // Complete message (skip if we're getting deltas)
                    null
                }
                "result" -> {
                    // Final result
                    val isError = json.optBoolean("is_error", false)
                    if (isError) {
                        val result = json.optString("result", "Unknown error")
                        ClaudeEvent.Error(result)
                    } else {
                        null
                    }
                }
                "error" -> {
                    val error = json.optJSONObject("error")
                    ClaudeEvent.Error(error?.optString("message", "Unknown error") ?: "Unknown error")
                }
                else -> {
                    Log.d(TAG, "Unknown stream event type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stream event: $line", e)
            null
        }
    }

    /**
     * Start an interactive Claude session and return a Flow of events.
     *
     * @param conversationId Optional conversation ID to resume
     * @return Flow of ClaudeEvent objects
     */
    fun startSession(conversationId: String? = null): Flow<ClaudeEvent> = channelFlow {
        val env = buildEnvironment()
        val cmdArgs = mutableListOf(
            getClaudePath(),
            "--output-format", "stream-json"
        )
        conversationId?.let {
            cmdArgs.add("--resume")
            cmdArgs.add(it)
        }

        Log.i(TAG, "Starting Claude session: ${cmdArgs.joinToString(" ")}")

        try {
            val processBuilder = ProcessBuilder(cmdArgs)
                .directory(File("$PREFIX_PATH/.."))
                .redirectErrorStream(false)

            processBuilder.environment().putAll(env)

            process = processBuilder.start()
            outputWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream))

            // Read stdout in separate coroutine
            val stdoutJob = launch {
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "Claude stdout: $line")
                        parseAndEmit(line!!)?.let { event ->
                            send(event)
                        }
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e(TAG, "Error reading stdout", e)
                    }
                } finally {
                    reader.close()
                }
            }

            // Read stderr in separate coroutine
            val stderrJob = launch {
                val reader = BufferedReader(InputStreamReader(process!!.errorStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.w(TAG, "Claude stderr: $line")
                        send(ClaudeEvent.Error(line!!))
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e(TAG, "Error reading stderr", e)
                    }
                } finally {
                    reader.close()
                }
            }

            // Wait for process to exit
            val exitCode = process!!.waitFor()
            Log.i(TAG, "Claude process exited with code: $exitCode")

            stdoutJob.cancelAndJoin()
            stderrJob.cancelAndJoin()

            send(ClaudeEvent.SessionEnded(exitCode))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Claude session", e)
            send(ClaudeEvent.Error("Failed to start Claude: ${e.message}"))
        }

        awaitClose {
            close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a message to the running Claude session.
     */
    fun sendMessage(message: String) {
        scope.launch {
            try {
                outputWriter?.let { writer ->
                    Log.i(TAG, "Sending message: ${message.take(100)}...")
                    writer.write(message)
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    /**
     * Send a special command to Claude (e.g., for tool responses).
     */
    fun sendToolResponse(toolUseId: String, result: String) {
        val response = JSONObject().apply {
            put("type", "tool_result")
            put("tool_use_id", toolUseId)
            put("content", result)
        }
        sendMessage(response.toString())
    }

    /**
     * Cancel the current request.
     */
    fun cancelCurrentRequest() {
        process?.let {
            // Send interrupt signal
            try {
                // On Android, we can't easily send SIGINT, so destroy the process
                it.destroy()
                Log.i(TAG, "Cancelled Claude request")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel request", e)
            }
        }
    }

    /**
     * Close the Claude session and clean up resources.
     */
    fun close() {
        Log.i(TAG, "Closing Claude client")
        try {
            outputWriter?.close()
            process?.destroy()
            persistentProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Claude client", e)
        } finally {
            outputWriter = null
            process = null
            persistentProcess = null
            sessionActive = false
        }
        scope.cancel()
    }

    /**
     * Clear conversation history for a fresh start.
     */
    fun clearConversation() {
        conversationId = null
        Log.i(TAG, "Conversation cleared")
    }

    /**
     * Build environment variables for Claude CLI.
     */
    private fun buildEnvironment(): Map<String, String> {
        return mapOf(
            "HOME" to "$PREFIX_PATH/../home",
            "PREFIX" to PREFIX_PATH,
            "PATH" to "$PREFIX_PATH/bin",
            "LD_LIBRARY_PATH" to "$PREFIX_PATH/lib",
            "TMPDIR" to "$PREFIX_PATH/tmp",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to "$PREFIX_PATH/bin/bash",
            "CLAUDE_CODE_SHELL" to "$PREFIX_PATH/bin/bash",
            "OPENSSL_CONF" to "$PREFIX_PATH/etc/tls/openssl.cnf"
        )
    }

    /**
     * Parse a JSON line from Claude CLI output and convert to ClaudeEvent.
     */
    private fun parseAndEmit(line: String): ClaudeEvent? {
        if (line.isBlank()) return null

        return try {
            val json = JSONObject(line)
            when (json.optString("type")) {
                "text" -> ClaudeEvent.Text(json.optString("content", ""))
                "text_delta" -> ClaudeEvent.TextDelta(json.optString("content", ""))
                "tool_use" -> ClaudeEvent.ToolUse(
                    id = json.optString("id"),
                    name = json.optString("name"),
                    input = json.optString("input")
                )
                "message_start" -> ClaudeEvent.MessageStart(
                    messageId = json.optString("message_id")
                )
                "message_end" -> ClaudeEvent.MessageEnd
                "error" -> ClaudeEvent.Error(json.optString("message", "Unknown error"))
                else -> {
                    Log.d(TAG, "Unknown event type: ${json.optString("type")}")
                    null
                }
            }
        } catch (e: Exception) {
            // Not JSON, treat as plain text
            ClaudeEvent.Text(line)
        }
    }


    /**
     * Get system prompt describing Android tools available.
     */
    private fun getAndroidToolsPrompt(): String {
        return """
IMPORTANT: You are running on Android/Termux. Some built-in tools may not work correctly.

FILE OPERATIONS:
- AVOID using Glob, Read tools directly - they have compatibility issues on Android
- USE the Bash tool for ALL file operations:
  - List files: Bash with "ls -la /path"
  - Read file: Bash with "cat /path/file"
  - Find files: Bash with "find /path -name '*.txt'"
  - Search content: Bash with "grep -r 'pattern' /path"

ENVIRONMENT:
- Home directory: /data/data/com.anthroid/files/home
- Prefix: /data/data/com.anthroid/files/usr
- Current working directory: /data/data/com.anthroid/files

AVAILABLE TOOLS:
- Bash: Execute shell commands (USE THIS for file operations)
- Write/Edit: Create or modify files (these work correctly)

ANDROID FEATURES (use Bash with termux-api commands):
- Notification: termux-notification --title "Title" --content "Message"
- Open URL: termux-open-url "https://..."
- Clipboard read: termux-clipboard-get
- Clipboard write: termux-clipboard-set "text"
- Location: termux-location
- Toast message: termux-toast "message"
- Vibrate: termux-vibrate
- TTS: termux-tts-speak "text"
- Battery info: termux-battery-status
- WiFi info: termux-wifi-connectioninfo

When the user asks about files, use Bash commands.
When the user asks about Android features (notifications, apps, location, etc.), use Bash with termux-api commands.
""".trimIndent()
    }
}

/**
 * Events emitted by Claude CLI.
 */
sealed class ClaudeEvent {
    /** Start of a new message */
    data class MessageStart(val messageId: String) : ClaudeEvent()

    /** Complete text content */
    data class Text(val content: String) : ClaudeEvent()

    /** Incremental text update (streaming) */
    data class TextDelta(val content: String) : ClaudeEvent()

    /** Tool use request from Claude */
    data class ToolUse(
        val id: String,
        val name: String,
        val input: String
    ) : ClaudeEvent()

    /** End of a message */
    object MessageEnd : ClaudeEvent()

    /** Error occurred */
    data class Error(val message: String) : ClaudeEvent()

    /** Session ended */
    data class SessionEnded(val exitCode: Int) : ClaudeEvent()
}