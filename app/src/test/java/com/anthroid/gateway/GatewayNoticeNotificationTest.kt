package com.anthroid.gateway

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GatewayNoticeNotificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun postedNotice(): Notification = shadowOf(
        context.getSystemService(NotificationManager::class.java),
    ).allNotifications.single()

    @Test
    fun systemNotificationAndTapKeepTextSenderAndTimestamp() {
        val notice = GatewayNotice("Build complete", "First line\nSecond line", "Build agent", 1_700_000_000_123L)
        GatewayNotificationHelper(context).showStandaloneNotification(notice)

        val posted = postedNotice()
        assertEquals(notice.title, posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(notice.body, posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(notice.detailText(), posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(
            notice.metadataText().replace("\n", " · "),
            posted.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString(),
        )
        assertFalse(posted.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)

        val tapIntent = shadowOf(posted.contentIntent).savedIntent
        assertFalse(tapIntent.hasExtra(GatewayNotificationHelper.EXTRA_SESSION_KEY))
        assertEquals(notice, GatewayNotificationHelper.consumeStandaloneNotice(tapIntent))
        assertNull(GatewayNotificationHelper.consumeStandaloneNotice(tapIntent))
        assertFalse(tapIntent.hasExtra(GatewayNotificationHelper.EXTRA_NOTIFICATION_SENDER))
        assertFalse(tapIntent.hasExtra(GatewayNotificationHelper.EXTRA_NOTIFICATION_SENT_AT_MS))
    }

    @Test
    fun newerLegacyNoticeDoesNotReusePreviousPendingIntentMetadata() {
        val helper = GatewayNotificationHelper(context)
        helper.showStandaloneNotification(GatewayNotice("New", "Body", "Agent", 1_700_000_000_123L))
        helper.showStandaloneNotification(GatewayNotice("Legacy", "Legacy body"))

        val posted = postedNotice()
        val expanded = posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(expanded.contains("Unknown sender"))
        assertTrue(expanded.contains("Unknown send time"))
        assertFalse(posted.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        val tapIntent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(GatewayNotice("Legacy", "Legacy body"), GatewayNotificationHelper.consumeStandaloneNotice(tapIntent))
    }

    @Test
    fun legacyTapAndMalformedTimeRemainUnknownWithoutConsumingOtherExtras() {
        val intent = Intent()
            .putExtra(GatewayNotificationHelper.EXTRA_NOTIFICATION_BODY, "Legacy body")
            .putExtra(GatewayNotificationHelper.EXTRA_NOTIFICATION_SENT_AT_MS, "not a timestamp")
            .putExtra("unrelated", "preserved")
        val notice = GatewayNotificationHelper.consumeStandaloneNotice(intent)!!
        assertEquals("OpenClaw", notice.title)
        assertNull(notice.sender)
        assertNull(notice.sentAtMs)
        assertTrue(notice.detailText().contains("Unknown send time"))
        assertEquals("preserved", intent.getStringExtra("unrelated"))
        assertFalse(intent.hasExtra(GatewayNotificationHelper.EXTRA_NOTIFICATION_SENT_AT_MS))
    }

    @Test
    fun tapPreservesEpochZeroRatherThanTreatingItAsMissing() {
        val notice = GatewayNotice("Title", "Body", sentAtMs = 0L)
        val intent = GatewayNotificationHelper.standaloneNoticeIntent(context, notice)
        assertEquals(notice, GatewayNotificationHelper.consumeStandaloneNotice(intent))
    }
}
