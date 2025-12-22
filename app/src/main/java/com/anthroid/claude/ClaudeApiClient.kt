package com.anthroid.claude

import android.content.Context
import android.util.Log
import com.anthroid.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP-based Claude API client.
 * Fallback when Claude CLI is not available.
 */
class ClaudeApiClient(private val context: Context) {

    companion object {
        private const val TAG = "ClaudeApiClient"
    }

    private var apiKey: String = ""
    private var baseUrl: String = BuildConfig.CLAUDE_API_BASE_URL
    private var model: String = BuildConfig.CLAUDE_API_MODEL
    private val conversationHistory = mutableListOf<JSONObject>()

    /**
     * Configure the API client.
     */
    fun configure(apiKey: String, baseUrl: String = BuildConfig.CLAUDE_API_BASE_URL, model: String = BuildConfig.CLAUDE_API_MODEL) {
        this.apiKey = apiKey
        this.baseUrl = baseUrl.trimEnd('/')
        this.model = model
        Log.i(TAG, "Configured API client: baseUrl=$baseUrl, model=$model")
    }

    /**
     * Check if the client is configured with API key.
     */
    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Send a message and receive streaming response.
     */
    fun chat(userMessage: String): Flow<ClaudeEvent> = channelFlow {
        if (!isConfigured()) {
            send(ClaudeEvent.Error("API key not configured"))
            return@channelFlow
        }

        withContext(Dispatchers.IO) {
            try {
                // Add user message to history
                conversationHistory.add(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })

                val url = URL("$baseUrl/v1/messages")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                connection.doOutput = true
                connection.doInput = true

                val messagesArray = JSONArray()
                for (msg in conversationHistory) {
                    messagesArray.put(msg)
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 4096)
                    put("stream", true)
                    put("messages", messagesArray)
                }

                Log.d(TAG, "Sending request to $url")
                connection.outputStream.write(requestBody.toString().toByteArray())
                connection.outputStream.flush()

                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                if (responseCode != 200) {
                    val errorStream = connection.errorStream ?: connection.inputStream
                    val error = errorStream.bufferedReader().readText()
                    Log.e(TAG, "API error: $error")
                    send(ClaudeEvent.Error("API error ($responseCode): $error"))
                    return@withContext
                }

                // Process SSE stream
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val responseBuilder = StringBuilder()
                var line: String?

                send(ClaudeEvent.MessageStart(""))

                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.substring(6)
                        if (data == "[DONE]") break

                        try {
                            val event = JSONObject(data)
                            val type = event.optString("type")

                            when (type) {
                                "content_block_delta" -> {
                                    val delta = event.optJSONObject("delta")
                                    val text = delta?.optString("text", "") ?: ""
                                    if (text.isNotEmpty()) {
                                        responseBuilder.append(text)
                                        send(ClaudeEvent.TextDelta(text))
                                    }
                                }
                                "message_stop" -> {
                                    // Add assistant response to history
                                    conversationHistory.add(JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", responseBuilder.toString())
                                    })
                                    send(ClaudeEvent.MessageEnd)
                                }
                                "error" -> {
                                    val errorMsg = event.optJSONObject("error")?.optString("message", "Unknown error")
                                    send(ClaudeEvent.Error(errorMsg ?: "Unknown error"))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse SSE event: $data", e)
                        }
                    }
                }

                reader.close()
                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Chat failed", e)
                send(ClaudeEvent.Error("Request failed: ${e.message}"))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Clear conversation history.
     */
    fun clearHistory() {
        conversationHistory.clear()
    }
}
