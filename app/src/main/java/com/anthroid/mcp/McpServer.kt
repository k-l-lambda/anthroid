package com.anthroid.mcp

import android.content.Context
import com.anthroid.accessibility.ScreenAutomationOverlay
import android.util.Log
import com.anthroid.claude.AndroidTools
import com.anthroid.claude.TerminalCommandBridge
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Data class for pending ask_user_question call.
 */
data class McpPendingQuestion(
    val toolId: String,
    val questionsJson: String,
    val deferred: CompletableDeferred<String>
)

/**
 * MCP (Model Context Protocol) Server for exposing Android tools to Claude CLI.
 *
 * Implements the Streamable HTTP transport:
 * - POST /mcp - Handle JSON-RPC requests
 * - Supports: initialize, tools/list, tools/call methods
 */
class McpServer(
    private val context: Context,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(LOCALHOST, port) {

    companion object {
        private const val TAG = "McpServer"
        private const val LOCALHOST = "127.0.0.1"
        const val DEFAULT_PORT = 8765
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val QUESTION_TIMEOUT_MS = 120000L // 2 minutes timeout for user response

        @Volatile
        private var instance: McpServer? = null

        // Callback for tool completion events (toolName, isError, result)
        var onToolComplete: ((toolName: String, isError: Boolean, result: String) -> Unit)? = null

        // Callback for ask_user_question tool - UI should observe this
        var onAskUserQuestion: ((McpPendingQuestion) -> Unit)? = null

        // Current pending question (for answering)
        @Volatile
        var pendingQuestion: McpPendingQuestion? = null

        fun getInstance(context: Context): McpServer {
            return instance ?: synchronized(this) {
                instance ?: McpServer(context.applicationContext).also { instance = it }
            }
        }

        fun startServer(context: Context): Boolean {
            return try {
                val server = getInstance(context)
                if (!server.isAlive) {
                    server.start(SOCKET_READ_TIMEOUT, false)
                    Log.i(TAG, "MCP server started on $LOCALHOST:${server.listeningPort}")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MCP server", e)
                false
            }
        }

        fun stopServer() {
            instance?.stop()
            instance = null
            Log.i(TAG, "MCP server stopped")
        }

        /**
         * Answer the pending question from UI.
         * @param answersJson JSON string with answers
         */
        fun answerQuestion(answersJson: String) {
            val pending = pendingQuestion
            if (pending != null) {
                Log.i(TAG, "Question answered: $answersJson")
                pending.deferred.complete(answersJson)
                pendingQuestion = null
            } else {
                Log.w(TAG, "answerQuestion called but no pending question")
            }
        }

        /**
         * Cancel the pending question (user dismissed).
         */
        fun cancelQuestion() {
            val pending = pendingQuestion
            if (pending != null) {
                Log.i(TAG, "Question cancelled by user")
                pending.deferred.complete("__CANCELLED__")
                pendingQuestion = null
            }
        }
    }

    private val androidTools = AndroidTools(context)
    private val sessions = mutableMapOf<String, SessionInfo>()

    data class SessionInfo(
        val id: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        // Only handle /mcp endpoint
        if (uri != "/mcp") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        return when (method) {
            Method.POST -> handlePost(session)
            Method.GET -> handleGet(session)
            Method.DELETE -> handleDelete(session)
            Method.OPTIONS -> handleOptions()
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        try {
            // Read request body
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val buffer = ByteArray(contentLength)
            session.inputStream.read(buffer, 0, contentLength)
            val body = String(buffer)

            Log.d(TAG, "Request body: $body")

            val request = JSONObject(body)
            val jsonrpcVersion = request.optString("jsonrpc", "2.0")
            val id = request.opt("id")
            val methodName = request.optString("method", "")
            val params = request.optJSONObject("params") ?: JSONObject()

            val result = when (methodName) {
                "initialize" -> handleInitialize(params)
                "initialized" -> handleInitialized()
                "tools/list" -> handleToolsList(params)
                "tools/call" -> handleToolsCall(params)
                "ping" -> handlePing()
                else -> {
                    Log.w(TAG, "Unknown method: $methodName")
                    createErrorResponse(id, -32601, "Method not found: $methodName")
                }
            }

            // Build response
            val response = if (result.has("error")) {
                result
            } else {
                JSONObject().apply {
                    put("jsonrpc", jsonrpcVersion)
                    if (id != null) put("id", id)
                    put("result", result)
                }
            }

            val responseStr = response.toString()
            Log.d(TAG, "Response: $responseStr")

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                responseStr
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling POST", e)
            val error = createErrorResponse(null, -32700, "Parse error: ${e.message}")
            return newFixedLengthResponse(Response.Status.OK, "application/json", error.toString())
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        // GET is for SSE streaming - not implemented for now
        return newFixedLengthResponse(
            Response.Status.METHOD_NOT_ALLOWED,
            MIME_PLAINTEXT,
            "SSE streaming not supported"
        )
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val sessionId = session.headers["mcp-session-id"]
        if (sessionId != null) {
            sessions.remove(sessionId)
            Log.i(TAG, "Session deleted: $sessionId")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
    }

    private fun handleOptions(): Response {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Content-Type, Mcp-Session-Id, MCP-Protocol-Version")
        }
    }

    private fun handleInitialize(params: JSONObject): JSONObject {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = SessionInfo(sessionId)

        Log.i(TAG, "Initialize request, created session: $sessionId")

        return JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject().apply {
                    put("listChanged", false)
                })
            })
            put("serverInfo", JSONObject().apply {
                put("name", "Anthroid MCP Server")
                put("version", "1.0.0")
            })
        }
    }

    private fun handleInitialized(): JSONObject {
        Log.i(TAG, "Initialized notification received")
        return JSONObject() // Empty result for notification
    }

    private fun handlePing(): JSONObject {
        return JSONObject() // Empty result
    }

    private fun handleToolsList(params: JSONObject): JSONObject {
        val tools = JSONArray()

        // Define all Android tools as MCP tools
        val toolDefinitions = listOf(
            ToolDef("launch_app", "Launch an Android app by package name", mapOf(
                "package" to PropDef("string", "Package name of the app to launch", true)
            )),
            ToolDef("open_url", "Open a URL in the default browser", mapOf(
                "url" to PropDef("string", "URL to open", true)
            )),
            ToolDef("show_notification", "Show a notification on the device", mapOf(
                "title" to PropDef("string", "Notification title", true),
                "message" to PropDef("string", "Notification message", true)
            )),
            ToolDef("get_device_info", "Get device information", emptyMap()),
            // App tools
            ToolDef("list_apps", "List installed apps on device", mapOf(
                "filter" to PropDef("string", "Filter: all, system, or user (default: user)", false),
                "limit" to PropDef("integer", "Max apps to return (default: 50)", false)
            )),
            ToolDef("get_app_info", "Get detailed info about an app", mapOf(
                "package" to PropDef("string", "Package name of the app", true)
            )),
            ToolDef("send_intent", "Send an Android Intent action", mapOf(
                "action" to PropDef("string", "Intent action (e.g. android.intent.action.VIEW)", true),
                "data" to PropDef("string", "URI data for the intent", false),
                "type" to PropDef("string", "MIME type", false)
            )),
            // Clipboard tools
            ToolDef("read_clipboard", "Read text from Android clipboard", emptyMap()),
            ToolDef("write_clipboard", "Write text to Android clipboard", mapOf(
                "text" to PropDef("string", "Text to copy to clipboard", true)
            )),
            // Location tools
            ToolDef("geocode", "Convert address to coordinates", mapOf(
                "address" to PropDef("string", "Address to geocode", true)
            )),
            ToolDef("reverse_geocode", "Convert coordinates to address", mapOf(
                "latitude" to PropDef("number", "Latitude", true),
                "longitude" to PropDef("number", "Longitude", true)
            )),
            ToolDef("get_location", "Get current device location", emptyMap()),
            // Calendar tools
            ToolDef("query_calendar", "Query calendar events", mapOf(
                "days" to PropDef("integer", "Number of days to query (default: 7)", false),
                "limit" to PropDef("integer", "Max events to return (default: 20)", false)
            )),
            ToolDef("add_calendar_event", "Add a calendar event", mapOf(
                "title" to PropDef("string", "Event title", true),
                "start" to PropDef("integer", "Start time (epoch ms)", true),
                "end" to PropDef("integer", "End time (epoch ms)", true),
                "description" to PropDef("string", "Event description", false),
                "location" to PropDef("string", "Event location", false)
            )),
            // Media tools
            ToolDef("query_media", "Query media files (images, videos, audio)", mapOf(
                "type" to PropDef("string", "Media type: images, videos, or audio", true),
                "limit" to PropDef("integer", "Max files to return (default: 20)", false)
            )),
            ToolDef("set_app_proxy", "Set up VPN proxy for specific apps", mapOf(
                "proxy_host" to PropDef("string", "Proxy server host", true),
                "proxy_port" to PropDef("integer", "Proxy server port", true),
                "proxy_type" to PropDef("string", "Proxy type: HTTP or SOCKS5", false),
                "proxy_user" to PropDef("string", "Proxy username", false),
                "proxy_pass" to PropDef("string", "Proxy password", false),
                "apps" to PropDef("array", "List of app package names", false)
            )),
            ToolDef("stop_app_proxy", "Stop VPN proxy service", emptyMap()),
            ToolDef("get_proxy_status", "Get current VPN proxy status", emptyMap()),
            ToolDef("get_accessibility_status", "Check if accessibility service is enabled", emptyMap()),
            ToolDef("get_screen_text", "Get all visible text on screen", emptyMap()),
            ToolDef("get_screen_elements", "Get structured UI elements as JSON", mapOf(
                "include_invisible" to PropDef("boolean", "Include invisible elements", false)
            )),
            ToolDef("find_element", "Find element by text content", mapOf(
                "text" to PropDef("string", "Text to search for", true),
                "exact_match" to PropDef("boolean", "Require exact match", false)
            )),
            ToolDef("click_element", "Click element by text", mapOf(
                "text" to PropDef("string", "Text of element to click", true)
            )),
            ToolDef("click_position", "Click at specific coordinates", mapOf(
                "x" to PropDef("number", "X coordinate", true),
                "y" to PropDef("number", "Y coordinate", true)
            )),
            ToolDef("input_text", "Type text into focused input field", mapOf(
                "text" to PropDef("string", "Text to type", true)
            )),
            ToolDef("focus_and_input", "Click element and type text", mapOf(
                "target" to PropDef("string", "Text of element to click", true),
                "text" to PropDef("string", "Text to type", true)
            )),
            ToolDef("swipe", "Perform swipe gesture", mapOf(
                "start_x" to PropDef("number", "Start X coordinate", true),
                "start_y" to PropDef("number", "Start Y coordinate", true),
                "end_x" to PropDef("number", "End X coordinate", true),
                "end_y" to PropDef("number", "End Y coordinate", true),
                "duration_ms" to PropDef("integer", "Swipe duration in ms", false)
            )),
            ToolDef("long_press", "Long press at coordinates", mapOf(
                "x" to PropDef("number", "X coordinate", true),
                "y" to PropDef("number", "Y coordinate", true),
                "duration_ms" to PropDef("integer", "Press duration in ms", false)
            )),
            ToolDef("scroll", "Scroll in a direction", mapOf(
                "direction" to PropDef("string", "Direction: up or down", true)
            )),
            ToolDef("press_back", "Press system back button", emptyMap()),
            ToolDef("press_home", "Press system home button", emptyMap()),
            ToolDef("open_recents", "Open recent apps", emptyMap()),
            ToolDef("open_notifications", "Open notification panel", emptyMap()),
            ToolDef("wait_for_element", "Wait for element to appear", mapOf(
                "text" to PropDef("string", "Text to wait for", true),
                "timeout_ms" to PropDef("integer", "Timeout in milliseconds", false)
            )),
            ToolDef("get_current_app", "Get current foreground app package", emptyMap()),
            // Screen capture tools
            ToolDef("take_screenshot", "Take a screenshot of the current screen. Returns file path.", emptyMap()),
            ToolDef("start_audio_capture", "Start recording system audio (API 29+). Returns file path.", emptyMap()),
            ToolDef("stop_audio_capture", "Stop audio recording and get the recorded file path.", emptyMap()),
            ToolDef("get_capture_status", "Get screen capture service status.", emptyMap()),
            // Terminal tools
            ToolDef("read_terminal", "Read text from the terminal session. Use this to see terminal output.", mapOf(
                "max_lines" to PropDef("integer", "Maximum number of lines to return (0 = unlimited, default: 500)", false),
                "session_id" to PropDef("string", "Target session ID (default: current session)", false)
            ))
        )

        for (tool in toolDefinitions) {
            tools.put(tool.toJson())
        }

        // Add ask_user_question tool with complex nested schema
        tools.put(createAskUserQuestionToolDef())

        Log.i(TAG, "handleToolsList: total tools = ${tools.length()}")

        return JSONObject().apply {
            put("tools", tools)
        }
    }

    /**
     * Create the ask_user_question tool definition with nested schema.
     */
    private fun createAskUserQuestionToolDef(): JSONObject {
        return JSONObject().apply {
            put("name", "ask_user_question")
            put("description", "Ask the user multiple-choice questions to gather preferences, clarify requirements, or get decisions. Each question has a header tag, question text, and 2-4 options with labels and descriptions. Use this when you need user input before proceeding.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("questions", JSONObject().apply {
                        put("type", "array")
                        put("description", "Array of 1-4 questions to ask the user")
                        put("minItems", 1)
                        put("maxItems", 4)
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("question", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "The question to ask")
                                })
                                put("header", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Short label/tag for the question (max 12 chars)")
                                    put("maxLength", 12)
                                })
                                put("options", JSONObject().apply {
                                    put("type", "array")
                                    put("description", "2-4 answer options")
                                    put("minItems", 2)
                                    put("maxItems", 4)
                                    put("items", JSONObject().apply {
                                        put("type", "object")
                                        put("properties", JSONObject().apply {
                                            put("label", JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Short label for the option")
                                            })
                                            put("description", JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Explanation of what this option means")
                                            })
                                        })
                                        put("required", JSONArray().apply {
                                            put("label")
                                            put("description")
                                        })
                                    })
                                })
                                put("multiSelect", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "Allow multiple selections (default: false)")
                                    put("default", false)
                                })
                            })
                            put("required", JSONArray().apply {
                                put("question")
                                put("header")
                                put("options")
                            })
                        })
                    })
                })
                put("required", JSONArray().apply {
                    put("questions")
                })
            })
        }
    }

    private fun handleToolsCall(params: JSONObject): JSONObject {
        val toolName = params.optString("name", "")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        Log.i(TAG, "Tool call: $toolName with args: $arguments")

        if (toolName.isEmpty()) {
            return JSONObject().apply {
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", "Error: tool name is required")
                }))
                put("isError", true)
            }
        }

        // Handle read_terminal - requires TerminalCommandBridge
        if (toolName == "read_terminal") {
            return handleReadTerminal(arguments)
        }

        // Handle ask_user_question specially - requires UI interaction
        if (toolName == "ask_user_question") {
            return handleAskUserQuestion(arguments)
        }

        // Execute tool
        val result = runBlocking {
            try {
                androidTools.executeTool(toolName, arguments.toString())
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

        // Detect errors: "Error:", "Unknown tool:", or JSON with "success": false
        val isError = result.startsWith("Error:") ||
                      result.startsWith("Unknown tool:") ||
                      (result.startsWith("{") && result.contains("\"success\": false"))

        // Notify listener about tool completion
        onToolComplete?.invoke(toolName, isError, result)

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", result)
            }))
            put("isError", isError)
        }
    }

    /**
     * Handle read_terminal tool call.
     * Reads text from terminal session using TerminalCommandBridge.
     */
    private fun handleReadTerminal(arguments: JSONObject): JSONObject {
        val maxLines = arguments.optInt("max_lines", 500)
        val sessionIdParam = arguments.optString("session_id", "")

        if (!TerminalCommandBridge.isAvailable()) {
            return JSONObject().apply {
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", "Error: Terminal bridge not available. Please open the terminal first.")
                }))
                put("isError", true)
            }
        }

        val result = runBlocking {
            try {
                TerminalCommandBridge.readTerminalSession(
                    sessionId = sessionIdParam.takeIf { it.isNotEmpty() },
                    maxLines = maxLines
                )
            } catch (e: Exception) {
                TerminalCommandBridge.CommandResult.error("Error: ${e.message}")
            }
        }

        val isError = !result.success
        val resultText = if (result.success) result.output else "Error: ${result.output}"
        onToolComplete?.invoke("read_terminal", isError, resultText)

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", resultText)
            }))
            put("isError", isError)
        }
    }

    /**
     * Handle ask_user_question tool call.
     * Blocks until user answers or timeout.
     */
    private fun handleAskUserQuestion(arguments: JSONObject): JSONObject {
        val questionsArray = arguments.optJSONArray("questions")
        if (questionsArray == null || questionsArray.length() == 0) {
            return JSONObject().apply {
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", "Error: questions array is required")
                }))
                put("isError", true)
            }
        }

        Log.i(TAG, "ask_user_question: ${questionsArray.length()} questions")

        // Create deferred for blocking until user responds
        val deferred = CompletableDeferred<String>()
        val toolId = UUID.randomUUID().toString()
        val pending = McpPendingQuestion(
            toolId = toolId,
            questionsJson = questionsArray.toString(),
            deferred = deferred
        )

        // Store pending question
        pendingQuestion = pending

        // Notify UI to show question dialog
        onAskUserQuestion?.invoke(pending)

        // Update overlay to show question mode
        try {
            ScreenAutomationOverlay.getInstance(context).setAskingQuestion()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update overlay for question", e)
        }

        // Block and wait for user response
        val result = runBlocking {
            try {
                withTimeout(QUESTION_TIMEOUT_MS) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "ask_user_question timed out")
                pendingQuestion = null
                "__TIMEOUT__"
            }
        }

        // Clear pending question
        pendingQuestion = null

        // Handle special results
        val isError: Boolean
        val resultText: String
        when (result) {
            "__CANCELLED__" -> {
                isError = false
                resultText = "User cancelled the question dialog without answering."
            }
            "__TIMEOUT__" -> {
                isError = true
                resultText = "Error: User did not respond within 2 minutes."
            }
            else -> {
                isError = false
                // Format the result: "User has answered your questions: \"q1\"=\"a1\", ..."
                resultText = formatAnswersResult(questionsArray, result)
            }
        }

        // Notify tool completion
        onToolComplete?.invoke("ask_user_question", isError, resultText)

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", resultText)
            }))
            put("isError", isError)
        }
    }

    /**
     * Format the answers JSON into a readable result string.
     */
    private fun formatAnswersResult(questionsArray: JSONArray, answersJson: String): String {
        return try {
            val answers = JSONObject(answersJson)
            val resultBuilder = StringBuilder("User has answered your questions: ")
            var first = true

            for (i in 0 until questionsArray.length()) {
                val question = questionsArray.getJSONObject(i)
                val questionText = question.optString("question", "Question $i")
                val answer = answers.optString(questionText, "")

                if (!first) resultBuilder.append(", ")
                resultBuilder.append("\"$questionText\"=\"$answer\"")
                first = false
            }

            resultBuilder.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to format answers", e)
            "User answered: $answersJson"
        }
    }

    private fun createErrorResponse(id: Any?, code: Int, message: String): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
    }

    // Helper classes for tool definitions
    data class PropDef(val type: String, val description: String, val required: Boolean)

    data class ToolDef(
        val name: String,
        val description: String,
        val properties: Map<String, PropDef>
    ) {
        fun toJson(): JSONObject {
            val required = JSONArray()
            val props = JSONObject()

            for ((propName, propDef) in properties) {
                props.put(propName, JSONObject().apply {
                    put("type", propDef.type)
                    put("description", propDef.description)
                })
                if (propDef.required) {
                    required.put(propName)
                }
            }

            return JSONObject().apply {
                put("name", name)
                put("description", description)
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    if (required.length() > 0) {
                        put("required", required)
                    }
                })
            }
        }
    }
}
