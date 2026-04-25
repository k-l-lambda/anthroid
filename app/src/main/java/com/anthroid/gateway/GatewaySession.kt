package com.anthroid.gateway

import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

data class GatewayClientInfo(
  val id: String,
  val displayName: String?,
  val version: String,
  val platform: String,
  val mode: String,
  val instanceId: String?,
  val deviceFamily: String?,
  val modelIdentifier: String?,
)

data class GatewayConnectOptions(
  val role: String,
  val scopes: List<String>,
  val caps: List<String> = emptyList(),
  val commands: List<String> = emptyList(),
  val permissions: Map<String, Boolean> = emptyMap(),
  val client: GatewayClientInfo,
  val userAgent: String? = null,
)

class GatewaySession(
  private val scope: CoroutineScope,
  private val identityStore: DeviceIdentityStore,
  private val deviceAuthStore: DeviceAuthStore,
  private val onConnected: (serverName: String?, remoteAddress: String?, mainSessionKey: String?) -> Unit,
  private val onDisconnected: (message: String) -> Unit,
  private val onEvent: (event: String, payloadJson: String?) -> Unit,
) {
  private companion object {
    private const val TAG = "AnthroidGateway"
    private const val CONNECT_RPC_TIMEOUT_MS = 12_000L
    private const val GATEWAY_PROTOCOL_VERSION = 3
  }

  private val writeLock = Mutex()
  private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
  private val disconnectNotified = AtomicBoolean(false)

  // Shared OkHttpClient — reused across reconnections to avoid thread pool leaks
  // HTTP/1.1 only: WebSocket upgrade requires HTTP/1.1; h2 breaks it via TLS-ALPN proxies
  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

  @Volatile private var mainSessionKey: String? = null

  private data class DesiredConnection(
    val host: String,
    val port: Int,
    val token: String?,
    val options: GatewayConnectOptions,
    val useTls: Boolean = true,
  )

  private var desired: DesiredConnection? = null
  private var job: Job? = null
  @Volatile private var currentConnection: Connection? = null

  val isConnected: Boolean get() = currentConnection != null && desired != null
  fun currentMainSessionKey(): String? = mainSessionKey

  fun connect(
    host: String,
    port: Int,
    token: String?,
    options: GatewayConnectOptions,
    useTls: Boolean = true,
  ) {
    desired = DesiredConnection(host, port, token, options, useTls)
    if (job == null) {
      job = scope.launch(Dispatchers.IO) { runLoop() }
    }
  }

  fun disconnect() {
    desired = null
    currentConnection?.closeQuietly()
    scope.launch(Dispatchers.IO) {
      job?.cancelAndJoin()
      job = null
      mainSessionKey = null
      // Only notify once — listener may have already called onDisconnected
      if (disconnectNotified.compareAndSet(false, true)) {
        onDisconnected("Offline")
      }
    }
  }

  fun reconnect() {
    currentConnection?.closeQuietly()
  }

  suspend fun request(method: String, paramsJson: String?, timeoutMs: Long = 15_000): String {
    val conn = currentConnection ?: throw IllegalStateException("not connected")
    val params = if (paramsJson.isNullOrBlank()) null else JSONObject(paramsJson)
    val res = conn.request(method, params, timeoutMs)
    if (res.ok) return res.payloadJson ?: ""
    val err = res.error
    throw IllegalStateException("${err?.code ?: "UNAVAILABLE"}: ${err?.message ?: "request failed"}")
  }

  suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean {
    val conn = currentConnection ?: return false
    val params = JSONObject().apply {
      put("event", event)
      if (payloadJson != null) {
        put("payloadJSON", payloadJson)
      } else {
        put("payloadJSON", JSONObject.NULL)
      }
    }
    return try {
      conn.request("node.event", params, timeoutMs = 8_000)
      true
    } catch (err: Throwable) {
      Log.w(TAG, "node.event failed: ${err.message ?: err::class.java.simpleName}")
      false
    }
  }

  private data class RpcResponse(
    val id: String,
    val ok: Boolean,
    val payloadJson: String?,
    val error: ErrorShape?,
  )

  private data class ErrorShape(val code: String, val message: String)

  private inner class Connection(
    private val host: String,
    private val port: Int,
    private val token: String?,
    private val options: GatewayConnectOptions,
    private val useTls: Boolean = true,
  ) {
    private val connectDeferred = CompletableDeferred<Unit>()
    private val closedDeferred = CompletableDeferred<Unit>()
    private val isClosed = AtomicBoolean(false)
    private val connectNonceDeferred = CompletableDeferred<String>()
    private var socket: WebSocket? = null

    val remoteAddress: String = "$host:$port"

    suspend fun connect() {
      val scheme = if (useTls) "wss" else "ws"
      val url = "$scheme://$host:$port"
      val request = Request.Builder().url(url).build()
      socket = httpClient.newWebSocket(request, Listener())
      connectDeferred.await()
    }

    suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): RpcResponse {
      val id = UUID.randomUUID().toString()
      val deferred = CompletableDeferred<RpcResponse>()
      pending[id] = deferred
      val frame = JSONObject().apply {
        put("type", "req")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
      }
      try {
        sendJson(frame)
        return withTimeout(timeoutMs) { deferred.await() }
      } catch (err: Throwable) {
        pending.remove(id)
        throw if (err is TimeoutCancellationException) IllegalStateException("request timeout") else err
      }
    }

    suspend fun sendJson(obj: JSONObject) {
      val jsonString = obj.toString()
      writeLock.withLock {
        socket?.send(jsonString)
      }
    }

    suspend fun awaitClose() = closedDeferred.await()

    fun closeQuietly() {
      if (isClosed.compareAndSet(false, true)) {
        socket?.close(1000, "bye")
        socket = null
        closedDeferred.complete(Unit)
      }
    }

    private inner class Listener : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        scope.launch {
          try {
            val nonce = awaitConnectNonce()
            sendConnect(nonce)
          } catch (err: Throwable) {
            connectDeferred.completeExceptionally(err)
            closeQuietly()
          }
        }
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        if (isClosed.get()) return  // drop messages arriving after closeQuietly()
        scope.launch { handleMessage(text) }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!connectDeferred.isCompleted) {
          connectDeferred.completeExceptionally(t)
        }
        if (isClosed.compareAndSet(false, true)) {
          failPending()
          closedDeferred.complete(Unit)
          if (disconnectNotified.compareAndSet(false, true)) {
            onDisconnected("Gateway error: ${t.message ?: t::class.java.simpleName}")
          }
        }
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!connectDeferred.isCompleted) {
          connectDeferred.completeExceptionally(IllegalStateException("Gateway closed: $reason"))
        }
        if (isClosed.compareAndSet(false, true)) {
          failPending()
          closedDeferred.complete(Unit)
          if (disconnectNotified.compareAndSet(false, true)) {
            onDisconnected("Gateway closed: $reason")
          }
        }
      }
    }

    private suspend fun sendConnect(connectNonce: String) {
      val identity = identityStore.loadOrCreate()
      val storedToken = deviceAuthStore.loadToken(identity.deviceId, options.role)
      val trimmedToken = token?.trim().orEmpty()
      val authToken = if (trimmedToken.isNotBlank()) trimmedToken else storedToken.orEmpty()
      val payload = buildConnectParams(identity, connectNonce, authToken)
      val res = request("connect", payload, timeoutMs = CONNECT_RPC_TIMEOUT_MS)
      if (!res.ok) {
        val msg = res.error?.message ?: "connect failed"
        throw IllegalStateException(msg)
      }
      handleConnectSuccess(res, identity.deviceId)
      connectDeferred.complete(Unit)
    }

    private fun handleConnectSuccess(res: RpcResponse, deviceId: String) {
      val payloadJson = res.payloadJson ?: throw IllegalStateException("connect failed: missing payload")
      val obj = JSONObject(payloadJson)
      val serverName = obj.optJSONObject("server")?.optString("host", "")?.takeIf { it.isNotEmpty() }
      val authObj = obj.optJSONObject("auth")
      val deviceToken = authObj?.optString("deviceToken", "")?.takeIf { it.isNotEmpty() }
      val authRole = authObj?.optString("role", "")?.takeIf { it.isNotEmpty() } ?: options.role
      if (!deviceToken.isNullOrBlank()) {
        deviceAuthStore.saveToken(deviceId, authRole, deviceToken)
      }
      val sessionDefaults = obj.optJSONObject("snapshot")
        ?.optJSONObject("sessionDefaults")
      mainSessionKey = sessionDefaults?.optString("mainSessionKey", "")?.takeIf { it.isNotEmpty() }
      onConnected(serverName, remoteAddress, mainSessionKey)
    }

    private fun buildConnectParams(
      identity: DeviceIdentity,
      connectNonce: String,
      authToken: String,
    ): JSONObject {
      val client = options.client
      val locale = Locale.getDefault().toLanguageTag()
      val clientObj = JSONObject().apply {
        put("id", client.id)
        client.displayName?.let { put("displayName", it) }
        put("version", client.version)
        put("platform", client.platform)
        put("mode", client.mode)
        client.instanceId?.let { put("instanceId", it) }
        client.deviceFamily?.let { put("deviceFamily", it) }
        client.modelIdentifier?.let { put("modelIdentifier", it) }
      }

      val authJson = if (authToken.isNotEmpty()) {
        JSONObject().apply { put("token", authToken) }
      } else null

      val signedAtMs = System.currentTimeMillis()
      val payloadStr = DeviceAuthPayload.buildV3(
        deviceId = identity.deviceId,
        clientId = client.id,
        clientMode = client.mode,
        role = options.role,
        scopes = options.scopes,
        signedAtMs = signedAtMs,
        token = if (authToken.isNotEmpty()) authToken else null,
        nonce = connectNonce,
        platform = client.platform,
        deviceFamily = client.deviceFamily,
      )
      val signature = identityStore.signPayload(payloadStr, identity)
      val publicKey = identityStore.publicKeyBase64Url(identity)

      Log.d(TAG, "connect: deviceId=${identity.deviceId}, role=${options.role}, signed=$signedAtMs")

      val deviceJson = if (!signature.isNullOrBlank() && !publicKey.isNullOrBlank()) {
        JSONObject().apply {
          put("id", identity.deviceId)
          put("publicKey", publicKey)
          put("signature", signature)
          put("signedAt", signedAtMs)
          put("nonce", connectNonce)
        }
      } else null

      return JSONObject().apply {
        put("minProtocol", GATEWAY_PROTOCOL_VERSION)
        put("maxProtocol", GATEWAY_PROTOCOL_VERSION)
        put("client", clientObj)
        if (options.caps.isNotEmpty()) put("caps", JSONArray(options.caps))
        if (options.commands.isNotEmpty()) put("commands", JSONArray(options.commands))
        if (options.permissions.isNotEmpty()) {
          put("permissions", JSONObject().apply {
            options.permissions.forEach { (key, value) -> put(key, value) }
          })
        }
        put("role", options.role)
        if (options.scopes.isNotEmpty()) put("scopes", JSONArray(options.scopes))
        authJson?.let { put("auth", it) }
        deviceJson?.let { put("device", it) }
        put("locale", locale)
        options.userAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { put("userAgent", it) }
      }
    }

    private suspend fun handleMessage(text: String) {
      val frame = try { JSONObject(text) } catch (_: Throwable) { return }
      when (frame.optString("type")) {
        "res" -> handleResponse(frame)
        "event" -> handleEvent(frame)
      }
    }

    private fun handleResponse(frame: JSONObject) {
      val id = frame.optString("id", "") .takeIf { it.isNotEmpty() } ?: return
      val ok = frame.optBoolean("ok", false)
      val payloadJson = if (frame.has("payload")) frame.get("payload").toString() else null
      val error = if (frame.has("error")) {
        val errObj = frame.optJSONObject("error")
        val code = errObj?.optString("code", "UNAVAILABLE") ?: "UNAVAILABLE"
        val msg = errObj?.optString("message", "request failed") ?: "request failed"
        ErrorShape(code, msg)
      } else null
      pending.remove(id)?.complete(RpcResponse(id, ok, payloadJson, error))
    }

    private fun handleEvent(frame: JSONObject) {
      val event = frame.optString("event", "").takeIf { it.isNotEmpty() } ?: return
      val payloadJson = when {
        frame.has("payload") -> frame.get("payload").toString()
        frame.has("payloadJSON") -> frame.optString("payloadJSON", "").takeIf { it.isNotEmpty() }
        else -> null
      }
      if (event == "connect.challenge") {
        val nonce = extractConnectNonce(payloadJson)
        if (!connectNonceDeferred.isCompleted && !nonce.isNullOrBlank()) {
          connectNonceDeferred.complete(nonce.trim())
        }
        return
      }
      onEvent(event, payloadJson)
    }

    private suspend fun awaitConnectNonce(): String {
      return try {
        withTimeout(2_000) { connectNonceDeferred.await() }
      } catch (err: Throwable) {
        throw IllegalStateException("connect challenge timeout", err)
      }
    }

    private fun extractConnectNonce(payloadJson: String?): String? {
      if (payloadJson.isNullOrBlank()) return null
      return try {
        JSONObject(payloadJson).optString("nonce", "").takeIf { it.isNotEmpty() }
      } catch (_: Throwable) {
        null
      }
    }

    private fun failPending() {
      for ((_, waiter) in pending) {
        waiter.cancel()
      }
      pending.clear()
    }
  }

  private suspend fun runLoop() {
    var attempt = 0
    while (scope.isActive) {
      val target = desired
      if (target == null) {
        currentConnection?.closeQuietly()
        currentConnection = null
        delay(250)
        continue
      }

      try {
        disconnectNotified.set(false)  // Reset for new connection cycle
        onDisconnected(if (attempt == 0) "Connecting..." else "Reconnecting...")
        connectOnce(target)
        attempt = 0
      } catch (err: Throwable) {
        attempt += 1
        Log.w(TAG, "connect attempt #$attempt failed: ${err.message}")
        if (disconnectNotified.compareAndSet(false, true)) {
          onDisconnected("Gateway error: ${err.message ?: err::class.java.simpleName}")
        }
        val sleepMs = minOf(8_000L, (350.0 * Math.pow(1.7, attempt.toDouble())).toLong())
        delay(sleepMs)
      }
    }
  }

  private suspend fun connectOnce(target: DesiredConnection) = withContext(Dispatchers.IO) {
    val conn = Connection(target.host, target.port, target.token, target.options, target.useTls)
    currentConnection = conn
    try {
      conn.connect()
      conn.awaitClose()
    } finally {
      currentConnection = null
      mainSessionKey = null
    }
  }
}
