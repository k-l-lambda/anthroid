package com.anthroid.claude

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthroid.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.anthroid.mcp.McpServer

/**
 * ViewModel for Claude chat interface.
 * Manages chat state and communication with ClaudeCliClient or ClaudeApiClient.
 */
class ClaudeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ClaudeViewModel"
    }

    private val cliClient = ClaudeCliClient(application)
    private val apiClient = ClaudeApiClient(application)
    private val androidTools = AndroidTools(application)
    private val conversationManager = ConversationManager(application)

    // Chat messages
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Current streaming content (for assistant's response)
    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()

    // Processing state
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Claude CLI installation state (true if CLI or API is available)
    private val _isClaudeInstalled = MutableStateFlow(false)
    val isClaudeInstalled: StateFlow<Boolean> = _isClaudeInstalled.asStateFlow()

    // Pending images for next message
    private val _pendingImages = MutableStateFlow<List<MessageImage>>(emptyList())
    val pendingImages: StateFlow<List<MessageImage>> = _pendingImages.asStateFlow()

    // Use CLI or API
    private var useCliMode = false

    // Current session job
    private var sessionJob: Job? = null

    // Current streaming message ID
    private var streamingMessageId: String? = null

    init {
        checkClaudeInstallation()

        // Set up MCP server callback to handle tool completion events
        McpServer.onToolComplete = { toolName, isError ->
            Log.d(TAG, "MCP onToolComplete: tool=$toolName, isError=$isError")
            viewModelScope.launch {
                if (isError) {
                    // Update the most recent tool message with this name to show error
                    _messages.value = _messages.value.map { msg ->
                        if (msg.role == MessageRole.TOOL &&
                            msg.toolName?.contains(toolName) == true &&
                            !msg.isError) {
                            Log.d(TAG, "Setting isError=true for tool message: ${msg.id}")
                            msg.copy(isStreaming = false, isError = true)
                        } else {
                            msg
                        }
                    }
                } else {
                    // Mark streaming tool as complete (success)
                    _messages.value = _messages.value.map { msg ->
                        if (msg.role == MessageRole.TOOL &&
                            msg.toolName?.contains(toolName) == true &&
                            msg.isStreaming) {
                            msg.copy(isStreaming = false)
                        } else {
                            msg
                        }
                    }
                }
            }
        }
    }

    /**
     * Configure API client with credentials.
     * Uses BuildConfig values as defaults for baseUrl and model.
     */
    fun configureApi(apiKey: String, baseUrl: String = BuildConfig.CLAUDE_API_BASE_URL, model: String = BuildConfig.CLAUDE_API_MODEL) {
        apiClient.configure(apiKey, baseUrl, model)
        checkClaudeInstallation()
    }

    /**
     * Check if Claude CLI is installed or API is configured.
     */
    fun checkClaudeInstallation() {
        val cliAvailable = cliClient.isClaudeInstalled()
        val apiConfigured = apiClient.isConfigured()
        // Prefer API mode when configured, as it has proper tool support
        // CLI mode uses MCP which doesn't support our custom Android tools
        useCliMode = cliAvailable && !apiConfigured
        _isClaudeInstalled.value = useCliMode || apiConfigured
        Log.i(TAG, "Claude CLI installed: $cliAvailable, API configured: $apiConfigured, using CLI mode: $useCliMode")
    }

    /**
     * Add an image to pending images.
     */
    fun addPendingImage(uri: Uri) {
        val mimeType = ImageUtils.getMimeType(getApplication(), uri)
        val image = MessageImage(uri = uri, mimeType = mimeType)
        _pendingImages.value = _pendingImages.value + image
        Log.d(TAG, "Added pending image: $uri, total: ${_pendingImages.value.size}")
    }

    /**
     * Remove a pending image by ID.
     */
    fun removePendingImage(imageId: String) {
        _pendingImages.value = _pendingImages.value.filter { it.id != imageId }
        Log.d(TAG, "Removed pending image: $imageId, remaining: ${_pendingImages.value.size}")
    }

    /**
     * Clear all pending images.
     */
    fun clearPendingImages() {
        _pendingImages.value = emptyList()
    }

    /**
     * Send a message to Claude.
     * @param isFromVoice If true, the message was transcribed from voice input via ASR
     */
    fun sendMessage(content: String, isFromVoice: Boolean = false) {
        val images = _pendingImages.value.toList()
        if (content.isBlank() && images.isEmpty()) return

        // Add voice input note for Claude if from ASR
        val messageContent = if (isFromVoice && content.isNotBlank()) {
            "$content\n[Note: This message was transcribed from voice input using speech recognition. Please be tolerant of potential transcription errors.]"
        } else {
            content
        }

        Log.i(TAG, "Sending message: ${content.take(50)}... with ${images.size} images (useCliMode=$useCliMode, isFromVoice=$isFromVoice)")

        // Add user message with images
        val userMessage = Message(
            role = MessageRole.USER,
            content = messageContent,
            images = images
        )

        // Clear pending images
        _pendingImages.value = emptyList()

        // Add streaming assistant message placeholder
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        streamingMessageId = assistantMessage.id

        _messages.value = _messages.value + userMessage + assistantMessage

        // Start processing
        _isProcessing.value = true
        _currentResponse.value = ""
        _error.value = null

        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            try {
                val hasImages = images.isNotEmpty()

                if (hasImages) {
                    // Images can use CLI with stream-json input, or API mode
                    val imageDataList = images.mapNotNull { image ->
                        val base64 = ImageUtils.processImageForApi(getApplication(), image.uri)
                        if (base64 != null) {
                            Pair(base64, image.mimeType)
                        } else null
                    }

                    if (useCliMode && cliClient.isClaudeInstalled()) {
                        // CLI mode with stream-json input for images
                        Log.d(TAG, "CLI mode: sending ${imageDataList.size} images via stream-json")
                        val cliImages = imageDataList.map { (base64, mimeType) ->
                            ClaudeCliClient.ImageData(base64, mimeType)
                        }
                        cliClient.chatWithImages(content, cliImages)
                            .collect { event ->
                                handleEvent(event)
                            }
                    } else if (apiClient.isConfigured()) {
                        // API mode for images
                        Log.d(TAG, "API mode: sending ${imageDataList.size} images")
                        val apiImages = imageDataList.map { (base64, mimeType) ->
                            ClaudeApiClient.ImageContent(base64, mimeType)
                        }
                        apiClient.chat(content, apiImages)
                            .collect { event ->
                                handleEvent(event)
                            }
                    } else {
                        // Neither CLI nor API available for images
                        _error.value = "Images require Claude CLI or API key to be configured"
                        _isProcessing.value = false
                        _messages.value = _messages.value.filter { it.id != streamingMessageId }
                        return@launch
                    }
                } else if (useCliMode) {
                    // CLI mode for text-only messages
                    cliClient.chatStreaming(content, emptyList())
                        .collect { event ->
                            handleEvent(event)
                        }
                } else {
                    // API mode for text-only messages
                    apiClient.chat(content, emptyList())
                        .collect { event ->
                            handleEvent(event)
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session error", e)
                _error.value = e.message
                _isProcessing.value = false
            }
        }
    }

    /**
     * Handle events from Claude CLI.
     */
    private fun handleEvent(event: ClaudeEvent) {
        when (event) {
            is ClaudeEvent.MessageStart -> {
                Log.d(TAG, "Message started: ${event.messageId}")
            }

            is ClaudeEvent.Text -> {
                _currentResponse.value = event.content
                updateStreamingMessage(event.content)
            }

            is ClaudeEvent.TextDelta -> {
                Log.d(TAG, "TextDelta received: ${event.content.take(50)}")

                // Mark any streaming tool messages as timed out/error
                // If agent is outputting text, previous tools should have completed
                _messages.value = _messages.value.map { msg ->
                    if (msg.role == MessageRole.TOOL && msg.isStreaming) {
                        Log.d(TAG, "Marking tool as timed out: ${msg.toolName}")
                        msg.copy(isStreaming = false, isError = true)
                    } else {
                        msg
                    }
                }
                // If streamingMessageId is null (e.g., after tool use), create new streaming message
                if (streamingMessageId == null) {
                    Log.d(TAG, "Creating new streaming message for post-tool response")
                    val assistantMessage = Message(
                        role = MessageRole.ASSISTANT,
                        content = "",
                        isStreaming = true
                    )
                    streamingMessageId = assistantMessage.id
                    _messages.value = _messages.value + assistantMessage
                    _currentResponse.value = ""
                }
                _currentResponse.value += event.content
                updateStreamingMessage(_currentResponse.value)
            }

            is ClaudeEvent.ToolUse -> {
                Log.i(TAG, "Tool use: ${event.name}")
                handleToolUse(event)
            }

            is ClaudeEvent.MessageEnd -> {
                // Check if current streaming message has content
                val msgId = streamingMessageId
                val currentMsg = if (msgId != null) _messages.value.find { it.id == msgId } else null
                if (currentMsg != null && currentMsg.content.isEmpty()) {
                    // Remove empty streaming message (happens during tool use)
                    Log.d(TAG, "MessageEnd: removing empty streaming message")
                    removeStreamingMessage()
                } else {
                    // Finalize the streaming message with content
                    // NOTE: Don't set isProcessing=false here - the session might continue
                    // with more tool calls or messages. Only SessionEnded should stop processing.
                    Log.d(TAG, "MessageEnd: finalizing message with content")
                    finalizeStreamingMessage()
                }
            }

            is ClaudeEvent.Error -> {
                Log.e(TAG, "Claude error: ${event.message}")
                _error.value = event.message
                // Show error in chat UI instead of removing message
                showErrorMessage(event.message)
            }

            is ClaudeEvent.SessionEnded -> {
                Log.i(TAG, "Session ended with code: ${event.exitCode}")
                _isProcessing.value = false
            }
        }
    }

    /**
     * Update the streaming message content.
     */
    private fun updateStreamingMessage(content: String) {
        val msgId = streamingMessageId
        if (msgId == null) {
            Log.w(TAG, "updateStreamingMessage: streamingMessageId is null!")
            return
        }
        Log.d(TAG, "updateStreamingMessage: msgId=${msgId.take(8)}, contentLen=${content.length}")
        val oldList = _messages.value
        val newList = oldList.map { msg ->
            if (msg.id == msgId) {
                Log.d(TAG, "updateStreamingMessage: found msg, updating")
                msg.copy(content = content)
            } else {
                msg
            }
        }
        _messages.value = newList
        Log.d(TAG, "updateStreamingMessage: done, listSize=${newList.size}")
    }

    /**
     * Finalize the streaming message (mark as not streaming).
     */
    private fun finalizeStreamingMessage() {
        val msgId = streamingMessageId ?: return
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) {
                msg.copy(isStreaming = false)
            } else {
                msg
            }
        }
        streamingMessageId = null
        _currentResponse.value = ""
    }

    /**
     * Remove the streaming message (on error).
     */
    private fun removeStreamingMessage() {
        val msgId = streamingMessageId ?: return
        _messages.value = _messages.value.filter { it.id != msgId }
        streamingMessageId = null
        _currentResponse.value = ""
    }

    /**
     * Show error message in chat UI.
     */
    private fun showErrorMessage(errorMessage: String) {
        val msgId = streamingMessageId
        if (msgId != null) {
            // Update existing streaming message to show error
            val oldList = _messages.value
            val newList = oldList.map { msg ->
                if (msg.id == msgId) {
                    msg.copy(content = "Error: $errorMessage", isStreaming = false, isError = true)
                } else {
                    msg
                }
            }
            _messages.value = newList
            streamingMessageId = null
            _currentResponse.value = ""
        } else {
            // Add new error message
            val errorMsg = Message(
                role = MessageRole.ASSISTANT,
                content = "Error: $errorMessage",
                isError = true
            )
            _messages.value = _messages.value + errorMsg
        }
        _isProcessing.value = false
    }

    /**
     * Handle tool use requests from Claude.
     */
    private fun handleToolUse(event: ClaudeEvent.ToolUse) {
        // Add tool message to chat UI
        val toolInputText = try {
            val json = org.json.JSONObject(event.input)
            when (event.name.lowercase()) {
                "bash", "run_termux" -> json.optString("command", event.input)
                "read" -> json.optString("file_path", event.input)
                "write" -> json.optString("file_path", event.input)
                "open_url" -> json.optString("url", event.input)
                "launch_app", "get_app_info" -> json.optString("package", event.input)
                "show_notification" -> json.optString("title", "") + ": " + json.optString("message", event.input)
                "geocode" -> json.optString("address", event.input)
                "reverse_geocode" -> "${json.optDouble("latitude")}, ${json.optDouble("longitude")}"
                "get_location" -> json.optString("provider", "network")
                "query_calendar" -> "next ${json.optInt("days_ahead", 7)} days"
                "add_calendar_event" -> json.optString("title", event.input)
                "query_media" -> json.optString("type", "images")
                "read_terminal" -> "session: " + json.optString("session_id", "current")
                "read_clipboard" -> "read clipboard"
                "write_clipboard" -> json.optString("text", event.input).take(50) + "..."
                else -> event.input
            }
        } catch (e: Exception) {
            event.input
        }

        val toolMessage = Message(
            role = MessageRole.TOOL,
            content = toolInputText,
            toolName = event.name,
            toolInput = toolInputText,
            isStreaming = true
        )
        _messages.value = _messages.value + toolMessage

        // MCP tools (mcp__*) are handled by the MCP server via HTTP callback
        // Don't execute them locally - just show streaming state until callback updates it
        if (event.name.startsWith("mcp__")) {
            Log.d(TAG, "MCP tool \${event.name} - waiting for server callback")
            return
        }

        viewModelScope.launch {
            // In CLI mode, tool execution happens but CLI doesn't receive results
            // because CLI uses MCP protocol which we haven't implemented
            if (useCliMode) {
                Log.w(TAG, "Tool '${event.name}' called in CLI mode - results may not be sent back to Claude")
            }

            // Check if we can execute this tool locally
            val toolName = event.name.lowercase()
            val isLocalTool = toolName in listOf("run_termux", "bash", "read", "write", "read_terminal", "read_clipboard", "write_clipboard") ||
                              androidTools.isAndroidTool(event.name)
            
            // For unknown/CLI-handled tools, just keep streaming and return
            if (!isLocalTool) {
                Log.d(TAG, "CLI-handled tool ${event.name} - keeping streaming state")
                return@launch
            }
            
            val result = when (toolName) {
                "run_termux" -> executeRunTermuxTool(event.input)
                "bash" -> executeBashTool(event.input)
                "read" -> executeReadTool(event.input)
                "write" -> executeWriteTool(event.input)
                "read_terminal" -> executeReadTerminalTool(event.input)
                "read_clipboard" -> executeReadClipboardTool()
                "write_clipboard" -> executeWriteClipboardTool(event.input)
                else -> androidTools.executeTool(event.name, event.input)
            }

            // Update tool message to show completed with result
            _messages.value = _messages.value.map { msg ->
                if (msg.id == toolMessage.id) msg.copy(
                    isStreaming = false,
                    content = "${toolInputText}\n\n📤 Result:\n${result.take(500)}${if (result.length > 500) "..." else ""}"
                )
                else msg
            }

            // Send tool result back to Claude
            if (useCliMode) {
                cliClient.sendToolResponse(event.id, result)
            } else {
                // Use API client for HTTP mode
                apiClient.sendToolResult(event.id, event.name, result)
                    .collect { responseEvent ->
                        handleEvent(responseEvent)
                    }
            }
        }
    }

    /**
     * Execute bash command tool.
     * Uses Termux shell with proper environment.
     */
    private suspend fun executeBashTool(input: String): String {
        return try {
            val command = org.json.JSONObject(input).optString("command", "")
            Log.i(TAG, "Executing bash: $command")

            // Intercept TOOL_CALL broadcasts and execute directly (broadcasts from subprocess dont work)
            if (command.contains("com.anthroid.TOOL_CALL") && command.contains("--es tool")) {
                Log.i(TAG, "Intercepting TOOL_CALL command for direct execution")
                val toolRegex = Regex("""--es tool ["'"]([^"'"]+)["'"]""")
                val inputRegex = Regex("""--es input '(\{.*?\})'""")
                val toolMatch = toolRegex.find(command)
                val inputMatch = inputRegex.find(command)
                val toolName = toolMatch?.groupValues?.getOrNull(1) ?: ""
                val toolInput = inputMatch?.groupValues?.getOrNull(1) ?: "{}"
                Log.i(TAG, "Parsed tool=$toolName, input=$toolInput")
                if (toolName.isNotEmpty()) {
                    val androidTools = AndroidTools(getApplication())
                    val result = androidTools.executeTool(toolName, toolInput)
                    Log.i(TAG, "Direct tool execution result: $result")
                    return result
                }
            }

            // Try Termux terminal bridge first
            if (TerminalCommandBridge.isAvailable()) {
                Log.i(TAG, "Using Termux terminal for bash command")
                val result = TerminalCommandBridge.executeCommand(
                    command = command,
                    timeout = 60000
                )
                // If bridge succeeded, return result
                if (result.success) {
                    return result.toToolResult()
                }
                Log.w(TAG, "Terminal bridge failed: ${result.output}, falling back to direct execution")
            }

            // Fallback to direct execution with Termux shell
            Log.i(TAG, "Using direct execution for bash command")
            val termuxBin = "/data/data/com.anthroid/files/usr/bin"
            val termuxHome = "/data/data/com.anthroid/files/home"
            val termuxLib = "/data/data/com.anthroid/files/usr/lib"
            val termuxPrefix = "/data/data/com.anthroid/files/usr"

            // If command uses /system/bin, don't set LD_LIBRARY_PATH to avoid library conflicts
            val usesSystemBin = command.contains("/system/bin")
            val wrappedCommand = if (usesSystemBin) {
                // For system commands: include /system/bin in PATH, unset LD_LIBRARY_PATH
                "export HOME='$termuxHome' PREFIX='$termuxPrefix' PATH='/system/bin:$termuxBin' LANG='en_US.UTF-8' TERM='xterm-256color' && unset LD_LIBRARY_PATH && $command"
            } else {
                // For termux commands: use termux environment with system bin fallback
                "export HOME='$termuxHome' PREFIX='$termuxPrefix' PATH='$termuxBin:/system/bin' LD_LIBRARY_PATH='$termuxLib' LANG='en_US.UTF-8' TERM='xterm-256color' && $command"
            }

            val process = Runtime.getRuntime().exec(
                arrayOf("$termuxBin/bash", "-c", wrappedCommand),
                null,
                java.io.File(termuxHome)
            )

            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.i(TAG, "Bash execution completed: exitCode=$exitCode, outputLen=${output.length}, errorLen=${errorOutput.length}")
            if (output.isNotEmpty()) Log.d(TAG, "Output: ${output.take(200)}")
            if (errorOutput.isNotEmpty()) Log.w(TAG, "Error: ${errorOutput.take(200)}")

            if (exitCode == 0) {
                output.ifEmpty { "(no output)" }
            } else {
                "Exit code: $exitCode\n$output$errorOutput"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bash execution failed", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Execute file read tool.
     */
    private fun executeReadTool(input: String): String {
        return try {
            val path = org.json.JSONObject(input).optString("path", "")
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                file.readText()
            } else {
                "Error: Cannot read file $path"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Execute file write tool.
     */
    private fun executeWriteTool(input: String): String {
        return try {
            val json = org.json.JSONObject(input)
            val path = json.optString("path", "")
            val content = json.optString("content", "")
            val file = java.io.File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            "File written successfully: $path"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    /**
     * Read terminal session text.
     */
    private suspend fun executeReadTerminalTool(input: String): String {
        return try {
            val json = org.json.JSONObject(input)
            val sessionId = if (json.has("session_id")) json.optString("session_id") else null
            val maxLines = json.optInt("max_lines", 0)

            if (!TerminalCommandBridge.isAvailable()) {
                return "Error: Termux terminal not available. Please open Termux first."
            }

            Log.i(TAG, "read_terminal: session=${sessionId ?: "current"}, maxLines=$maxLines")

            val result = TerminalCommandBridge.readTerminalSession(
                sessionId = sessionId,
                maxLines = maxLines
            )
            result.toToolResult()
        } catch (e: Exception) {
            Log.e(TAG, "read_terminal failed", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Execute command in Termux terminal.
     * Shows command in terminal and captures output.
     */
    private suspend fun executeRunTermuxTool(input: String): String {
        return try {
            val json = org.json.JSONObject(input)
            val command = json.optString("command", "")
            val sessionId = if (json.has("session_id")) json.optString("session_id") else null
            val timeout = json.optLong("timeout", 30000)

            if (command.isEmpty()) {
                return "Error: command is required"
            }

            if (!TerminalCommandBridge.isAvailable()) {
                return "Error: Terminal not available. Please open Termux terminal first."
            }

            Log.i(TAG, "run_termux: $command (session: ${sessionId ?: "current"})")

            val result = TerminalCommandBridge.executeCommand(
                command = command,
                sessionId = sessionId,
                timeout = timeout
            )

            result.toToolResult()
        } catch (e: Exception) {
            Log.e(TAG, "run_termux failed", e)
            "Error: ${e.message}"
        }
    }


    /**
     * Read text from clipboard.
     */
    private fun executeReadClipboardTool(): String {
        return try {
            val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (text.isNullOrEmpty()) {
                    "Clipboard is empty"
                } else {
                    text
                }
            } else {
                "Clipboard is empty"
            }
        } catch (e: Exception) {
            Log.e(TAG, "read_clipboard failed", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Write text to clipboard.
     */
    private fun executeWriteClipboardTool(input: String): String {
        return try {
            val json = org.json.JSONObject(input)
            val text = json.optString("text", "")
            if (text.isEmpty()) {
                return "Error: text is required"
            }

            val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Claude", text)
            clipboard.setPrimaryClip(clip)
            "Text copied to clipboard (${text.length} characters)"
        } catch (e: Exception) {
            Log.e(TAG, "write_clipboard failed", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Cancel the current request.
     */
    fun cancelRequest() {
        Log.i(TAG, "Cancelling request")
        sessionJob?.cancel()
        if (useCliMode) {
            cliClient.cancelCurrentRequest()
        }

        // Mark the streaming message as interrupted
        val msgId = streamingMessageId
        if (msgId != null) {
            _messages.value = _messages.value.map { msg ->
                if (msg.id == msgId) {
                    msg.copy(isStreaming = false, isInterrupted = true)
                } else {
                    msg
                }
            }
            streamingMessageId = null
            _currentResponse.value = ""
        }

        _isProcessing.value = false
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _error.value = null
    }


    /**
     * Resume a conversation from history.
     * Loads messages from JSONL file and sets CLI session ID.
     */
    fun resumeConversation(sessionId: String) {
        Log.i(TAG, "Resuming conversation: $sessionId")
        viewModelScope.launch {
            // Clear current messages
            _messages.value = emptyList()
            _currentResponse.value = ""
            
            // Load messages from conversation file
            val historyMessages = conversationManager.loadConversation(sessionId)
            Log.i(TAG, "Loaded ${historyMessages.size} messages from history")
            
            // Convert to UI messages
            val uiMessages = historyMessages.mapNotNull { msg ->
                when (msg.type) {
                    "user" -> Message(
                        role = MessageRole.USER,
                        content = msg.content,
                        timestamp = msg.timestamp
                    )
                    "assistant" -> if (msg.toolName != null) {
                        // Use isError from ConversationManager (parsed from tool_result)
                        val isToolError = msg.isError ||
                                          msg.content.contains("\"success\": false") ||
                                          msg.content.contains("\"success\":false") ||
                                          msg.content.startsWith("Error:")
                        Message(
                            role = MessageRole.TOOL,
                            content = msg.content,
                            timestamp = msg.timestamp,
                            toolName = msg.toolName,
                            toolInput = msg.toolInput,
                            isError = isToolError
                        )
                    } else if (msg.content.isNotEmpty()) {
                        Message(
                            role = MessageRole.ASSISTANT,
                            content = msg.content,
                            timestamp = msg.timestamp
                        )
                    } else null
                    else -> null
                }
            }
            
            _messages.value = uiMessages
            
            // Set session ID for CLI to resume
            cliClient.setConversationId(sessionId)
            
            Log.i(TAG, "Conversation resumed with ${uiMessages.size} UI messages")
        }
    }

    /**
     * Start a new conversation (clear current and reset session).
     */
    fun startNewConversation() {
        Log.i(TAG, "Starting new conversation")
        _messages.value = emptyList()
        _currentResponse.value = ""
        apiClient.clearHistory()
        cliClient.clearConversation()
    }

    /**
     * Clear all messages.
     */
    fun clearMessages() {
        _messages.value = emptyList()
        _currentResponse.value = ""
        apiClient.clearHistory()
        cliClient.clearConversation()
    }

    override fun onCleared() {
        super.onCleared()
        cliClient.close()
    }
}

/**
 * Represents a chat message.
 */
data class Message(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val isInterrupted: Boolean = false,
    val toolName: String? = null,
    val toolInput: String? = null,
    val images: List<MessageImage> = emptyList()
)

/**
 * Represents an image attached to a message.
 */
data class MessageImage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val mimeType: String = "image/jpeg"
)

/**
 * Message role in the conversation.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}
