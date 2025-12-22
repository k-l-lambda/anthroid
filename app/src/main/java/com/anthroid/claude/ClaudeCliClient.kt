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
            cmdArgs.add("--conversation")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Claude client", e)
        } finally {
            outputWriter = null
            process = null
        }
        scope.cancel()
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
            "TERM" to "xterm-256color"
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
