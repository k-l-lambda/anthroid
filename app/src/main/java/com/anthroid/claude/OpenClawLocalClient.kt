package com.anthroid.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*

/**
 * Client for communicating with OpenClaw agent process (run.mjs).
 * Wraps the OpenClaw pi-embedded-runner and provides a Flow-based API
 * compatible with ClaudeEvent, matching ClaudeCliClient's interface.
 *
 * Communication protocol (stream-json stdio):
 *   stdin:  JSON line — { type: "user", message: { role: "user", content: [...] } }
 *   stdout: JSON lines — { type: "system"|"stream_event"|"error", ... }
 *
 * The agent handles all tools internally via its built-in bash tool,
 * which calls android-tools-bridge.mjs to reach anthroid's MCP server.
 * No local tool execution is needed from the Kotlin side.
 */
class OpenClawLocalClient(private val context: Context) {

    companion object {
        private const val TAG = "OpenClawLocalClient"
        private const val PREFIX_PATH = "/data/data/com.anthroid/files/usr"
        private const val AGENT_DIR_NAME = "openclaw-agent-local"
    }

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Thinking block state tracking
    private var isInThinkingBlock = false

    // API credentials — set via configure()
    private var apiKey: String = ""
    private var baseUrl: String = ""
    private var model: String = ""

    private val agentDir: String
        get() = "$PREFIX_PATH/../home/$AGENT_DIR_NAME"

    /**
     * Check if the OpenClaw agent is installed (run.mjs exists and node is available).
     */
    fun isAgentInstalled(): Boolean {
        val runMjs = File("$agentDir/run.mjs")
        val node = File("$PREFIX_PATH/bin/node")
        val installed = runMjs.exists() && node.exists()
        Log.d(TAG, "Agent installed check: runMjs=${runMjs.exists()}, node=${node.exists()} → $installed")
        return installed
    }

    /**
     * Configure API credentials for the agent.
     * The agent uses these to talk to the LLM provider.
     */
    fun configure(apiKey: String, baseUrl: String = "", model: String = "") {
        this.apiKey = apiKey
        this.baseUrl = baseUrl
        this.model = model
    }

