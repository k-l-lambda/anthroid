package com.anthroid.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.TimeUnit

/**
 * Client for communicating with OpenClaw agent process (run.mjs).
 * Wraps the OpenClaw pi-embedded-runner and provides a Flow-based API
 * compatible with ClaudeEvent, matching ClaudeCliClient's interface.
 *
 * Communication protocol (stream-json stdio):
 *   stdin:  JSON lines — first a config line, then user message
 *     { type: "config", apiKey: "...", baseUrl: "...", model: "..." }
 *     { type: "user", message: { role: "user", content: [...] } }
 *   stdout: JSON lines — { type: "system"|"stream_event"|"error", ... }
 *
 * The agent handles all tools internally via its built-in bash tool,
 * which calls android-tools-bridge.mjs to reach anthroid's MCP server.
 * No local tool execution is needed from the Kotlin side.
 */
class OpenClawLocalClient(private val context: Context) {

    companion object {
        private const val TAG = "OpenClawLocalClient"
        private const val AGENT_DIR_NAME = "openclaw-agent-local"
        private const val ASSET_DIR_NAME = "openclaw-agent-local"
    }

    @Volatile
    private var process: Process? = null
    private val chatMutex = Mutex()  // Enforce single-flight: only one chat() at a time
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // API credentials — set via configure()
    private var apiKey: String = ""
    private var baseUrl: String = ""
    private var model: String = ""

    private val prefixPath: String
        get() = "${context.filesDir.absolutePath}/usr"

    private val agentDir: String
        get() = "${context.filesDir.absolutePath}/home/$AGENT_DIR_NAME"

    /**
     * Check if the OpenClaw agent is installed (run.mjs exists and node is available).
     */
    fun isAgentInstalled(): Boolean {
        val runMjs = File("$agentDir/run.mjs")
        val node = File("$prefixPath/bin/node")
        val nodeModules = File("$agentDir/node_modules")
        val installed = runMjs.exists() && node.exists() && nodeModules.exists()
        Log.d(TAG, "Agent installed check: runMjs=${runMjs.exists()}, node=${node.exists()}, node_modules=${nodeModules.exists()} → $installed")
        return installed
    }

    /**
     * Update agent core files from APK assets if app version has changed.
     * Preserves node_modules and .sessions (runtime data).
     */
    fun updateAgentIfNeeded() {
        try {
            val versionFile = File("$agentDir/.version")
            val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

            if (versionFile.exists() && versionFile.readText().trim() == appVersion) {
                Log.d(TAG, "Agent files up to date (version $appVersion)")
                return
            }

            Log.i(TAG, "Updating OpenClaw agent files to version $appVersion")
            extractAgentFromAssets()

            // Write version marker
            versionFile.writeText(appVersion)
            Log.i(TAG, "Agent files updated to version $appVersion")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update agent files: ${e.message}")
        }
    }

    /**
     * Extract agent core files from APK assets to agentDir.
     * Copies everything except node_modules (installed via npm).
     */
    private fun extractAgentFromAssets() {
        val dir = File(agentDir)
        if (!dir.exists()) dir.mkdirs()

        val assetManager = context.assets
        copyAssetDir(assetManager, ASSET_DIR_NAME, dir.absolutePath)

        // Make scripts executable
        arrayOf("run.mjs", "android-tools-bridge.mjs", "create-stubs.mjs").forEach { name ->
            val f = File(dir, name)
            if (f.exists()) f.setExecutable(true)
        }

        Log.i(TAG, "Extracted agent files from assets to $agentDir")
    }

