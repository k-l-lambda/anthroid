package com.anthroid.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class GatewayNoticeFormattingTest {
    @Test
    fun absoluteTimeIncludesDateSecondsZoneAndOffset() {
        val notice = GatewayNotice("Title", "Body", "Build agent", 1_700_000_000_123L)
        assertEquals(
            "From: Build agent\nSent: 2023-11-14 22:13:20 UTC (+0000)",
            notice.metadataText(Locale.US, TimeZone.getTimeZone("UTC")),
        )
    }

    @Test
    fun localTimeRespectsDateRolloverAndDaylightSaving() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        assertEquals(
            "From: Agent\nSent: 1969-12-31 16:00:00 PST (-0800)",
            GatewayNotice("", "", "Agent", 0L).metadataText(Locale.US, zone),
        )
        assertEquals(
            "From: Agent\nSent: 2024-06-30 17:00:00 PDT (-0700)",
            GatewayNotice("", "", "Agent", 1_719_792_000_000L).metadataText(Locale.US, zone),
        )
    }

    @Test
    fun legacyMetadataIsExplicitlyUnknown() {
        assertEquals(
            "From: Unknown sender\nSent: Unknown send time",
            GatewayNotice("Title", "Body").metadataText(),
        )
        assertEquals("Unknown sender", GatewayNotice("", "", " \n ").displaySender)
    }

    @Test
    fun senderDoesNotImplyAKnownSendTime() {
        assertEquals(
            "From: Agent\nSent: Unknown send time",
            GatewayNotice("Title", "Body", " Agent ").metadataText(),
        )
    }

    @Test
    fun detailKeepsOriginalTitleAndMultilineBodyReadable() {
        val notice = GatewayNotice("Original title", "Line one\nLine two")
        assertEquals("Original title", notice.title)
        assertEquals(
            "From: Unknown sender\nSent: Unknown send time\n\nLine one\nLine two",
            notice.detailText(),
        )
    }

    @Test
    fun defaultFormattingUsesCurrentDeviceTimeZone() {
        val originalZone = TimeZone.getDefault()
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val notice = GatewayNotice("", "", sentAtMs = 1_700_000_000_123L)
            assertTrue(notice.detailText().contains("2023-11-15 06:13:20 CST (+0800)"))
            // Reformat when opened after a device-zone change, not at receipt time.
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertTrue(notice.detailText().contains("2023-11-14 22:13:20 UTC (+0000)"))
        } finally {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        }
    }
}
