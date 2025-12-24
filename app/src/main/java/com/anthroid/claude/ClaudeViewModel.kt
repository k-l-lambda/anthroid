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
                _currentResponse.value += event.content
                updateStreamingMessage(_currentResponse.value)
            }

            is ClaudeEvent.ToolUse -> {
                Log.i(TAG, "Tool use: ${event.name}")
                handleToolUse(event)
            }

            is ClaudeEvent.MessageEnd -> {
                // Finalize the streaming message
                finalizeStreamingMessage()
                _isProcessing.value = false
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
        val msgId = streamingMessageId ?: return
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) {
                msg.copy(content = content)
            } else {
                msg
            }
        }
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
        viewModelScope.launch {
            val result = when (event.name) {
                "bash" -> executeBashTool(event.input)
                "read" -> executeReadTool(event.input)
                "write" -> executeWriteTool(event.input)
                else -> "Tool '${event.name}' not supported"
            }
            cliClient.sendToolResponse(event.id, result)
        }
    }

    /**
     * Execute bash command tool.
     */
    private fun executeBashTool(input: String): String {
        return try {
            val command = org.json.JSONObject(input).optString("command", "")
            Log.i(TAG, "Executing bash: $command")

            val process = ProcessBuilder("sh", "-c", command)
                .directory(java.io.File("/data/data/com.anthroid/files/home"))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) output else "Exit code: $exitCode\n$output"
        } catch (e: Exception) {
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
    val isStreaming: Boolean = false
)

/**
 * Message role in the conversation.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
