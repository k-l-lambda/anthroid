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

        @Volatile
        var instance: GatewayForegroundService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun start(context: Context, host: String, port: Int, token: String?) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                token?.let { putExtra(EXTRA_TOKEN, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var gatewayManager: GatewayManager? = null
        private set
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
                startGateway(host, port, token)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        gatewayManager?.disconnect()
        gatewayManager = null
        notificationHelper?.clearAll()
        notificationHelper = null
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun startGateway(host: String, port: Int, token: String?) {
        gatewayManager?.disconnect()

        val manager = GatewayManager(applicationContext, serviceScope)
        gatewayManager = manager

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
        manager.onChatMessage = { sessionKey, displayName, messageText ->
            if (!ScreenAutomationOverlay.isAppInForeground) {
                notificationHelper?.showMessageNotification(
                    sessionKey = sessionKey,
                    displayName = displayName,
                    messageText = messageText
                )
            }
        }

        // Update persistent notification on connection status changes
        serviceScope.launch {
            manager.connectionStatus.collectLatest { status ->
                updateServiceNotification(status)
            }
        }

        manager.connect(host, port, token)
        Log.i(TAG, "Gateway started: $host:$port")
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
