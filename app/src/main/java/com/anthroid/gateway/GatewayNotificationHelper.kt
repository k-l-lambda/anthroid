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
        const val CHANNEL_ID_STREAMING = "gateway_streaming"
        private const val CHANNEL_PREFIX = "gateway_msg_"
        private const val GROUP_KEY = "com.anthroid.GATEWAY_MESSAGES"
        private const val SUMMARY_NOTIFICATION_ID = 50000
        private const val SESSION_ID_BASE = 50001
        const val EXTRA_SESSION_KEY = "gateway_session_key"

        /**
         * Derive a notification channel tag from a sessionKey.
         * - "agent:financer:cron:xxx:run:yyy" → "financer"
         * - "agent:main:main" → "main"
         * - "agent:main:cron:xxx" → "main"
         * Label override (from session label, e.g. "Cron: Gold Monitor - AM Open (10min)"):
         * - Strip "Cron: " prefix
         * - Keep only part before " - "
         * - Limit to 3 words
         */
        fun deriveChannelTag(sessionKey: String, sessionLabel: String? = null): String {
            // Try label first (more descriptive)
            if (!sessionLabel.isNullOrBlank()) {
                var tag = sessionLabel.removePrefix("Cron:").removePrefix("Cron: ").trim()
                if (tag.contains(" - ")) tag = tag.substringBefore(" - ").trim()
                // Limit to 3 words
                val words = tag.split("\\s+".toRegex())
                if (words.size > 3) tag = words.take(3).joinToString(" ")
                if (tag.isNotBlank()) return tag
            }
            // Fallback: extract agent name from sessionKey
            val parts = sessionKey.split(":")
            return if (parts.size >= 2 && parts[0] == "agent") parts[1] else "gateway"
        }
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
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "Gateway Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Final messages from OpenClaw gateway sessions"
            })
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_STREAMING,
                "Gateway Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streaming output from OpenClaw agent runs"
            })
        }
    }

    /**
     * Get or create a dynamic notification channel for a session tag.
     * Channel ID: "gateway_msg_<sanitized_tag>"
     * Channel name: "Gateway Msg - <tag>"
     */
    private fun getOrCreateDynamicChannel(tag: String): String {
        val sanitized = tag.lowercase().replace("[^a-z0-9_]+".toRegex(), "_").take(40)
        val channelId = "$CHANNEL_PREFIX$sanitized"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    channelId,
                    "Gateway Msg - $tag",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Messages from OpenClaw: $tag"
                })
                Log.d(TAG, "Created notification channel: $channelId")
            }
        }
        return channelId
    }

    /**
     * Show or update a notification for a gateway session message.
     * Each session gets a separate notification entry showing the last message.
     */
    fun showMessageNotification(
        sessionKey: String,
        displayName: String?,
        messageText: String,
        channelId: String = CHANNEL_ID,
        sessionLabel: String? = null,
    ) {
        // Resolve dynamic channel for non-streaming messages
        val resolvedChannelId = if (channelId == CHANNEL_ID_STREAMING) {
            CHANNEL_ID_STREAMING
        } else {
            val tag = deriveChannelTag(sessionKey, sessionLabel)
            getOrCreateDynamicChannel(tag)
        }

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

        val notification = NotificationCompat.Builder(context, resolvedChannelId)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(state.displayName)
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setPriority(if (resolvedChannelId == CHANNEL_ID_STREAMING) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
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
        // Cancel tracked notifications
        sessionNotifications.values.forEach { manager.cancel(it.notificationId) }
        manager.cancel(SUMMARY_NOTIFICATION_ID)
        sessionNotifications.clear()
        // Also cancel any stale notifications from previous process by known ID range
        for (id in SESSION_ID_BASE until SESSION_ID_BASE + 50) {
            manager.cancel(id)
        }
    }
}
