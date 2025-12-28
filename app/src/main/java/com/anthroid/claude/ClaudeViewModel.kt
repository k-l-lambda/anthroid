package com.anthroid.claude

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthroid.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    // Use CLI or API
    private var useCliMode = false

    // Current session job
    private var sessionJob: Job? = null

    // Current streaming message ID
    private var streamingMessageId: String? = null

    init {
        checkClaudeInstallation()
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
        useCliMode = cliClient.isClaudeInstalled()
        val apiConfigured = apiClient.isConfigured()
        _isClaudeInstalled.value = useCliMode || apiConfigured
        Log.i(TAG, "Claude CLI installed: $useCliMode, API configured: $apiConfigured")
    }

    /**
     * Send a message to Claude.
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        Log.i(TAG, "Sending message: ${content.take(50)}... (useCliMode=$useCliMode)")

        // Add user message
        val userMessage = Message(
            role = MessageRole.USER,
            content = content
        )

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
                if (useCliMode) {
                    // Use CLI client with streaming mode (--output-format stream-json)
                    cliClient.chatStreaming(content)
                        .collect { event ->
                            handleEvent(event)
                        }
                } else {
                    // Use HTTP API client
                    apiClient.chat(content)
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
                    Log.d(TAG, "MessageEnd: finalizing message with content")
                    finalizeStreamingMessage()
                    _isProcessing.value = false
                }
            }

            is ClaudeEvent.Error -> {
                Log.e(TAG, "Claude error: ${event.message}")
                _error.value = event.message
                // Remove empty streaming message on error
                removeStreamingMessage()
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

        viewModelScope.launch {
            val result = when (event.name.lowercase()) {
                "run_termux" -> executeRunTermuxTool(event.input)
                "bash" -> executeBashTool(event.input)
                "read" -> executeReadTool(event.input)
                "write" -> executeWriteTool(event.input)
                else -> "Tool '${event.name}' not supported"
            }

            // Update tool message to show completed
            _messages.value = _messages.value.map { msg ->
                if (msg.id == toolMessage.id) msg.copy(isStreaming = false)
                else msg
            }

            cliClient.sendToolResponse(event.id, result)
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

            // Try Termux terminal bridge first
            if (TerminalCommandBridge.isAvailable()) {
                Log.i(TAG, "Using Termux terminal for bash command")
                val result = TerminalCommandBridge.executeCommand(
                    command = command,
                    timeout = 60000
                )
                result.toToolResult()
            } else {
                // Fallback to direct execution with Termux shell
                Log.i(TAG, "Termux terminal not available, using direct execution")
                val termuxBin = "/data/data/com.anthroid/files/usr/bin"
                val termuxHome = "/data/data/com.anthroid/files/home"
                val termuxLib = "/data/data/com.anthroid/files/usr/lib"

                val env = arrayOf(
                    "HOME=$termuxHome",
                    "PREFIX=/data/data/com.anthroid/files/usr",
                    "PATH=$termuxBin",
                    "LD_LIBRARY_PATH=$termuxLib",
                    "LANG=en_US.UTF-8",
                    "TERM=xterm-256color"
                )

                val process = Runtime.getRuntime().exec(
                    arrayOf("$termuxBin/bash", "-c", command),
                    env,
                    java.io.File(termuxHome)
                )

                val output = process.inputStream.bufferedReader().readText()
                val errorOutput = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    output.ifEmpty { "(no output)" }
                } else {
                    "Exit code: $exitCode\n$output$errorOutput"
                }
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
     * Cancel the current request.
     */
    fun cancelRequest() {
        Log.i(TAG, "Cancelling request")
        sessionJob?.cancel()
        if (useCliMode) {
            cliClient.cancelCurrentRequest()
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
    val toolName: String? = null,
    val toolInput: String? = null
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
