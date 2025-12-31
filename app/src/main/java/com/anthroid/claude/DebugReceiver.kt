package com.anthroid.claude

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug receiver for sending messages and configuring API via adb.
 *
 * Usage:
 *   # Send message:
 *   adb shell am broadcast -a com.anthroid.DEBUG_SEND_MESSAGE  *       --es message "your message here" -p com.anthroid
 *
 *   # Configure API key:
 *   adb shell am broadcast -a com.anthroid.DEBUG_CONFIG_API  *       --es api_key "sk-ant-..." -p com.anthroid
 *
 *   # Read conversation (writes to /sdcard/anthroid_conversation.txt):
 *   adb shell am broadcast -a com.anthroid.DEBUG_READ_CONVERSATION -p com.anthroid
 *   adb shell cat /sdcard/anthroid_conversation.txt
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugReceiver"
        const val ACTION_SEND_MESSAGE = "com.anthroid.DEBUG_SEND_MESSAGE"
        const val ACTION_CONFIG_API = "com.anthroid.DEBUG_CONFIG_API"
        const val ACTION_READ_CONVERSATION = "com.anthroid.DEBUG_READ_CONVERSATION"
        const val ACTION_TOOL_CALL = "com.anthroid.TOOL_CALL"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_INPUT = "input"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_MODEL = "model"

        // Global event flow for debug messages (replay=1 ensures late collectors get the message)
        private val _debugMessageFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
        val debugMessageFlow = _debugMessageFlow.asSharedFlow()

        // Global event flow for API config changes
        private val _apiConfigFlow = MutableSharedFlow<ApiConfig>(replay = 1, extraBufferCapacity = 1)
        val apiConfigFlow = _apiConfigFlow.asSharedFlow()

        // Global event flow for read conversation requests
        private val _readConversationFlow = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
        val readConversationFlow = _readConversationFlow.asSharedFlow()

        // Store last conversation for writing to file
        @Volatile
        var lastConversation: String = ""

        /**
         * Emit a debug message (called from receiver).
         */
        fun emitMessage(message: String) {
            _debugMessageFlow.tryEmit(message)
        }

        /**
         * Emit API config change (called from receiver).
         */
        fun emitApiConfig(config: ApiConfig) {
            _apiConfigFlow.tryEmit(config)
        }

        /**
         * Request to read conversation (called from receiver).
         */
        fun requestReadConversation() {
            _readConversationFlow.tryEmit(Unit)
        }

        /**
         * Update conversation content (called from ViewModel).
         */
        fun updateConversation(conversation: String) {
            lastConversation = conversation
        }
    }

    data class ApiConfig(
        val apiKey: String,
        val baseUrl: String?,
        val model: String?
    )

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SEND_MESSAGE -> handleSendMessage(intent)
            ACTION_CONFIG_API -> handleConfigApi(context, intent)
            ACTION_READ_CONVERSATION -> handleReadConversation(context)
            ACTION_TOOL_CALL -> handleToolCall(context, intent)
        }
    }

    private fun handleSendMessage(intent: Intent) {
        val message = intent.getStringExtra(EXTRA_MESSAGE)
        if (message.isNullOrBlank()) {
            Log.w(TAG, "Received empty message")
            return
        }

        Log.i(TAG, "Received debug message: ${message.take(50)}...")
        emitMessage(message)
    }

    private fun handleConfigApi(context: Context, intent: Intent) {
        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Received empty API key")
            return
        }

        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
        val model = intent.getStringExtra(EXTRA_MODEL)

        // Save to SharedPreferences
        val prefs = context.getSharedPreferences("claude_config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("api_key", apiKey)
            baseUrl?.let { putString("base_url", it) }
            model?.let { putString("model", it) }
            apply()
        }

        Log.i(TAG, "API config saved: key=${apiKey.take(10)}..., baseUrl=$baseUrl, model=$model")

        // Emit config change event
        emitApiConfig(ApiConfig(apiKey, baseUrl, model))
    }

    private fun handleReadConversation(context: Context) {
        Log.i(TAG, "Reading conversation...")

        // Request ViewModel to update conversation
        requestReadConversation()

        // Give ViewModel time to update, then write to file
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val outputFile = File(Environment.getExternalStorageDirectory(), "anthroid_conversation.txt")
                outputFile.writeText(lastConversation)
                Log.i(TAG, "Conversation written to ${outputFile.absolutePath} (${lastConversation.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write conversation", e)
            }
        }, 100)
    }

    private fun handleToolCall(context: Context, intent: Intent) {
        val tool = intent.getStringExtra(EXTRA_TOOL)
        val input = intent.getStringExtra(EXTRA_INPUT) ?: "{}"
        
        if (tool.isNullOrBlank()) {
            Log.w(TAG, "Received empty tool name")
            writeToolResult("Error: tool name is required")
            return
        }

        Log.i(TAG, "Tool call: tool=$tool, input=${input.take(100)}")

        // Execute tool in coroutine
        val androidTools = AndroidTools(context)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = androidTools.executeTool(tool, input)
                Log.i(TAG, "Tool result: ${result.take(100)}")
                writeToolResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution failed", e)
                writeToolResult("Error: ${e.message}")
            }
        }
    }

    private fun writeToolResult(result: String) {
        try {
            val outputFile = File(Environment.getExternalStorageDirectory(), "anthroid_tool_result.txt")
            outputFile.writeText(result)
            Log.i(TAG, "Tool result written to ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write tool result", e)
        }
    }
}
