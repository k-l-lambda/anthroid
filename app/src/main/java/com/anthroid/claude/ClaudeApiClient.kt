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

    private var pendingToolId: String? = null
    private var pendingToolName: String? = null
    private val pendingToolInput = StringBuilder()

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
                    put("tools", getToolDefinitions())
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
                                "content_block_start" -> {
                                    val contentBlock = event.optJSONObject("content_block")
                                    if (contentBlock?.optString("type") == "tool_use") {
                                        pendingToolId = contentBlock.optString("id")
                                        pendingToolName = contentBlock.optString("name")
                                        pendingToolInput.clear()
                                        Log.i(TAG, "Tool use started: id=$pendingToolId, name=$pendingToolName")
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = event.optJSONObject("delta")
                                    val deltaType = delta?.optString("type", "") ?: ""

                                    if (deltaType == "input_json_delta") {
                                        // Tool input delta
                                        val partialJson = delta.optString("partial_json", "")
                                        pendingToolInput.append(partialJson)
                                    } else {
                                        // Text delta
                                        val text = delta?.optString("text", "") ?: ""
                                        if (text.isNotEmpty()) {
                                            responseBuilder.append(text)
                                            send(ClaudeEvent.TextDelta(text))
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    if (pendingToolId != null && pendingToolName != null) {
                                        val toolId = pendingToolId!!
                                        val toolName = pendingToolName!!
                                        val toolInput = if (pendingToolInput.isNotEmpty()) pendingToolInput.toString() else "{}"
                                        Log.i(TAG, "Tool use complete: ")
                                        pendingToolId = null
                                        pendingToolName = null
                                        pendingToolInput.clear()
                                        send(ClaudeEvent.ToolUse(toolId, toolName, toolInput))
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

    /**
     * Send tool result and get Claude's response.
     */
    fun sendToolResult(toolUseId: String, toolName: String, result: String): Flow<ClaudeEvent> = channelFlow {
        if (!isConfigured()) {
            send(ClaudeEvent.Error("API key not configured"))
            return@channelFlow
        }

        withContext(Dispatchers.IO) {
            try {
                // Add assistant message with tool_use
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", toolUseId)
                            put("name", toolName)
                            put("input", JSONObject())
                        })
                    })
                })

                // Add tool result message
                conversationHistory.add(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", toolUseId)
                            put("content", result)
                        })
                    })
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
                    put("tools", getToolDefinitions())
                }

                Log.d(TAG, "Sending tool result for $toolName")
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

                // Process SSE stream (same as chat())
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
                                "content_block_start" -> {
                                    val contentBlock = event.optJSONObject("content_block")
                                    if (contentBlock?.optString("type") == "tool_use") {
                                        pendingToolId = contentBlock.optString("id")
                                        pendingToolName = contentBlock.optString("name")
                                        pendingToolInput.clear()
                                        Log.i(TAG, "Tool use started: id=$pendingToolId, name=$pendingToolName")
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = event.optJSONObject("delta")
                                    val deltaType = delta?.optString("type", "") ?: ""

                                    if (deltaType == "input_json_delta") {
                                        val partialJson = delta.optString("partial_json", "")
                                        pendingToolInput.append(partialJson)
                                    } else {
                                        val text = delta?.optString("text", "") ?: ""
                                        if (text.isNotEmpty()) {
                                            responseBuilder.append(text)
                                            send(ClaudeEvent.TextDelta(text))
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    if (pendingToolId != null && pendingToolName != null) {
                                        val tid = pendingToolId!!
                                        val tname = pendingToolName!!
                                        val tinput = if (pendingToolInput.isNotEmpty()) pendingToolInput.toString() else "{}"
                                        Log.i(TAG, "Tool use complete: ")
                                        pendingToolId = null
                                        pendingToolName = null
                                        pendingToolInput.clear()
                                        send(ClaudeEvent.ToolUse(tid, tname, tinput))
                                    }
                                }
                                "message_stop" -> {
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
                Log.e(TAG, "Send tool result failed", e)
                send(ClaudeEvent.Error("Request failed: ${e.message}"))
            }
        }
    }.flowOn(Dispatchers.IO)
    private fun getToolDefinitions(): JSONArray {
        val tools = JSONArray()
        tools.put(createTool("show_notification", "Show a notification on the Android device", mapOf("title" to "string:Notification title", "message" to "string:Notification message content"), listOf("message")))
        tools.put(createTool("open_url", "Open a URL in the browser", mapOf("url" to "string:URL to open"), listOf("url")))
        tools.put(createTool("launch_app", "Launch an Android app by package name", mapOf("package" to "string:Package name of the app"), listOf("package")))
        tools.put(createTool("list_apps", "List installed apps on the device", mapOf("filter" to "string:Filter apps: user, system, or all", "limit" to "integer:Maximum apps to return")))
        tools.put(createTool("get_app_info", "Get detailed information about an installed app", mapOf("package" to "string:Package name of the app"), listOf("package")))
        tools.put(createTool("geocode", "Convert an address to geographic coordinates", mapOf("address" to "string:Address to geocode"), listOf("address")))
        tools.put(createTool("reverse_geocode", "Convert coordinates to an address", mapOf("latitude" to "number:Latitude", "longitude" to "number:Longitude"), listOf("latitude", "longitude")))
        tools.put(createTool("get_location", "Get the device current location", mapOf("provider" to "string:Location provider: network or gps")))
        tools.put(createTool("query_calendar", "Query calendar events", mapOf("days_ahead" to "integer:Days ahead to query", "limit" to "integer:Max events to return")))
        tools.put(createTool("add_calendar_event", "Add a calendar event", mapOf("title" to "string:Event title", "start_time" to "integer:Start time in ms", "end_time" to "integer:End time in ms"), listOf("title", "start_time", "end_time")))
        tools.put(createTool("query_media", "Query media files on the device", mapOf("type" to "string:Media type: images, videos, audio", "limit" to "integer:Max items to return")))
        tools.put(createTool("send_intent", "Send a generic Android intent", mapOf("action" to "string:Intent action", "data" to "string:Intent data URI", "type" to "string:MIME type"), listOf("action")))
        tools.put(createTool("bash", "Execute a shell command in Termux", mapOf("command" to "string:Shell command to execute"), listOf("command")))
        tools.put(createTool("run_termux", "Execute a command in the visible Termux terminal", mapOf("command" to "string:Command to execute", "session_id" to "string:Terminal session ID", "timeout" to "integer:Timeout in ms"), listOf("command")))
        tools.put(createTool("read_terminal", "Read the full text content from the terminal session", mapOf("session_id" to "string:Terminal session ID", "max_lines" to "integer:Maximum number of lines to return (0 for all)")))
        tools.put(createTool("read_clipboard", "Read text from the device clipboard", mapOf()))
        tools.put(createTool("write_clipboard", "Write text to the device clipboard", mapOf("text" to "string:Text to copy to clipboard"), listOf("text")))
        return tools
    }

    private fun createTool(name: String, description: String, properties: Map<String, String>, required: List<String> = emptyList()): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    properties.forEach { (propName, typeDesc) ->
                        val parts = typeDesc.split(":", limit = 2)
                        put(propName, JSONObject().apply {
                            put("type", parts[0])
                            if (parts.size > 1) put("description", parts[1])
                        })
                    }
                })
                if (required.isNotEmpty()) {
                    put("required", JSONArray().apply { required.forEach { put(it) } })
                }
            })
        }
    }
}