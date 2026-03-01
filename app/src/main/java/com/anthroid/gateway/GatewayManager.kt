package com.anthroid.gateway

import android.content.Context
import android.util.Log
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
    private const val CLIENT_ID = "anthroid"
    private const val CLIENT_MODE = "operator"
    private const val CLIENT_VERSION = "0.10.9"
    private const val CLIENT_PLATFORM = "android"
    private const val DEFAULT_ROLE = "operator"
    private val DEFAULT_SCOPES = listOf("operator.read", "operator.write")
  }

  private val identityStore = DeviceIdentityStore(context)
  private val authStore = DeviceAuthStore(context)

  private val _connectionStatus = MutableStateFlow("Offline")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

  var onNotification: ((title: String, body: String) -> Unit)? = null

  private var session: GatewaySession? = null

  fun connect(host: String, port: Int, token: String? = null) {
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
    gatewaySession.connect(host, port, token, options)
    Log.i(TAG, "Connecting to gateway at $host:$port...")
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
        val messages = JSONArray().apply {
          put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
          })
          put(JSONObject().apply {
            put("role", "assistant")
            put("content", assistantResponse)
          })
        }
        val params = JSONObject().apply {
          put("sessionKey", "Anthroid")
          put("messages", messages)
        }
        gatewaySession.request("chat.inject", params.toString(), timeoutMs = 10_000)
        Log.d(TAG, "Session sync: injected ${messages.length()} messages to Anthroid session")
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
      else -> {
        Log.d(TAG, "Unhandled gateway event: $event")
      }
    }
  }
}
