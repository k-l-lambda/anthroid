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
     * Data class for image with base64 content.
     */
    data class ImageData(
        val base64: String,
        val mimeType: String
    )

    /**
     * Send a message with images using stream-json input format.
     * This enables multimodal support via CLI without needing API key.
     *
     * @param message The text message to send
     * @param images List of images with base64 content
     * @return Flow of ClaudeEvent objects with streaming response
     */
    fun chatWithImages(message: String, images: List<ImageData>): Flow<ClaudeEvent> = channelFlow {
        val env = buildEnvironment()
        val claudePath = getClaudePath()

        Log.i(TAG, "Starting Claude stream-json mode with ${images.size} images")

        try {
            val cmdArgs = mutableListOf(
                claudePath,
                "--print",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--dangerously-skip-permissions"
            )

            // Add conversation flag to resume previous session
            conversationId?.let {
                cmdArgs.add("--resume")
                cmdArgs.add(it)
            }

            val processBuilder = ProcessBuilder(cmdArgs)
                .directory(File("$PREFIX_PATH/.."))
                .redirectErrorStream(false)

            processBuilder.environment().putAll(env)

            val proc = processBuilder.start()
            process = proc

            // Build stream-json input message with images
            val contentArray = org.json.JSONArray()

            // Add text content
            if (message.isNotBlank()) {
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", message)
                })
            }

            // Add image content blocks
            images.forEach { image ->
                contentArray.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", image.mimeType)
                        put("data", image.base64)
                    })
                })
            }

            // Construct the stream-json input format
            val inputJson = JSONObject().apply {
                put("type", "user")
                put("message", JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
            }

            // Write JSON to stdin
            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val jsonLine = inputJson.toString()
            Log.d(TAG, "Sending stream-json input: ${jsonLine.take(200)}...")
            writer.write(jsonLine)
            writer.newLine()
            writer.flush()
            writer.close()
            Log.i(TAG, "Stdin closed with stream-json message")

            // Read stderr in background
            val stderrJob = launch {
                val reader = BufferedReader(InputStreamReader(proc.errorStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.contains("unused DT entry")) continue
                        Log.w(TAG, "Claude stderr: $line")
                    }
                } catch (e: IOException) {
                    if (isActive) Log.e(TAG, "Error reading stderr", e)
                } finally {
                    reader.close()
                }
            }

            // Read stdout line by line for stream-json events
            val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
            try {
                var line: String?
                while (stdoutReader.readLine().also { line = it } != null) {
                    if (line!!.isNotBlank()) {
                        Log.d(TAG, "Stream event: ${line!!.take(100)}")
                        parseStreamEvent(line!!)?.let { event ->
                            // Capture session ID from system event
                            if (event is ClaudeEvent.MessageStart) {
                                try {
                                    val json = JSONObject(line!!)
                                    if (json.optString("type") == "system") {
                                        val sessionId = json.optString("session_id", "")
                                        if (sessionId.isNotEmpty()) {
                                            conversationId = sessionId
                                            Log.i(TAG, "Captured session ID: $sessionId")
                                        }
                                    }
                                } catch (e: Exception) { /* ignore */ }
                            }
                            send(event)
                        }
                    }
                }
            } catch (e: IOException) {
                if (isActive) Log.e(TAG, "Error reading stdout", e)
            } finally {
                stdoutReader.close()
            }

            val exitCode = proc.waitFor()
            Log.i(TAG, "Claude stream-json process exited with code: $exitCode")

            stderrJob.cancelAndJoin()

            send(ClaudeEvent.MessageEnd)
            send(ClaudeEvent.SessionEnded(exitCode))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to run Claude stream-json mode", e)
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
     * @param imagePaths List of image file paths to include in the message (deprecated, use chatWithImages instead)
     * @return Flow of ClaudeEvent objects with real-time streaming
     */
    fun chatStreaming(message: String, imagePaths: List<String> = emptyList()): Flow<ClaudeEvent> = channelFlow {
        val env = buildEnvironment()
        val claudePath = getClaudePath()

        Log.i(TAG, "Starting Claude streaming mode for message: " + message.take(50) + "...")
        if (imagePaths.isNotEmpty()) {
            Log.i(TAG, "Including ${imagePaths.size} images: ${imagePaths.joinToString()}")
        }

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
            // Add image flags for each image path
            imagePaths.forEach { imagePath ->
                cmdArgs.add("--image")
                cmdArgs.add(imagePath)
            }
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
     * Set conversation ID to resume a previous session.
     */
    fun setConversationId(sessionId: String) {
        conversationId = sessionId
        Log.i(TAG, "Conversation ID set: $sessionId")
    }

    /**
     * Get current conversation ID.
     */
    fun getConversationId(): String? = conversationId

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

ANDROID FEATURES (use Bash with /system/bin/am broadcast to call Android tools):
IMPORTANT: Always use /system/bin/am (full path), NOT just "am", because the termux am wrapper crashes.
NOTE: The am command may output security warnings but the broadcast still works. Check the result file content, not the exit code.

Call Android tools via broadcast, result written to /sdcard/anthroid_tool_result.txt:
  /system/bin/am broadcast -a com.anthroid.TOOL_CALL --es tool "TOOL_NAME" --es input '{"key":"value"}' -p com.anthroid 2>/dev/null; sleep 0.3; cat /sdcard/anthroid_tool_result.txt

Available Android tools:
- show_notification: Input: {"title": "...", "message": "..."}
- open_url: Input: {"url": "https://..."}
- launch_app: Input: {"package": "com.example.app"}
- list_apps: Input: {"filter": "user|system|all", "limit": 50}
- get_app_info: Input: {"package": "com.example.app"}
- read_clipboard: Input: {} (no parameters needed)
- write_clipboard: Input: {"text": "..."}
- get_location: Input: {"provider": "network|gps"}
- geocode: Input: {"address": "..."}
- reverse_geocode: Input: {"latitude": 0.0, "longitude": 0.0}
- query_calendar: Input: {"days_ahead": 7, "limit": 20}
- add_calendar_event: Input: {"title": "...", "start_time": ms, "end_time": ms}
- query_media: Input: {"type": "images|videos|audio", "limit": 20}
- set_app_proxy: Set up VPN proxy for specified apps. Input: {"apps": ["pkg1", "pkg2"], "proxy_host": "localhost", "proxy_port": 1091, "proxy_type": "SOCKS5"}
  NOTE: Requires VPN permission granted in Settings > VPN Proxy first
- stop_app_proxy: Stop VPN proxy service. Input: {}
- get_proxy_status: Get current VPN proxy status. Input: {}

Example - show notification:
  /system/bin/am broadcast -a com.anthroid.TOOL_CALL --es tool "show_notification" --es input '{"title":"Hello","message":"World"}' -p com.anthroid 2>/dev/null; sleep 0.3; cat /sdcard/anthroid_tool_result.txt

Example - set up proxy for an app:
  /system/bin/am broadcast -a com.anthroid.TOOL_CALL --es tool "set_app_proxy" --es input '{"apps":["com.browser.app"],"proxy_host":"localhost","proxy_port":1091}' -p com.anthroid 2>/dev/null; sleep 0.3; cat /sdcard/anthroid_tool_result.txt

When the user asks about files, use Bash commands.
When the user asks about Android features, use am broadcast with the tools above.
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