    /**
     * Send a message (with optional images) and stream the response.
     *
     * Spawns a new agent process per message. Session persistence is handled
     * by the agent's JSONL session files — each process reads and appends to
     * the most recent session file, maintaining full conversation context.
     *
     * @param message The text message to send
     * @param images Optional list of base64-encoded images
     * @return Flow of ClaudeEvent objects with streaming response
     */
    fun chat(
        message: String,
        images: List<ClaudeCliClient.ImageData>? = null
    ): Flow<ClaudeEvent> = channelFlow {
        val env = buildEnvironment()
        val nodePath = "$PREFIX_PATH/bin/node"
        val runMjsPath = "$agentDir/run.mjs"

        Log.i(TAG, "Starting OpenClaw agent for message: ${message.take(50)}...")

        isInThinkingBlock = false

        try {
            val processBuilder = ProcessBuilder(nodePath, runMjsPath)
                .directory(File(agentDir))
                .redirectErrorStream(false)

            processBuilder.environment().putAll(env)

            val proc = processBuilder.start()
            process = proc

            // Build stream-json input message
            val contentArray = JSONArray()
            if (message.isNotBlank()) {
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", message)
                })
            }
            images?.forEach { image ->
                contentArray.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", image.mimeType)
                        put("data", image.base64)
                    })
                })
            }

            val inputJson = JSONObject().apply {
                put("type", "user")
                put("message", JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
            }

            // Write JSON line to stdin, then close to signal end of input.
            // The agent processes this message, streams events, then exits.
            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val jsonLine = inputJson.toString()
            Log.d(TAG, "Sending input: ${jsonLine.take(200)}...")
            writer.write(jsonLine)
            writer.newLine()
            writer.flush()
            writer.close()
            Log.d(TAG, "Stdin closed")

            // Read stderr in background
            val stderrJob = launch {
                val reader = BufferedReader(InputStreamReader(proc.errorStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.contains("unused DT entry")) continue
                        Log.w(TAG, "OpenClaw stderr: $line")
                    }
                } catch (e: IOException) {
                    if (isActive) Log.e(TAG, "Error reading stderr", e)
                } finally {
                    reader.close()
                }
            }

            // Read stdout char by char for real-time streaming.
            // Events are JSON lines terminated by \n.
            val stdoutReader = InputStreamReader(proc.inputStream)
            try {
                val lineBuilder = StringBuilder()
                var charCode: Int
                while (stdoutReader.read().also { charCode = it } != -1) {
                    if (charCode == '\n'.code) {
                        val line = lineBuilder.toString()
                        lineBuilder.clear()
                        if (line.isNotBlank()) {
                            Log.d(TAG, "Event: ${line.take(100)}")
                            parseStreamEvent(line)?.let { event ->
                                send(event)
                            }
                        }
                    } else {
                        lineBuilder.append(charCode.toChar())
                    }
                }
                // Handle any remaining content in buffer
                if (lineBuilder.isNotEmpty()) {
                    val line = lineBuilder.toString()
                    if (line.isNotBlank()) {
                        parseStreamEvent(line)?.let { send(it) }
                    }
                }
            } catch (e: IOException) {
                if (isActive) Log.e(TAG, "Error reading stdout", e)
            } finally {
                stdoutReader.close()
            }

            val exitCode = proc.waitFor()
            Log.i(TAG, "OpenClaw agent exited with code: $exitCode")

            stderrJob.cancelAndJoin()

            send(ClaudeEvent.MessageEnd)
            send(ClaudeEvent.SessionEnded(exitCode))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to run OpenClaw agent", e)
            send(ClaudeEvent.Error("Failed to run OpenClaw agent: ${e.message}"))
        } finally {
            process = null
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * Cancel the current agent request by destroying the process.
     */
    fun cancelCurrentRequest() {
        process?.let {
            try {
                it.destroy()
                Log.i(TAG, "Cancelled OpenClaw agent request")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel request", e)
            }
        }
    }

    /**
     * Close the client and clean up resources.
     */
    fun close() {
        Log.i(TAG, "Closing OpenClaw client")
        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing OpenClaw client", e)
        } finally {
            process = null
        }
        scope.cancel()
    }

    /**
     * Build environment variables for the agent process.
     * Includes Termux paths, API credentials, and MCP endpoint.
     */
    private fun buildEnvironment(): Map<String, String> {
        val env = mutableMapOf(
            "HOME" to "$PREFIX_PATH/../home",
            "PREFIX" to PREFIX_PATH,
            "PATH" to "$PREFIX_PATH/bin",
            "LD_LIBRARY_PATH" to "$PREFIX_PATH/lib",
            "TMPDIR" to "$PREFIX_PATH/tmp",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to "$PREFIX_PATH/bin/bash",
            "MCP_ENDPOINT" to "http://localhost:8765/mcp",
            "SESSION_DIR" to "$agentDir/.sessions",
            "WORKSPACE_DIR" to "$PREFIX_PATH/../home"
        )
        if (apiKey.isNotBlank()) {
            env["ANTHROPIC_API_KEY"] = apiKey
        }
        if (baseUrl.isNotBlank()) {
            env["ANTHROPIC_BASE_URL"] = baseUrl
        }
        if (model.isNotBlank()) {
            env["MODEL"] = model
        }
        return env
    }

    /**
     * Parse a stream-json event line into a ClaudeEvent.
     *
     * Handles the same format as run.mjs output:
     *   { type: "system", session_id: "..." }
     *   { type: "stream_event", event: { type: "message_start"|"content_block_*"|"message_stop", ... } }
     *   { type: "error", error: { message: "..." } }
     */
    private fun parseStreamEvent(line: String): ClaudeEvent? {
        return try {
            val json = JSONObject(line)
            val type = json.optString("type", "")

            when (type) {
                "system" -> {
                    val sessionId = json.optString("session_id", "unknown")
                    ClaudeEvent.MessageStart(sessionId)
                }

                "stream_event" -> {
                    val event = json.optJSONObject("event") ?: return null
                    val eventType = event.optString("type", "")

                    when (eventType) {
                        "message_start" -> {
                            val msgObj = event.optJSONObject("message")
                            ClaudeEvent.MessageStart(
                                msgObj?.optString("id", "unknown") ?: "unknown"
                            )
                        }

                        "message_stop" -> ClaudeEvent.MessageEnd

                        "content_block_start" -> {
                            val contentBlock = event.optJSONObject("content_block")
                            when (contentBlock?.optString("type", "")) {
                                "thinking" -> {
                                    isInThinkingBlock = true
                                    Log.d(TAG, "Thinking block started")
                                    ClaudeEvent.ThinkingStart
                                }
                                else -> null
                            }
                        }

                        "content_block_delta" -> {
                            val delta = event.optJSONObject("delta")
                            when (delta?.optString("type", "")) {
                                "text_delta" -> {
                                    val text = delta?.optString("text", "") ?: ""
                                    if (text.isNotEmpty()) ClaudeEvent.TextDelta(text) else null
                                }
                                "thinking_delta" -> {
                                    val thinking = delta?.optString("thinking", "") ?: ""
                                    if (thinking.isNotEmpty()) ClaudeEvent.ThinkingDelta(thinking) else null
                                }
                                else -> null
                            }
                        }

                        "content_block_stop" -> {
                            if (isInThinkingBlock) {
                                isInThinkingBlock = false
                                Log.d(TAG, "Thinking block ended")
                                ClaudeEvent.ThinkingEnd
                            } else {
                                null
                            }
                        }

                        else -> null
                    }
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    ClaudeEvent.Error(
                        error?.optString("message", "Unknown error") ?: "Unknown error"
                    )
                }

                else -> {
                    Log.d(TAG, "Unknown event type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse event: $line", e)
            null
        }
    }
}
