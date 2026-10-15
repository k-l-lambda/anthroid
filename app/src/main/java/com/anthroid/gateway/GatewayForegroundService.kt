package com.anthroid.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anthroid.R
import com.anthroid.accessibility.ScreenAutomationOverlay
import com.anthroid.main.MainPagerActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service that keeps the gateway WebSocket connection alive
 * when the app is backgrounded. Displays system notifications for incoming
 * agent messages (especially cron task results).
 */
class GatewayForegroundService : Service() {

    companion object {
        private const val TAG = "GatewayFgService"
        private const val CHANNEL_ID = "gateway_service"
        private const val NOTIFICATION_ID = 60000
        // Skip exact noise messages from monitoring/heartbeat crons (full match only)
        private val NOISE_MESSAGES = setOf(
            "HEARTBEAT_OK", "PONG", "NO",
        )

        /** Session key of the currently open remote OpenClaw view (null if none). */
        @Volatile
        var activeRemoteSessionKey: String? = null

        const val ACTION_START = "com.anthroid.gateway.START"
        const val ACTION_STOP = "com.anthroid.gateway.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_USE_TLS = "use_tls"

        @Volatile
        var instance: GatewayForegroundService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /**
         * Android 16 Live Update (promoted ongoing) mode for the status notification, stored in
         * default prefs. Android 16 has two promotion rule sets (AOSP Notification.java,
         * hasPromotableCharacteristics):
         *  - "request":   uiRichOngoing builds: EXTRA_REQUEST_PROMOTED_ONGOING, must NOT be colorized
         *  - "colorized": original 16.0 builds: EXTRA_COLORIZED (no request extra)
         *  - "off":       plain ongoing notification
         * Both also need POST_PROMOTED_NOTIFICATIONS, ongoing, a title and no custom views.
         */
        const val PREF_PROMOTED_MODE = "gateway_promoted_mode"
        const val PROMOTED_MODE_REQUEST = "request"
        const val PROMOTED_MODE_COLORIZED = "colorized"
        const val PROMOTED_MODE_OFF = "off"

        // Notification extras / flag from API 36 (compileSdk is 33, so use the literal values)
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        private const val FLAG_PROMOTED_ONGOING = 0x00040000

        /** Start with explicit connection params. */
        fun start(context: Context, host: String, port: Int, token: String?, useTls: Boolean = true) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USE_TLS, useTls)
                token?.let { putExtra(EXTRA_TOKEN, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Start from saved prefs (reads gateway_host/port/token). */
        fun start(context: Context) {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val host = prefs.getString("gateway_host", null) ?: return
            val port = prefs.getString("gateway_port", "40445")?.toIntOrNull() ?: 40445
            val token = prefs.getString("gateway_token", null)?.takeIf { it.isNotEmpty() }
            // Reuse the persisted transport setting. Manual gateway connections default to TLS,
            // and auto-restarts must not silently downgrade a saved wss:// connection to ws://.
            val useTls = prefs.getBoolean("gateway_use_tls", true)
            start(context, host, port, token, useTls)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayForegroundService::class.java))
        }
    }

    /** Disconnect and reconnect using saved prefs. */
    fun reconnect() {
        gatewayManager?.disconnect()
        tryStartGatewayFromPrefs()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var gatewayManager: GatewayManager? = null
        private set

    private val _gatewayManagerFlow = MutableStateFlow<GatewayManager?>(null)
    val gatewayManagerFlow: StateFlow<GatewayManager?> = _gatewayManagerFlow.asStateFlow()
    var notificationHelper: GatewayNotificationHelper? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createServiceChannel()
        notificationHelper = GatewayNotificationHelper(applicationContext)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createServiceNotification("Connecting..."))
                val host = intent.getStringExtra(EXTRA_HOST) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val port = intent.getIntExtra(EXTRA_PORT, 40445)
                val token = intent.getStringExtra(EXTRA_TOKEN)
                val useTls = intent.getBooleanExtra(EXTRA_USE_TLS, true)
                startGateway(host, port, token, useTls)
            }
            ACTION_STOP -> stopSelf()
            else -> {
                // START_STICKY restart (null intent) or unknown action.
                // Must call startForeground() to avoid ANR on Android 8+.
                startForeground(NOTIFICATION_ID, createServiceNotification("Reconnecting..."))
                tryStartGatewayFromPrefs()
            }
        }
        return START_STICKY
    }

    private fun tryStartGatewayFromPrefs() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("gateway_enabled", false)) {
            Log.i(TAG, "Auto-restart skipped: gateway_enabled=false")
            return
        }
        val host = prefs.getString("gateway_host", null) ?: run {
            Log.w(TAG, "Auto-restart skipped: no saved host")
            return
        }
        val port = prefs.getString("gateway_port", "40445")?.toIntOrNull() ?: 40445
        val token = prefs.getString("gateway_token", null)
        val useTls = prefs.getBoolean("gateway_use_tls", true)
        Log.i(TAG, "Auto-restarting gateway from prefs: $host:$port (tls=$useTls)")
        startGateway(host, port, token, useTls)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        gatewayManager?.disconnect()
        gatewayManager = null
        _gatewayManagerFlow.value = null
        notificationHelper?.clearAll()
        notificationHelper = null
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    /**
     * Drain pending messages from the gateway for all observed sessions.
     * Dispatches them as RemoteSessionEvents so they appear in Remote Agent View
     * and trigger notifications.
     */
    private suspend fun drainAndDeliverPending(manager: GatewayManager) {
        // Pre-warm session labels so notifications use job names (e.g. "Gold Monitor")
        try { manager.listSessions() } catch (_: Exception) {}
        // Drain ALL pending messages in one call (no per-key iteration needed)
        val messages = manager.drainAllPendingMessages()
        val groupedMessages = messages
            .filter { it.content.trim() !in NOISE_MESSAGES }
            .groupBy { GatewayManager.agentScopeKey(it.sessionKey) }
        for ((agentScopeKey, agentMessages) in groupedMessages) {
            agentMessages.forEach { msg ->
                val messageSessionKey = GatewayManager.canonicalSessionKey(msg.sessionKey)
                manager.emitRemoteSessionEvent(
                    GatewayManager.RemoteSessionEvent(messageSessionKey, "assistant", msg.content)
                )
            }
            val viewingThisAgent = ScreenAutomationOverlay.isAppInForeground
                && GatewayManager.agentScopeKey(activeRemoteSessionKey ?: "") == agentScopeKey
            if (!viewingThisAgent) {
                agentMessages.forEach { msg ->
                    val messageSessionKey = GatewayManager.canonicalSessionKey(msg.sessionKey)
                    val resolvedTitle = msg.title
                        ?: manager.getSessionLabel(messageSessionKey)
                        ?: agentScopeKey.removePrefix("agent:").ifBlank { "Agent" }
                    notificationHelper?.showMessageNotification(
                        sessionKey = agentScopeKey,
                        displayName = resolvedTitle,
                        messageText = msg.content,
                        sessionLabel = resolvedTitle,
                        deepLinkSessionKey = messageSessionKey,
                    )
                }
            }
            Log.i(TAG, "Delivered ${agentMessages.size} pending message(s) for $agentScopeKey")
        }
    }

    /**
     * Notification title for a question: the first question's header (a <=12 char
     * chip in the gateway schema), qualified by the asking agent when known.
     */
    private fun questionHeadline(question: GatewayManager.PendingGatewayQuestion): String {
        val header = firstQuestionField(question, "header")
        val agent = question.agentId
        return when {
            !header.isNullOrBlank() && !agent.isNullOrBlank() -> "$agent asks: $header"
            !header.isNullOrBlank() -> "Question: $header"
            !agent.isNullOrBlank() -> "$agent has a question"
            else -> "An agent has a question"
        }
    }

    /** Notification body: the question text itself, plus a count when there are several. */
    private fun questionBody(question: GatewayManager.PendingGatewayQuestion): String {
        val text = firstQuestionField(question, "question") ?: "Tap to answer."
        val count = questionCount(question)
        return if (count > 1) "$text\n(+${count - 1} more)" else text
    }

    private fun firstQuestionField(
        question: GatewayManager.PendingGatewayQuestion,
        field: String,
    ): String? = try {
        org.json.JSONArray(question.questionsJson)
            .optJSONObject(0)
            ?.optString(field, "")
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private fun questionCount(question: GatewayManager.PendingGatewayQuestion): Int = try {
        org.json.JSONArray(question.questionsJson).length()
    } catch (_: Throwable) {
        1
    }

    private fun startGateway(host: String, port: Int, token: String?, useTls: Boolean = true) {
        // Null out callbacks before disconnect to prevent stale events from old session
        gatewayManager?.onNotification = null
        gatewayManager?.onChatMessage = null
        gatewayManager?.onQuestionRequested = null
        gatewayManager?.onQuestionResolved = null
        gatewayManager?.disconnect()

        val manager = GatewayManager(applicationContext, serviceScope)
        gatewayManager = manager
        _gatewayManagerFlow.value = manager

        // Wire up notification.push events
        manager.onNotification = { title, body ->
            // Shown unconditionally, foreground included — same rule as questions, and
            // deliberately different from chat messages. A suppressed chat message is
            // still readable later in its session; a push has no session, so suppressing
            // it destroys the only copy. Its text lives solely in the notification.
            //
            // Must use the standalone path: showMessageNotification deep-links to a
            // remote session, and a session key of "notification" does not exist on the
            // gateway, so the tap opened an empty session view with no body text.
            notificationHelper?.showStandaloneNotification(title, body)
        }

        // Wire up agent questions. Unlike chat, a question notification is shown even
        // when the app is in the foreground: the asking agent is blocked on it, and
        // the user may be on an unrelated screen (terminal, settings).
        manager.onQuestionRequested = { question ->
            val headline = questionHeadline(question)
            val body = questionBody(question)
            notificationHelper?.showQuestionNotification(
                questionId = question.questionId,
                headline = headline,
                body = body,
                sessionKey = question.sessionKey,
            )
        }

        // A question can be answered on another surface (Control UI, TUI), cancelled,
        // or expire on the gateway's own timer. Dismiss our notification either way
        // so the user is not invited to answer something already terminal.
        manager.onQuestionResolved = { questionId, status ->
            Log.i(TAG, "Question $questionId resolved elsewhere: $status")
            notificationHelper?.clearQuestion(questionId)
        }

        // Wire up chat message events
        manager.onChatMessage = { sessionKey, displayName, messageText, isStreaming ->
            val canonicalSessionKey = GatewayManager.canonicalSessionKey(sessionKey)
            val agentScopeKey = GatewayManager.agentScopeKey(canonicalSessionKey)
            val viewingThisAgent = ScreenAutomationOverlay.isAppInForeground
                && GatewayManager.agentScopeKey(activeRemoteSessionKey ?: "") == agentScopeKey
            if (!viewingThisAgent && !messageText.isNullOrBlank() && messageText.trim() !in NOISE_MESSAGES) {
                // Merge all cron outputs for one agent into one notification.
                val notifKey = agentScopeKey
                val sessionLabel = manager.getSessionLabel(canonicalSessionKey)
                val resolvedTitle = displayName
                    ?.takeUnless { isStreaming && it == "Agent Streaming" }
                    ?: sessionLabel
                    ?: displayName
                    ?: agentScopeKey.removePrefix("agent:").ifBlank { if (isStreaming) "Agent Streaming" else "Agent" }
                notificationHelper?.showMessageNotification(
                    sessionKey = notifKey,
                    displayName = resolvedTitle,
                    messageText = messageText,
                    channelId = if (isStreaming) GatewayNotificationHelper.CHANNEL_ID_STREAMING
                                else GatewayNotificationHelper.CHANNEL_ID,
                    sessionLabel = resolvedTitle,
                    deepLinkSessionKey = canonicalSessionKey,
                    isStreaming = isStreaming,
                )
            }
        }

        // Update persistent notification on connection status changes
        serviceScope.launch {
            manager.connectionStatus.collectLatest { status ->
                updateServiceNotification(status)
            }
        }

        // Immediate drain on connect; periodic drain every 60s
        serviceScope.launch {
            manager.isConnected.collect { connected: Boolean ->
                if (connected) {
                    Log.d(TAG, "Gateway connected — draining pending messages immediately")
                    drainAndDeliverPending(manager)
                    manager.syncPendingQuestions()
                }
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(60_000)
                val connected = manager.isConnected.value
                if (connected) {
                    drainAndDeliverPending(manager)
                }
            }
        }

        manager.connect(host, port, token, useTls)
        Log.i(TAG, "Gateway started: $host:$port (tls=$useTls)")
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gateway Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps gateway connection alive for notifications"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    @Volatile
    private var lastStatusText = "Connecting..."

    /** Re-post the status notification after the promoted mode pref changes (debug broadcast). */
    fun refreshServiceNotification() {
        updateServiceNotification(lastStatusText)
    }

    private fun promotedMode(): String =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_PROMOTED_MODE, PROMOTED_MODE_REQUEST) ?: PROMOTED_MODE_REQUEST

    private fun createServiceNotification(statusText: String): Notification {
        lastStatusText = statusText
        val intent = Intent(this, MainPagerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isConnected = statusText.startsWith("Connected")
        val iconRes = if (isConnected) R.drawable.ic_service_notification
                      else R.drawable.ic_gateway_error_notification
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anthroid Gateway")
            .setContentText(statusText)
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
        when (promotedMode()) {
            PROMOTED_MODE_REQUEST -> builder.addExtras(android.os.Bundle().apply {
                putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            })
            PROMOTED_MODE_COLORIZED -> builder
                .setColorized(true)
                .setColor(if (isConnected) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        }
        if (promotedMode() != PROMOTED_MODE_OFF) {
            // Status bar chip text of a promoted notification
            builder.addExtras(android.os.Bundle().apply {
                putString(EXTRA_SHORT_CRITICAL_TEXT, if (isConnected) "Online" else "Offline")
            })
        }
        return builder.build()
    }

    /**
     * Log whether the system promoted the posted status notification. Reads the posted copy back
     * since FLAG_PROMOTED_ONGOING is set by the system, not the app.
     *   adb logcat -s GatewayFgService | grep promoted
     */
    private fun logPromotionState() {
        if (Build.VERSION.SDK_INT < 36) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val posted = nm.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }?.notification
            if (posted == null) {
                Log.i(TAG, "promoted: status notification not active")
                return
            }
            val promotable = runCatching {
                Notification::class.java.getMethod("hasPromotableCharacteristics").invoke(posted)
            }.getOrElse { "n/a" }
            val canPost = runCatching {
                NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(nm)
            }.getOrElse { "n/a" }
            Log.i(TAG, "promoted: mode=${promotedMode()} flagPromoted=" +
                "${posted.flags and FLAG_PROMOTED_ONGOING != 0} promotable=$promotable " +
                "canPostPromoted=$canPost flags=0x${Integer.toHexString(posted.flags)}")
        } catch (e: Exception) {
            Log.w(TAG, "promoted: check failed: ${e.message}")
        }
    }

    private fun updateServiceNotification(statusText: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createServiceNotification(statusText))
            serviceScope.launch {
                delay(500)
                logPromotionState()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update service notification: ${e.message}")
        }
    }
}
