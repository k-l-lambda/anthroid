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
        const val CHANNEL_ID_QUESTION = "gateway_questions"
        const val CHANNEL_ID_NOTICE = "gateway_notices"
        private const val CHANNEL_PREFIX = "gateway_msg_"
        private const val GROUP_KEY = "com.anthroid.GATEWAY_MESSAGES"
        private const val SUMMARY_NOTIFICATION_ID = 50000
        private const val SESSION_ID_BASE = 50001
        // Question notifications live in their own id range so clearAll() on the
        // message range cannot silently dismiss a question an agent is blocked on.
        private const val QUESTION_ID_BASE = 51000
        private const val MAX_QUESTION_NOTIFICATIONS = 20

        // Session-less notifications (notification.push) get their own id and channel.
        // They must NOT reuse the message range: those deep-link to a remote session,
        // and a push has no session to open.
        private const val STANDALONE_NOTIFICATION_ID = 50999
        const val EXTRA_SESSION_KEY = "gateway_session_key"
        const val EXTRA_QUESTION_ID = "gateway_question_id"
        const val EXTRA_NOTIFICATION_TITLE = "gateway_notification_title"
        const val EXTRA_NOTIFICATION_BODY = "gateway_notification_body"

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
            // Agent-scoped notification keys intentionally use the agent name so all
            // cron jobs for that agent share one Android notification channel.
            val parts = sessionKey.split(":")
            if (parts.size == 2 && parts[0] == "agent" && parts[1].isNotBlank()) {
                return parts[1]
            }
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
            return if (parts.size >= 2 && parts[0] == "agent") parts[1] else "gateway"
        }
    }

    private data class SessionState(
        val notificationId: Int,
        var lastMessage: String,
        var displayName: String,
        val messages: ArrayDeque<String> = ArrayDeque(),
        var deepLinkSessionKey: String,
        var isStreaming: Boolean,
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
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_QUESTION,
                "Agent Questions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "An agent is blocked waiting for your answer"
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
        deepLinkSessionKey: String = sessionKey,
        isStreaming: Boolean = false,
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
                deepLinkSessionKey = deepLinkSessionKey,
                isStreaming = isStreaming,
            )
        }
        state.lastMessage = messageText
        state.deepLinkSessionKey = deepLinkSessionKey
        if (state.isStreaming != isStreaming) {
            state.messages.clear()
            state.isStreaming = isStreaming
        }
        state.messages.addLast(messageText)
        while (state.messages.size > 8) state.messages.removeFirst()
        if (displayName != null) {
            state.displayName = displayName
        }

        val intent = Intent(context, MainPagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_KEY, state.deepLinkSessionKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, state.notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, resolvedChannelId)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(state.displayName)
            .setContentText(messageText)
            .setPriority(if (resolvedChannelId == CHANNEL_ID_STREAMING) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(pendingIntent)
        if (state.messages.size > 1) {
            notificationBuilder.setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    state.messages.forEach { style.addLine(it) }
                    style.setSummaryText("${state.messages.size} recent messages")
                }
            )
        } else {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
        }
        val notification = notificationBuilder.build()

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(state.notificationId, notification)

            // Show summary notification when multiple sessions have messages
            if (sessionNotifications.size > 1) {
                val summaryNotification = NotificationCompat.Builder(context, resolvedChannelId)
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

    /**
     * Show a notification that belongs to no session (gateway `notification.push`).
     *
     * Deliberately NOT routed through showMessageNotification: that one deep-links to
     * a remote session, so a push landed on a session key of "notification" that does
     * not exist on the gateway and the tap opened an empty session view. The body text
     * travels in the intent instead, so the tap can show the text it already delivered.
     */
    fun showStandaloneNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID_NOTICE) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_NOTICE,
                        "Gateway Notices",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "Standalone notices from OpenClaw, not tied to a session" }
                )
            }
        }

        val intent = Intent(context, MainPagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TITLE, title)
            putExtra(EXTRA_NOTIFICATION_BODY, body)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, STANDALONE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_NOTICE)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(STANDALONE_NOTIFICATION_ID, notification)
            Log.i(TAG, "Standalone notification shown (${body.length} chars)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted: ${e.message}")
        }
    }

    private val questionNotifications = mutableMapOf<String, Int>()
    private var nextQuestionNotificationId = QUESTION_ID_BASE

    /**
     * Show a notification for a pending agent question. Tapping it opens
     * AskUserQuestionActivity via MainPagerActivity's deep-link handler.
     *
     * Deliberately NOT auto-cancel: the question stays visible until it is answered
     * or reaches a terminal state, because dismissing the notification does not
     * answer the question and the agent is still blocked.
     */
    fun showQuestionNotification(
        questionId: String,
        headline: String,
        body: String,
        sessionKey: String?,
    ) {
        val notificationId = questionNotifications.getOrPut(questionId) {
            nextQuestionNotificationId++.also {
                if (nextQuestionNotificationId >= QUESTION_ID_BASE + MAX_QUESTION_NOTIFICATIONS) {
                    nextQuestionNotificationId = QUESTION_ID_BASE
                }
            }
        }

        val intent = Intent(context, MainPagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_QUESTION_ID, questionId)
            if (sessionKey != null) putExtra(EXTRA_SESSION_KEY, sessionKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_QUESTION)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(headline)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            Log.i(TAG, "Question notification shown: $questionId (id=$notificationId)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted: ${e.message}")
        }
    }

    /** Dismiss a question notification once the question reached a terminal state. */
    fun clearQuestion(questionId: String) {
        val notificationId = questionNotifications.remove(questionId) ?: return
        NotificationManagerCompat.from(context).cancel(notificationId)
        Log.d(TAG, "Question notification cleared: $questionId")
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
