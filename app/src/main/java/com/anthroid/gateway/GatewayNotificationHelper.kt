package com.anthroid.gateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anthroid.R
import com.anthroid.main.MainPagerActivity

/**
 * Handles Android system notifications for incoming gateway messages.
 * Groups notifications by session — each session gets its own notification
 * showing the last message, with a summary when multiple sessions are active.
 */
class GatewayNotificationHelper(private val context: Context) {

    companion object {
        private const val TAG = "GatewayNotifHelper"
        const val CHANNEL_ID = "gateway_messages"
        private const val GROUP_KEY = "com.anthroid.GATEWAY_MESSAGES"
        private const val SUMMARY_NOTIFICATION_ID = 50000
        private const val SESSION_ID_BASE = 50001
        const val EXTRA_SESSION_KEY = "gateway_session_key"
    }

    private data class SessionState(
        val notificationId: Int,
        var lastMessage: String,
        var displayName: String,
    )

    private val sessionNotifications = mutableMapOf<String, SessionState>()
    private var nextNotificationId = SESSION_ID_BASE

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gateway Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Messages from OpenClaw gateway sessions"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Show or update a notification for a gateway session message.
     * Each session gets a separate notification entry showing the last message.
     */
    fun showMessageNotification(sessionKey: String, displayName: String?, messageText: String) {
        val state = sessionNotifications.getOrPut(sessionKey) {
            SessionState(
                notificationId = nextNotificationId++,
                lastMessage = messageText,
                displayName = displayName ?: sessionKey,
            )
        }
        state.lastMessage = messageText
        if (displayName != null) {
            state.displayName = displayName
        }

        val intent = Intent(context, MainPagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_KEY, sessionKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, state.notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(state.displayName)
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(pendingIntent)
            .build()

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(state.notificationId, notification)

            // Show summary notification when multiple sessions have messages
            if (sessionNotifications.size > 1) {
                val summaryNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_service_notification)
                    .setContentTitle("Anthroid")
                    .setContentText("${sessionNotifications.size} sessions with new messages")
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                manager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted: ${e.message}")
        }
    }

    fun clearSession(sessionKey: String) {
        val state = sessionNotifications.remove(sessionKey) ?: return
        NotificationManagerCompat.from(context).cancel(state.notificationId)
        if (sessionNotifications.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID)
        }
    }

    fun clearAll() {
        val manager = NotificationManagerCompat.from(context)
        sessionNotifications.values.forEach { manager.cancel(it.notificationId) }
        manager.cancel(SUMMARY_NOTIFICATION_ID)
        sessionNotifications.clear()
    }
}