    private fun copyAssetDir(assetManager: android.content.res.AssetManager, assetPath: String, targetPath: String) {
        val children = assetManager.list(assetPath)
        if (children == null || children.isEmpty()) {
            // It's a file — copy it
            assetManager.open(assetPath).use { input ->
                FileOutputStream(targetPath).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory — recurse
            File(targetPath).mkdirs()
            for (child in children) {
                copyAssetDir(assetManager, "$assetPath/$child", "$targetPath/$child")
            }
        }
    }

    /**
     * Install agent dependencies if needed (runs .install_deps.sh).
     * Returns true if dependencies are ready, false if installation failed.
     */
    suspend fun ensureDependencies(): Boolean = withContext(Dispatchers.IO) {
        val nodeModules = File("$agentDir/node_modules")
        if (nodeModules.exists()) {
            // Check if stubs exist (created by create-stubs.mjs)
            val stubMarker = File("$agentDir/node_modules/@aws-sdk/client-bedrock/package.json")
            if (!stubMarker.exists()) {
                Log.i(TAG, "Stub packages missing, running create-stubs.mjs...")
                if (!runCreateStubs()) {
                    Log.w(TAG, "Failed to create stubs, agent may not start correctly")
                    return@withContext false
                }
            } else {
                Log.d(TAG, "node_modules and stubs ready")
            }
            return@withContext true
        }

        val installScript = File("$agentDir/.install_deps.sh")
        if (!installScript.exists()) {
            Log.w(TAG, "No install script found and no node_modules — agent not properly deployed")
            return@withContext false
        }

        Log.i(TAG, "Installing OpenClaw agent dependencies via npm...")
        try {
            val bashPath = "$prefixPath/bin/bash"
            val proc = ProcessBuilder(bashPath, installScript.absolutePath)
                .directory(File(agentDir))
                .redirectErrorStream(true)
                .start()

            // Read output for logging
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "npm install: $line")
            }

            val finished = proc.waitFor(600, TimeUnit.SECONDS)  // 10 min max for npm install
            if (!finished) {
                Log.w(TAG, "npm install timed out after 10 minutes, killing process")
                proc.destroyForcibly()
                return@withContext false
            }
            val exitCode = proc.exitValue()
            Log.i(TAG, "npm install completed with exit code: $exitCode")
            return@withContext exitCode == 0 && nodeModules.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install dependencies: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Run create-stubs.mjs to generate stub packages for channel-specific
     * dependencies (Discord, Slack, etc.) that are statically imported but
     * never invoked in anthroid's local-agent mode.
     */
    private suspend fun runCreateStubs(): Boolean = withContext(Dispatchers.IO) {
        try {
            val nodePath = "$prefixPath/bin/node"
            val stubScript = "$agentDir/create-stubs.mjs"

            if (!File(stubScript).exists()) {
                Log.w(TAG, "create-stubs.mjs not found")
                return@withContext false
            }

            val proc = ProcessBuilder(nodePath, stubScript)
                .directory(File(agentDir))
                .redirectErrorStream(true)
                .apply { environment().putAll(buildEnvironment()) }
                .start()

            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "create-stubs: $line")
            }

            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                Log.w(TAG, "create-stubs.mjs timed out")
                proc.destroyForcibly()
                return@withContext false
            }
            val exitCode = proc.exitValue()
            Log.i(TAG, "create-stubs.mjs completed with exit code: $exitCode")
            return@withContext exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run create-stubs.mjs: ${e.message}")
            return@withContext false
        }
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
        chatMutex.withLock {
            val env = buildEnvironment()
            val nodePath = "$prefixPath/bin/node"
            val runMjsPath = "$agentDir/run.mjs"

            Log.i(TAG, "Starting OpenClaw agent for message: ${message.take(50)}...")

            // Thinking block state — scoped to this chat invocation
            var isInThinkingBlock = false

            // Local process reference for reliable cleanup in awaitClose
            var proc: Process? = null

            try {
                val processBuilder = ProcessBuilder(nodePath, runMjsPath)
                    .directory(File(agentDir))
                    .redirectErrorStream(false)

                processBuilder.environment().putAll(env)

                proc = processBuilder.start()
                process = proc  // expose for cancelCurrentRequest()

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

                // Write config line first (API key via stdin, not env), then user message.
                val writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

                val configJson = JSONObject().apply {
                    put("type", "config")
                    if (apiKey.isNotBlank()) put("apiKey", apiKey)
                    if (baseUrl.isNotBlank()) put("baseUrl", baseUrl)
                    if (model.isNotBlank()) put("model", model)
                }
                writer.write(configJson.toString())
                writer.newLine()

                writer.write(inputJson.toString())
                writer.newLine()
                writer.flush()
                writer.close()
                Log.d(TAG, "Stdin written and closed (config + message)")

                // Read stderr in background
                val stderrJob = launch {
                    BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { reader ->
                        try {
                            var line: String? = null
                            while (isActive && reader.readLine().also { line = it } != null) {
                                if (line!!.contains("unused DT entry")) continue
                                Log.w(TAG, "OpenClaw stderr: ${redactSecrets(line!!)}")
                            }
                        } catch (e: IOException) {
                            if (isActive) Log.e(TAG, "Error reading stderr", e)
                        }
                    }
                }

                // Read stdout line by line for streaming.
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    try {
                        var line: String? = null
                        while (isActive && reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isBlank()) continue
                            Log.d(TAG, "Event: ${l.take(100)}")
                            parseStreamEvent(l, { isInThinkingBlock }, { isInThinkingBlock = it })?.let { event ->
                                send(event)
                            }
                        }
                    } catch (e: IOException) {
                        if (isActive) Log.e(TAG, "Error reading stdout", e)
                    }
                }

                // Wait for process with timeout, then force-kill if stuck
                val finished = proc.waitFor(30, TimeUnit.SECONDS)
                if (!finished) {
                    Log.w(TAG, "Agent process didn't exit in 30s, force-killing")
                    proc.destroyForcibly()
                }
                val exitCode = proc.exitValue()
                Log.i(TAG, "OpenClaw agent exited with code: $exitCode")

                stderrJob.cancelAndJoin()

                send(ClaudeEvent.MessageEnd)
                send(ClaudeEvent.SessionEnded(exitCode))

            } catch (e: Exception) {
                Log.e(TAG, "Failed to run OpenClaw agent", e)
                send(ClaudeEvent.Error("Failed to run OpenClaw agent: ${redactSecrets(e.message ?: "")}"))
            }

            awaitClose {
                // Use local proc ref — not the field (which may already be null)
                proc?.let {
                    Log.i(TAG, "awaitClose: destroying agent process")
                    it.destroyForcibly()
                }
                process = null
            }
        }
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
     * Includes Termux paths and MCP endpoint. API credentials are passed
     * via stdin (config line) to avoid exposure in /proc/<pid>/environ.
     */
    private fun buildEnvironment(): Map<String, String> {
        val prefix = prefixPath
        val home = "${context.filesDir.absolutePath}/home"
        return mapOf(
            "HOME" to home,
            "PREFIX" to prefix,
            "PATH" to "$prefix/bin",
            "LD_LIBRARY_PATH" to "$prefix/lib",
            "TMPDIR" to "$prefix/tmp",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to "$prefix/bin/bash",
            "MCP_ENDPOINT" to "http://localhost:8765/mcp",
            "SESSION_DIR" to "$agentDir/.sessions",
            "WORKSPACE_DIR" to home
        )
    }

    /**
     * Redact potential API keys and tokens from strings before logging.
     */
    private fun redactSecrets(text: String): String {
        return text
            .replace(Regex("sk-[a-zA-Z0-9_-]{10,}"), "sk-***")
            .replace(Regex("key[=:]\\s*[^\\s,;]{10,}", RegexOption.IGNORE_CASE), "key=***")
    }

    /**
     * Parse a stream-json event line into a ClaudeEvent.
     *
     * Handles the same format as run.mjs output:
     *   { type: "system", session_id: "..." }
     *   { type: "stream_event", event: { type: "message_start"|"content_block_*"|"message_stop", ... } }
     *   { type: "error", error: { message: "..." } }
     */
    private fun parseStreamEvent(
        line: String,
        getThinking: () -> Boolean,
        setThinking: (Boolean) -> Unit
    ): ClaudeEvent? {
        if (!line.trimStart().startsWith("{")) {
            Log.d(TAG, "Skipping non-JSON line: ${line.take(80)}")
            return null
        }
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
                                    setThinking(true)
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
                            if (getThinking()) {
                                setThinking(false)
                                Log.d(TAG, "Thinking block ended")
                                ClaudeEvent.ThinkingEnd
                            } else {
                                null
                            }
                        }

                        "tool_use" -> {
                            val toolId = event.optString("id", "")
                            val toolName = event.optString("name", "")
                            val toolInput = event.optString("input", "{}")
                            Log.i(TAG, "Tool use: $toolName (id: $toolId)")
                            if (toolName.isNotEmpty()) {
                                ClaudeEvent.ToolUse(toolId, toolName, toolInput)
                            } else null
                        }

                        "tool_result" -> {
                            val toolUseId = event.optString("tool_use_id", "")
                            // content can be a plain string or a JSON array of text blocks
                            val rawContent = event.opt("content")
                            val content = when (rawContent) {
                                is String -> rawContent
                                is JSONArray -> {
                                    // [{"type":"text","text":"..."},...]
                                    val sb = StringBuilder()
                                    for (i in 0 until rawContent.length()) {
                                        val block = rawContent.optJSONObject(i)
                                        if (block?.optString("type") == "text") {
                                            if (sb.isNotEmpty()) sb.append("\n")
                                            sb.append(block.optString("text", ""))
                                        }
                                    }
                                    sb.toString()
                                }
                                else -> ""
                            }
                            val isError = event.optBoolean("is_error", false)
                            val inputHint = event.optString("input_hint", "").takeIf { it.isNotEmpty() }
                            Log.i(TAG, "Tool result: id=$toolUseId, error=$isError, len=${content.length}")
                            if (toolUseId.isNotEmpty()) ClaudeEvent.ToolResult(toolUseId, content, isError, inputHint) else null
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
