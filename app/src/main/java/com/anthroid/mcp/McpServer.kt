package com.anthroid.mcp

import android.content.Context
import android.util.Log
import com.anthroid.claude.AndroidTools
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

        @Volatile
        private var instance: McpServer? = null

        // Callback for tool completion events
        var onToolComplete: ((toolName: String, isError: Boolean) -> Unit)? = null

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
            ToolDef("get_capture_status", "Get screen capture service status.", emptyMap())
        )

        for (tool in toolDefinitions) {
            tools.put(tool.toJson())
        }

        return JSONObject().apply {
            put("tools", tools)
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
        onToolComplete?.invoke(toolName, isError)

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", result)
            }))
            put("isError", isError)
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
