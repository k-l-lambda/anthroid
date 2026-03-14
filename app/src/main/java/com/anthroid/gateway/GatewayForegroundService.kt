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
            start(context, host, port, token, useTls = false)
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
        val observedSessions = manager.getObservedSessionKeys()
        for (sessionKey in observedSessions) {
            val messages = manager.drainPendingMessages(sessionKey)
            for (content in messages) {
                // Emit as assistant event so RemoteAgentViewModel receives it
                manager.emitRemoteSessionEvent(
                    GatewayManager.RemoteSessionEvent(sessionKey, "assistant", content)
                )
                // Also show system notification when app is in background
                if (!ScreenAutomationOverlay.isAppInForeground) {
                    notificationHelper?.showMessageNotification(
                        sessionKey = sessionKey,
                        displayName = "Agent",
                        messageText = content
                    )
                }
                Log.i(TAG, "Delivered pending message for $sessionKey: ${content.take(60)}")
            }
        }
    }

    private fun startGateway(host: String, port: Int, token: String?, useTls: Boolean = true) {
        // Null out callbacks before disconnect to prevent stale events from old session
        gatewayManager?.onNotification = null
        gatewayManager?.onChatMessage = null
        gatewayManager?.disconnect()

        val manager = GatewayManager(applicationContext, serviceScope)
        gatewayManager = manager
        _gatewayManagerFlow.value = manager

        // Wire up notification.push events
        manager.onNotification = { title, body ->
            if (!ScreenAutomationOverlay.isAppInForeground) {
                notificationHelper?.showMessageNotification(
                    sessionKey = "notification",
                    displayName = title,
                    messageText = body
                )
            }
        }

        // Wire up chat message events
        manager.onChatMessage = { sessionKey, displayName, messageText, isStreaming ->
            if (!ScreenAutomationOverlay.isAppInForeground && !messageText.isNullOrBlank()) {
                // Use separate notification keys so streaming and final don't replace each other
                val notifKey = if (isStreaming) "$sessionKey:streaming" else sessionKey
                val notifName = displayName ?: if (isStreaming) "Agent Streaming" else "Agent"
                notificationHelper?.showMessageNotification(
                    sessionKey = notifKey,
                    displayName = notifName,
                    messageText = messageText,
                    channelId = if (isStreaming) GatewayNotificationHelper.CHANNEL_ID_STREAMING
                                else GatewayNotificationHelper.CHANNEL_ID
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

    private fun createServiceNotification(statusText: String): Notification {
        val intent = Intent(this, MainPagerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anthroid Gateway")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateServiceNotification(statusText: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createServiceNotification(statusText))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update service notification: ${e.message}")
        }
    }
}
