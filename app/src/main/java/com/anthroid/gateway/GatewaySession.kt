package com.anthroid.gateway

import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.withTimeoutOrNull
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
  private val context: Context,
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
    private const val GATEWAY_PROTOCOL_VERSION = 4
    // Liveness: the gateway sends a `tick` event every policy.tickIntervalMs (30 s by
    // default). A socket that stays silent for TICK_MISS_FACTOR intervals is treated as
    // dead, e.g. after ColorOS froze the app and its network was cut meanwhile.
    private const val DEFAULT_TICK_INTERVAL_MS = 30_000L
    private const val TICK_MISS_FACTOR = 2.5
    private const val LIVENESS_CHECK_MS = 15_000L
  }

  private val writeLock = Mutex()
  private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
  private val disconnectNotified = AtomicBoolean(false)

  // Shared OkHttpClient — reused across reconnections to avoid thread pool leaks
  // HTTP/1.1 only: WebSocket upgrade requires HTTP/1.1; h2 breaks it via TLS-ALPN proxies.
  // TLS: trust ONLY the pinned kelvin-tc gateway cert + an SPKI pin (see GatewayTls) —
  // scoped to this client so other app channels keep system-only trust. No-op for
  // cleartext ws://:80 (tls=false), which only needs cleartextTrafficPermitted.
  private val httpClient: OkHttpClient = GatewayTls.configure(
    OkHttpClient.Builder()
      .protocols(listOf(Protocol.HTTP_1_1))
      .writeTimeout(60, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS)
      .pingInterval(30, TimeUnit.SECONDS),
    context,
  ).build()

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

  // Ready only after the `connect` handshake succeeded: the gateway rejects any other
  // request sent first ("invalid handshake: first request must be connect").
  val isConnected: Boolean get() = currentConnection?.isReady == true && desired != null
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
    val conn = readyConnection()
    val params = if (paramsJson.isNullOrBlank()) null else JSONObject(paramsJson)
    val res = conn.request(method, params, timeoutMs)
    if (res.ok) return res.payloadJson ?: ""
    val err = res.error
    throw IllegalStateException("${err?.code ?: "UNAVAILABLE"}: ${err?.message ?: "request failed"}")
  }

  suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean {
    val conn = currentConnection?.takeIf { it.isReady } ?: return false
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

  private fun readyConnection(): Connection {
    val conn = currentConnection ?: throw IllegalStateException("not connected")
    if (!conn.isReady) throw IllegalStateException("not connected (handshake in progress)")
    return conn
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
    @Volatile private var handshakeComplete = false
    @Volatile private var lastInboundAtMs = SystemClock.elapsedRealtime()
    @Volatile private var tickIntervalMs = DEFAULT_TICK_INTERVAL_MS

    val remoteAddress: String = "$host:$port"
    val isReady: Boolean get() = handshakeComplete && !isClosed.get()

    fun silentMs(): Long = SystemClock.elapsedRealtime() - lastInboundAtMs
    fun maxSilenceMs(): Long = (tickIntervalMs * TICK_MISS_FACTOR).toLong()

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
        failPending()
        closedDeferred.complete(Unit)
      }
    }

    /** Drop a socket that stopped delivering frames; no close handshake with a dead peer. */
    fun abort(reason: String) {
      if (isClosed.compareAndSet(false, true)) {
        socket?.cancel()
        socket = null
        failPending()
        closedDeferred.complete(Unit)
        if (disconnectNotified.compareAndSet(false, true)) {
          onDisconnected(reason)
        }
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
        lastInboundAtMs = SystemClock.elapsedRealtime()
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

      // The gateway closed first (e.g. after rejecting a request). OkHttp only reports
      // onClosed once we answer with our own close frame, so finish the close here;
      // otherwise the session would sit half-closed until a ping failure.
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        onClosed(webSocket, code, reason)
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
      obj.optJSONObject("policy")?.optLong("tickIntervalMs", 0L)?.takeIf { it > 0 }?.let {
        tickIntervalMs = it
      }
      lastInboundAtMs = SystemClock.elapsedRealtime()
      // Mark ready before onConnected: its listeners immediately issue requests (drain).
      handshakeComplete = true
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

  /**
   * Wait for the socket to close, dropping it if no frame arrived for maxSilenceMs.
   * Timers use the monotonic clock, so after the process is thawed the next check runs
   * at once and a socket that died while frozen is replaced instead of shown as live.
   */
  private suspend fun awaitCloseOrSilence(conn: Connection) {
    var lastCheckAt = SystemClock.elapsedRealtime()
    while (true) {
      if (withTimeoutOrNull(LIVENESS_CHECK_MS) { conn.awaitClose() } != null) return
      val now = SystemClock.elapsedRealtime()
      val gapMs = now - lastCheckAt
      lastCheckAt = now
      if (gapMs > LIVENESS_CHECK_MS * 3) {
        Log.i(TAG, "liveness: resumed after ${gapMs / 1000}s gap (process frozen or suspended)")
      }
      val silentMs = conn.silentMs()
      if (silentMs > conn.maxSilenceMs()) {
        Log.w(TAG, "liveness: no frame for ${silentMs / 1000}s, dropping socket")
        conn.abort("Gateway silent for ${silentMs / 1000}s")
        return
      }
    }
  }

  private suspend fun connectOnce(target: DesiredConnection) = withContext(Dispatchers.IO) {
    val conn = Connection(target.host, target.port, target.token, target.options, target.useTls)
    currentConnection = conn
    try {
      conn.connect()
      awaitCloseOrSilence(conn)
    } finally {
      currentConnection = null
      mainSessionKey = null
    }
  }
}
