package com.anthroid.gateway

import android.content.Context
import android.util.Log
import com.anthroid.remote.RemoteSessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages gateway connection lifecycle and session sync for Anthroid.
 *
 * When connected to an OpenClaw gateway, this manager:
 * - Maintains a persistent WebSocket connection
 * - Syncs conversation messages to the "Anthroid" session on the gateway
 * - Forwards gateway events (e.g., notifications) to callbacks
 */
class GatewayManager(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  companion object {
    private const val TAG = "GatewayManager"
    private const val CLIENT_ID = "openclaw-android"
    private const val CLIENT_MODE = "ui"
    private const val CLIENT_VERSION = "0.10.9"
    private const val CLIENT_PLATFORM = "android"
    private const val DEFAULT_ROLE = "operator"
    private val DEFAULT_SCOPES = listOf("operator.read", "operator.write", "operator.admin")
  }

  private val identityStore = DeviceIdentityStore(context)
  private val authStore = DeviceAuthStore(context)

  private val _connectionStatus = MutableStateFlow("Offline")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

  var onNotification: ((title: String, body: String) -> Unit)? = null
  var onChatMessage: ((sessionKey: String, displayName: String?, messageText: String) -> Unit)? = null
  var onRemoteSessionEvent: ((sessionKey: String, role: String, content: String) -> Unit)? = null

  private var session: GatewaySession? = null

  fun connect(host: String, port: Int, token: String? = null, useTls: Boolean = true) {
    disconnect()

    val gatewaySession = GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = authStore,
      onConnected = { serverName, remoteAddress, mainSessionKey ->
        Log.i(TAG, "Connected to gateway: server=$serverName, remote=$remoteAddress, session=$mainSessionKey")
        _connectionStatus.value = "Connected to ${serverName ?: remoteAddress}"
        _isConnected.value = true
      },
      onDisconnected = { message ->
        Log.i(TAG, "Gateway disconnected: $message")
        _connectionStatus.value = message
        _isConnected.value = false
      },
      onEvent = { event, payloadJson ->
        Log.d(TAG, "Gateway event: $event")
        handleGatewayEvent(event, payloadJson)
      },
    )

    val deviceFamily = android.os.Build.MODEL ?: "Android"
    val options = GatewayConnectOptions(
      role = DEFAULT_ROLE,
      scopes = DEFAULT_SCOPES,
      client = GatewayClientInfo(
        id = CLIENT_ID,
        displayName = "Anthroid",
        version = CLIENT_VERSION,
        platform = CLIENT_PLATFORM,
        mode = CLIENT_MODE,
        instanceId = null,
        deviceFamily = deviceFamily,
        modelIdentifier = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
      ),
      userAgent = "Anthroid/$CLIENT_VERSION (Android ${android.os.Build.VERSION.RELEASE})",
    )

    session = gatewaySession
    gatewaySession.connect(host, port, token, options, useTls)
    Log.i(TAG, "Connecting to gateway at $host:$port (tls=$useTls)...")
  }

  fun disconnect() {
    session?.disconnect()
    session = null
    _isConnected.value = false
    _connectionStatus.value = "Offline"
  }

  /**
   * Sync conversation messages to the "Anthroid" session on the gateway.
   * Called after each agent turn completes.
   */
  fun syncMessages(userMessage: String, assistantResponse: String) {
    val gatewaySession = session ?: return
    if (!_isConnected.value) return

    scope.launch {
      try {
        val sessionKey = gatewaySession.currentMainSessionKey() ?: "Anthroid"
        // chat.inject: sessionKey + message (plain string) per call
        listOf(userMessage, assistantResponse).forEach { content ->
          val params = JSONObject().apply {
            put("sessionKey", sessionKey)
            put("message", content)
          }
          gatewaySession.request("chat.inject", params.toString(), timeoutMs = 10_000)
        }
        Log.d(TAG, "Session sync: injected 2 messages to Anthroid session")
      } catch (err: Throwable) {
        Log.w(TAG, "Session sync failed: ${err.message}")
      }
    }
  }

  /**
   * Send a gateway event for the current session state.
   */
  fun sendSessionEvent(event: String, payload: String?) {
    val gatewaySession = session ?: return
    if (!_isConnected.value) return

    scope.launch {
      try {
        gatewaySession.sendNodeEvent(event, payload)
      } catch (err: Throwable) {
        Log.w(TAG, "sendSessionEvent failed: ${err.message}")
      }
    }
  }

  /**
   * List active sessions from the gateway.
   * Falls back to locally observed sessions if the RPC fails (e.g., missing admin scope).
   */
  suspend fun listSessions(): List<RemoteSessionInfo> {
    val gatewaySession = session ?: throw IllegalStateException("Not connected to gateway")
    return try {
      val response = gatewaySession.request("sessions.list", null, timeoutMs = 10_000)
      parseSessionList(response)
    } catch (err: Throwable) {
      Log.w(TAG, "listSessions RPC failed (${err.message}), falling back to observed sessions")
      getObservedSessions()
    }
  }

  private fun parseSessionList(responseJson: String): List<RemoteSessionInfo> {
    if (responseJson.isBlank()) return emptyList()
    val result = mutableListOf<RemoteSessionInfo>()
    try {
      val obj = JSONObject(responseJson)
      val sessions = obj.optJSONArray("sessions") ?: return emptyList()
      for (i in 0 until sessions.length()) {
        val s = sessions.optJSONObject(i) ?: continue
        val displayName = s.optString("displayName", "")
          .takeIf { it.isNotEmpty() }
          ?: s.optString("label", "").takeIf { it.isNotEmpty() }
        val key = s.optString("key", "")
        // Parse agentId from "agent:{agentId}:{sessionName}" format
        val agentId = Regex("^agent:([^:]+):.+$").find(key)?.groupValues?.get(1)
        result.add(RemoteSessionInfo(
          sessionKey = key,
          displayName = displayName,
          lastActivity = s.optLong("updatedAt", s.optLong("lastActivity", 0)),
          status = "active",
          source = RemoteSessionInfo.Source.OPENCLAW,
          agentId = agentId,
        ))
      }
    } catch (err: Throwable) {
      Log.w(TAG, "parseSessionList failed: ${err.message}")
    }
    return result
  }

  /**
   * Inject a user message into a specific gateway session.
   */
  suspend fun injectMessage(sessionKey: String, text: String) {
    val gatewaySession = session ?: throw IllegalStateException("Not connected to gateway")
    val params = JSONObject().apply {
      put("sessionKey", sessionKey)
      put("message", text)
    }
    gatewaySession.request("chat.inject", params.toString(), timeoutMs = 10_000)
    Log.d(TAG, "Injected message to session $sessionKey: ${text.take(50)}")
  }

  /**
   * Send a user message to a specific gateway session, triggering agent processing.
   * Unlike injectMessage (chat.inject), this uses chat.send which delivers the message
   * as a user turn and causes the remote agent to process and respond.
   */
  suspend fun sendChatMessage(sessionKey: String, text: String) {
    val gatewaySession = session ?: throw IllegalStateException("Not connected to gateway")
    val idempotencyKey = "android-${System.currentTimeMillis()}"
    val params = JSONObject().apply {
      put("sessionKey", sessionKey)
      put("message", text)
      put("idempotencyKey", idempotencyKey)
    }
    gatewaySession.request("chat.send", params.toString(), timeoutMs = 30_000)
    Log.d(TAG, "Sent user message to session $sessionKey: ${text.take(50)}")
  }

  /**
   * Load recent conversation history for a session via sessions.preview RPC.
   * Returns list of (role, text) pairs ordered oldest-first, or empty list on failure.
   */
  suspend fun loadSessionHistory(sessionKey: String, limit: Int = 40): List<Pair<String, String>> {
    val gs = session ?: return emptyList()
    return try {
      val params = JSONObject().apply {
        put("keys", JSONArray().apply { put(sessionKey) })
        put("limit", limit)
        put("maxChars", 2000)
      }
      val response = gs.request("sessions.preview", params.toString(), timeoutMs = 10_000)
      if (response == null) return emptyList()
      val obj = JSONObject(response)
      val previews = obj.optJSONArray("previews") ?: return emptyList()
      val preview = previews.optJSONObject(0) ?: return emptyList()
      val items = preview.optJSONArray("items") ?: return emptyList()
      val result = mutableListOf<Pair<String, String>>()
      for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val role = item.optString("role", "")
        val text = item.optString("text", "").trim()
        if (text.isNotEmpty() && (role == "user" || role == "assistant")) {
          result.add(role to text)
        }
      }
      Log.d(TAG, "Loaded ${result.size} history items for session $sessionKey")
      result
    } catch (e: Exception) {
      Log.w(TAG, "loadSessionHistory failed for $sessionKey: ${e.message}")
      emptyList()
    }
  }

  // Track observed sessions from gateway events for fallback listing
  private data class ObservedSession(val sessionKey: String, var lastActivity: Long)
  private val observedSessions = mutableMapOf<String, ObservedSession>()

  private fun trackObservedSession(sessionKey: String) {
    if (sessionKey.isNotEmpty() && sessionKey != "gateway") {
      observedSessions[sessionKey] = ObservedSession(sessionKey, System.currentTimeMillis())
    }
  }

  fun getObservedSessions(): List<RemoteSessionInfo> {
    return observedSessions.values
      .sortedByDescending { it.lastActivity }
      .map { s ->
        RemoteSessionInfo(
          sessionKey = s.sessionKey,
          displayName = s.sessionKey.substringAfterLast(":").takeIf { it != s.sessionKey },
          lastActivity = s.lastActivity,
          status = "observed",
          source = RemoteSessionInfo.Source.OPENCLAW,
        )
      }
  }

  // Buffer assistant text per runId for agent events
  private val agentTextBuffers = mutableMapOf<String, StringBuilder>()
  private val agentSessionKeys = mutableMapOf<String, String>()
  private val processedAgentRuns = mutableSetOf<String>()

  private fun handleGatewayEvent(event: String, payloadJson: String?) {
    when (event) {
      "notification.push" -> {
        try {
          val obj = if (payloadJson != null) JSONObject(payloadJson) else null
          val title = obj?.optString("title", "Anthroid") ?: "Anthroid"
          val body = obj?.optString("body", "") ?: ""
          onNotification?.invoke(title, body)
        } catch (err: Throwable) {
          Log.w(TAG, "Failed to parse notification: ${err.message}")
        }
      }
      "chat" -> {
        try {
          val obj = if (payloadJson != null) JSONObject(payloadJson) else return
          val sessionKey = obj.optString("sessionKey", "").takeIf { it.isNotEmpty() } ?: return
          trackObservedSession(sessionKey)
          val state = obj.optString("state", "")
          // Only notify on final messages, not streaming deltas
          if (state != "final") return
          val msgObj = obj.optJSONObject("message") ?: return
          val role = msgObj.optString("role", "")
          if (role != "assistant") return
          // Extract text from content array
          val contentArray = msgObj.optJSONArray("content")
          val textParts = mutableListOf<String>()
          if (contentArray != null) {
            for (i in 0 until contentArray.length()) {
              val block = contentArray.optJSONObject(i) ?: continue
              if (block.optString("type") == "text") {
                textParts.add(block.optString("text", ""))
              }
            }
          } else {
            // content might be a plain string
            val contentStr = msgObj.optString("content", "")
            if (contentStr.isNotEmpty()) textParts.add(contentStr)
          }
          val messageText = textParts.joinToString("\n").trim()
          if (messageText.isEmpty()) return
          Log.i(TAG, "Chat message: session=$sessionKey, len=${messageText.length}")
          onChatMessage?.invoke(sessionKey, null, messageText)
          onRemoteSessionEvent?.invoke(sessionKey, "assistant", messageText)
        } catch (err: Throwable) {
          Log.w(TAG, "Failed to parse chat event: ${err.message}")
        }
      }
      "agent" -> {
        try {
          val obj = if (payloadJson != null) JSONObject(payloadJson) else return
          val runId = obj.optString("runId", "").takeIf { it.isNotEmpty() } ?: return
          val stream = obj.optString("stream", "")
          // Try both "sessionKey" and nested "sessionId" for session tracking
          val sessionKey = (obj.optString("sessionKey", "").takeIf { it.isNotEmpty() }
            ?: obj.optString("sessionId", "").takeIf { it.isNotEmpty() })
          if (sessionKey != null) {
            agentSessionKeys[runId] = sessionKey
            trackObservedSession(sessionKey)
          }
          val data = obj.optJSONObject("data")
          when (stream) {
            "assistant" -> {
              val text = data?.optString("text", "") ?: ""
              if (text.isNotEmpty()) {
                agentTextBuffers.getOrPut(runId) { StringBuilder() }.append(text)
              }
            }
            "lifecycle" -> {
              val phase = data?.optString("phase", "") ?: ""
              if (phase == "end" || phase == "error") {
                // Guard against duplicate lifecycle end events for the same runId
                if (!processedAgentRuns.add(runId)) return
                val buffered = agentTextBuffers.remove(runId)?.toString()?.trim()
                val effectiveSessionKey = agentSessionKeys.remove(runId) ?: "gateway"
                if (!buffered.isNullOrEmpty()) {
                  Log.i(TAG, "Agent run complete: runId=$runId, session=$effectiveSessionKey, len=${buffered.length}")
                  trackObservedSession(effectiveSessionKey)
                  // Use fixed key so all agent messages share one notification
                  onChatMessage?.invoke("gateway", "Gateway", buffered)
                  onRemoteSessionEvent?.invoke(effectiveSessionKey, "assistant", buffered)
                }
                // Keep processedAgentRuns bounded (max 100 entries)
                if (processedAgentRuns.size > 100) {
                  val iterator = processedAgentRuns.iterator()
                  repeat(50) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
                }
              }
            }
          }
        } catch (err: Throwable) {
          Log.w(TAG, "Failed to parse agent event: ${err.message}")
        }
      }
      else -> {
        // Track sessions from any event that has sessionKey
        if (payloadJson != null) {
          try {
            val obj = JSONObject(payloadJson)
            val sessionKey = obj.optString("sessionKey", "").takeIf { it.isNotEmpty() }
            if (sessionKey != null) {
              trackObservedSession(sessionKey)
            }
          } catch (_: Throwable) {}
        }
        Log.d(TAG, "Unhandled gateway event: $event")
      }
    }
  }
}
