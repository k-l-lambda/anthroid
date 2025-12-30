package com.anthroid.claude

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Debug receiver for sending messages and configuring API via adb.
 *
 * Usage:
 *   # Send message:
 *   adb shell am broadcast -a com.anthroid.DEBUG_SEND_MESSAGE \
 *       --es message "your message here" \
 *       -n com.anthroid/.claude.DebugReceiver -f 0x01000000
 *
 *   # Configure API key:
 *   adb shell am broadcast -a com.anthroid.DEBUG_CONFIG_API \
 *       --es api_key "sk-ant-..." \
 *       -n com.anthroid/.claude.DebugReceiver -f 0x01000000
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugReceiver"
        const val ACTION_SEND_MESSAGE = "com.anthroid.DEBUG_SEND_MESSAGE"
        const val ACTION_CONFIG_API = "com.anthroid.DEBUG_CONFIG_API"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_MODEL = "model"

        // Global event flow for debug messages
        private val _debugMessageFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val debugMessageFlow = _debugMessageFlow.asSharedFlow()

        // Global event flow for API config changes
        private val _apiConfigFlow = MutableSharedFlow<ApiConfig>(extraBufferCapacity = 1)
        val apiConfigFlow = _apiConfigFlow.asSharedFlow()

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
}
