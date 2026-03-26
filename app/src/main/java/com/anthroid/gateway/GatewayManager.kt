package com.anthroid.gateway

import android.content.Context
import android.util.Log
import com.anthroid.remote.RemoteSessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private const val CLIENT_MODE = "anthroid"
    private const val CLIENT_VERSION = "0.10.9"
    private const val CLIENT_PLATFORM = "android"
    private const val DEFAULT_ROLE = "operator"
    // operator.admin is NOT included by default (least privilege).
    // sessions.list RPC falls back to observed sessions when admin scope is unavailable.
    private val DEFAULT_SCOPES = listOf("operator.read", "operator.write")
  }

  private val identityStore = DeviceIdentityStore(context)
  private val authStore = DeviceAuthStore(context)

  private val _connectionStatus = MutableStateFlow("Offline")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

  var onNotification: ((title: String, body: String) -> Unit)? = null
  /** isStreaming=true for agent run buffered output; false for chat final messages */
  var onChatMessage: ((sessionKey: String, displayName: String?, messageText: String, isStreaming: Boolean) -> Unit)? = null

  data class RemoteSessionEvent(val sessionKey: String, val role: String, val content: String)
  private val _remoteSessionEventFlow = MutableSharedFlow<RemoteSessionEvent>(
    extraBufferCapacity = 256,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )
  val remoteSessionEventFlow: SharedFlow<RemoteSessionEvent> = _remoteSessionEventFlow.asSharedFlow()

  // Track recently seen message IDs (from WS events) for dedup against drainPending
  private val recentMessageIds = LinkedHashSet<String>()
  private val MAX_RECENT_IDS = 10

  private fun trackMessageId(id: String) {
    recentMessageIds.add(id)
    while (recentMessageIds.size > MAX_RECENT_IDS) {
      recentMessageIds.iterator().let { it.next(); it.remove() }
    }
  }

  fun isMessageSeen(id: String): Boolean = recentMessageIds.contains(id)

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
        // Track main session BEFORE setting isConnected=true so drainPending sees it
        if (!mainSessionKey.isNullOrBlank()) trackObservedSession(mainSessionKey)
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
        // Cache session label for notification channel derivation
        val rawLabel = s.optString("label", "").takeIf { it.isNotEmpty() }
        if (key.isNotEmpty() && rawLabel != null) setSessionLabel(key, rawLabel)
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

  /** Returns observed session keys for polling. */
  fun getObservedSessionKeys(): List<String> = observedSessions.keys.toList()

  /** Emits a synthetic RemoteSessionEvent (used by pending message delivery). */
  fun emitRemoteSessionEvent(event: RemoteSessionEvent) {
    _remoteSessionEventFlow.tryEmit(event)
  }

  /**
   * Drain pending messages queued by the gateway for this session.
   * Called by the polling service every ~60s to receive timed/scheduled messages.
   * Returns list of message strings (content), or empty list on failure.
   */
  data class DrainedMessage(val sessionKey: String, val content: String, val messageId: String)

  /** Drain ALL pending messages across all sessions in one call. */
  suspend fun drainAllPendingMessages(): List<DrainedMessage> {
    val gs = session
    if (gs == null) {
      Log.w(TAG, "drainAllPendingMessages: session is null")
      return emptyList()
    }
    return try {
      val response = gs.request("session.drainAllPending", "{}", timeoutMs = 10_000)
      Log.i(TAG, "drainAllPending response: ${response?.take(200)}")
      val obj = JSONObject(response)
      val messages = obj.optJSONArray("messages") ?: return emptyList()
      val result = mutableListOf<DrainedMessage>()
      var skipped = 0
      for (i in 0 until messages.length()) {
        val msg = messages.optJSONObject(i) ?: continue
        val messageId = msg.optString("messageId", "")
        if (messageId.isNotEmpty() && isMessageSeen(messageId)) {
          skipped++
          continue
        }
        val content = msg.optString("content", "").trim()
        val key = msg.optString("sessionKey", "")
        if (content.isNotEmpty() && key.isNotEmpty()) {
          result.add(DrainedMessage(key, content, messageId))
        }
      }
      if (result.isNotEmpty() || skipped > 0) Log.i(TAG, "DrainAll: ${result.size} messages (skipped $skipped duplicates)")
      result
    } catch (err: Throwable) {
      Log.w(TAG, "drainAllPendingMessages failed: ${err.message}")
      emptyList()
    }
  }

  suspend fun drainPendingMessages(sessionKey: String): List<String> {
    val gs = session ?: return emptyList()
    return try {
      val params = JSONObject().apply { put("key", sessionKey) }
      val response = gs.request("session.drainPending", params.toString(), timeoutMs = 10_000)
      val obj = JSONObject(response)
      val messages = obj.optJSONArray("messages") ?: return emptyList()
      val result = mutableListOf<String>()
      var skipped = 0
      for (i in 0 until messages.length()) {
        val msg = messages.optJSONObject(i) ?: continue
        val messageId = msg.optString("messageId", "")
        // Skip messages already received via WS real-time events
        if (messageId.isNotEmpty() && isMessageSeen(messageId)) {
          skipped++
          continue
        }
        val content = msg.optString("content", "").trim()
        if (content.isNotEmpty()) result.add(content)
      }
      if (result.isNotEmpty() || skipped > 0) Log.i(TAG, "Drained ${result.size} pending messages for $sessionKey (skipped $skipped duplicates)")
      result
    } catch (err: Throwable) {
      Log.d(TAG, "drainPendingMessages failed (no pending or not connected): ${err.message}")
      emptyList()
    }
  }

  /**
   * Fetch agent profile and memory content from the gateway.
   * Returns a data class with agent identity and memory, or null on failure.
   */
  suspend fun getAgentProfile(agentId: String? = null): AgentProfile? {
    val gs = session ?: return null
    return try {
      val params = JSONObject()
      if (!agentId.isNullOrBlank()) params.put("agentId", agentId)
      val response = gs.request("agent.getProfile", params.toString(), timeoutMs = 10_000)
      val obj = JSONObject(response)
      AgentProfile(
        agentId = obj.optString("agentId", ""),
        name = obj.optString("name", null),
        emoji = obj.optString("emoji", null),
        agentsContent = obj.optString("agentsContent", null),
        memoryContent = obj.optString("memoryContent", null),
        userContent = obj.optString("userContent", null),
        identityContent = obj.optString("identityContent", null),
        soulContent = obj.optString("soulContent", null)
      )
    } catch (err: Throwable) {
      Log.d(TAG, "getAgentProfile failed: ${err.message}")
      null
    }
  }

  data class AgentProfile(
    val agentId: String,
    val name: String?,
    val emoji: String?,
    val agentsContent: String?,
    val memoryContent: String?,
    val userContent: String?,
    val identityContent: String?,
    val soulContent: String?
  )

  /**
   * Get memory patch from gateway since a given timestamp.
   * Returns mode ("full"|"patch"), content, and latest timestamp.
   */
  suspend fun getMemoryPatch(sinceTimestamp: Long? = null): MemoryPatchResult? {
    val gs = session ?: return null
    return try {
      // Never send sinceTimestamp — Android has no git binary to apply patches.
      // Without sinceTimestamp, server always returns mode="full" with all files.
      val params = JSONObject()
      val response = gs.request("agent.getMemoryPatch", params.toString(), timeoutMs = 15_000)
      val obj = JSONObject(response)
      val mode = obj.optString("mode", "full")
      val latestTimestamp = obj.optLong("latestTimestamp", System.currentTimeMillis())
      if (mode == "patch") {
        MemoryPatchResult(mode = "patch", patch = obj.optString("patch", ""), latestTimestamp = latestTimestamp)
      } else {
        val filesObj = obj.optJSONObject("files")
        val files = mutableMapOf<String, String>()
        if (filesObj != null) {
          for (key in filesObj.keys()) {
            files[key] = filesObj.optString(key, "")
          }
        }
        MemoryPatchResult(mode = "full", files = files, latestTimestamp = latestTimestamp)
      }
    } catch (err: Throwable) {
      Log.w(TAG, "getMemoryPatch failed: ${err.message}")
      null
    }
  }

  /**
   * Push memory files to gateway.
   * Sends changed files as a "full" mode update.
   */
  /** Apply memory patch. Returns server timestamp on success, null on failure. */
  suspend fun applyMemoryPatch(files: Map<String, String>): Long? {
    val gs = session ?: return null
    return try {
      val filesJson = JSONObject()
      for ((name, content) in files) filesJson.put(name, content)
      val params = JSONObject().apply {
        put("mode", "full")
        put("files", filesJson)
      }
      val response = gs.request("agent.applyMemoryPatch", params.toString(), timeoutMs = 15_000)
      val obj = JSONObject(response)
      val ok = obj.optBoolean("ok", false)
      if (ok) {
        Log.i(TAG, "Applied memory patch: ${files.size} files")
        obj.optLong("timestamp", System.currentTimeMillis())
      } else null
    } catch (err: Throwable) {
      Log.w(TAG, "applyMemoryPatch failed: ${err.message}")
      null
    }
  }

  data class MemoryPatchResult(
    val mode: String, // "full" or "patch"
    val patch: String? = null,
    val files: Map<String, String>? = null,
    val latestTimestamp: Long = 0
  )

  /**
   * Load recent conversation history for a session via sessions.preview RPC.
   * Returns list of (role, text) pairs ordered oldest-first, or empty list on failure.
   */
  suspend fun loadSessionHistory(sessionKey: String, limit: Int = 40): List<Pair<String, String>> {
    val gs = session ?: return emptyList()
    return try {
      val params = JSONObject().apply {
        put("sessionKey", sessionKey)
        put("limit", limit)
        put("rawContent", true)
      }
      val response = gs.request("chat.history", params.toString(), timeoutMs = 15_000)
      if (response == null) return emptyList()
      val obj = JSONObject(response)
      // Cache session label if present
      val label = obj.optString("label", "").takeIf { it.isNotEmpty() }
      if (label != null) setSessionLabel(sessionKey, label)
      val messages = obj.optJSONArray("messages") ?: return emptyList()
      val result = mutableListOf<Pair<String, String>>()
      for (i in 0 until messages.length()) {
        val msg = messages.optJSONObject(i) ?: continue
        val role = msg.optString("role", "")
        if (role != "user" && role != "assistant") continue
        // Extract text from content blocks (array) or plain string
        val contentArray = msg.optJSONArray("content")
        val text = if (contentArray != null) {
          val parts = mutableListOf<String>()
          for (j in 0 until contentArray.length()) {
            val block = contentArray.optJSONObject(j) ?: continue
            if (block.optString("type") == "text") {
              parts.add(block.optString("text", ""))
            }
          }
          parts.joinToString("\n").trim()
        } else {
          msg.optString("content", "").trim()
        }
        if (text.isNotEmpty()) {
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
  private data class ObservedSession(val sessionKey: String, var lastActivity: Long, var label: String? = null)
  private val observedSessions = mutableMapOf<String, ObservedSession>()

  /** Update the display label for a session (from sessions.preview or chat.history). */
  fun setSessionLabel(sessionKey: String, label: String) {
    observedSessions[sessionKey]?.label = label
  }

  /** Get the display label for a session if known. */
  fun getSessionLabel(sessionKey: String): String? = observedSessions[sessionKey]?.label

  private fun trackObservedSession(sessionKey: String) {
    if (sessionKey.isNotEmpty() && sessionKey != "gateway") {
      // Preserve existing label when updating activity time
      val existing = observedSessions[sessionKey]
      observedSessions[sessionKey] = ObservedSession(sessionKey, System.currentTimeMillis(), existing?.label)
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

  // Cumulative assistant text per runId (data.text from agent/assistant is cumulative, not delta)
  private val agentTextBuffers = mutableMapOf<String, String>()
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
          val runId = obj.optString("runId", "").takeIf { it.isNotEmpty() }
          // Track runId for dedup against drainPending
          if (runId != null) trackMessageId(runId)
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
          onChatMessage?.invoke(sessionKey, null, messageText, false)
          _remoteSessionEventFlow.tryEmit(RemoteSessionEvent(sessionKey, "assistant", messageText))
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
              val cumulative = data?.optString("text", "") ?: ""
              if (cumulative.isNotEmpty() && sessionKey != null) {
                // data.text is cumulative — compute delta from last known position
                val prev = agentTextBuffers[runId] ?: ""
                val delta = if (cumulative.length > prev.length) cumulative.substring(prev.length) else ""
                agentTextBuffers[runId] = cumulative
                // Emit delta for streaming display in Remote Agent View
                if (delta.isNotEmpty()) {
                  _remoteSessionEventFlow.tryEmit(RemoteSessionEvent(sessionKey, "streaming", delta))
                }
              }
            }
            "lifecycle" -> {
              val phase = data?.optString("phase", "") ?: ""
              if (phase == "end" || phase == "error") {
                // Guard against duplicate lifecycle end events for the same runId
                if (!processedAgentRuns.add(runId)) return
                val buffered = agentTextBuffers.remove(runId)?.trim()
                val effectiveSessionKey = agentSessionKeys.remove(runId) ?: "gateway"
                if (!buffered.isNullOrEmpty()) {
                  Log.i(TAG, "Agent run complete: runId=$runId, session=$effectiveSessionKey, len=${buffered.length}")
                  trackObservedSession(effectiveSessionKey)
                  // Use real session key so notification deep-link opens the correct conversation.
                  // NOTE: do NOT emit to remoteSessionEventFlow here — the chat event
                  // provides the clean final message and will emit separately.
                  onChatMessage?.invoke(effectiveSessionKey, "Agent Streaming", buffered, true)
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
