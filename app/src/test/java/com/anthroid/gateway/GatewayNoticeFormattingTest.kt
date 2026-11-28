package com.anthroid.gateway

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GatewayNoticeFormattingTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun ms(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = utc
    }.parse(value)!!.time

    @Test
    fun todayShowsSenderAndTimeInTitleOnly() {
        val notice = GatewayNotice("this is title", "Body", "openclaw", ms("2026-11-28 17:01:23"))
        assertEquals("［openclaw］this is title (17:01:23)", notice.displayTitle(Locale.US, utc, ms("2026-11-28 20:00:00")))
        assertEquals("this is title", notice.title)
        assertEquals("Body", notice.body)
    }

    @Test
    fun olderNoticeKeepsDate() {
        val notice = GatewayNotice("Title", "Body", "Agent", ms("2026-11-27 17:01:23"))
        assertEquals("［Agent］Title (2026-11-27 17:01:23)", notice.displayTitle(Locale.US, utc, ms("2026-11-28 00:00:00")))
    }

    @Test
    fun futureOtherDayAlsoKeepsDate() {
        val notice = GatewayNotice("Title", "Body", "Agent", ms("2026-11-29 01:02:03"))
        assertEquals("［Agent］Title (2026-11-29 01:02:03)", notice.displayTitle(Locale.US, utc, ms("2026-11-28 22:00:00")))
    }

    @Test
    fun localDayNotUtcDayControlsDateVisibility() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val notice = GatewayNotice("Title", "", "Agent", ms("2026-11-27 16:01:23"))
        val now = ms("2026-11-28 01:00:00")
        assertEquals("［Agent］Title (00:01:23)", notice.displayTitle(Locale.US, zone, now))
        assertEquals("［Agent］Title (2026-11-27 16:01:23)", notice.displayTitle(Locale.US, utc, now))
    }

    @Test
    fun midnightChangesTodayNoticeToDatedNotice() {
        val notice = GatewayNotice("Title", "", "Agent", ms("2026-11-28 23:59:59"))
        assertEquals("［Agent］Title (23:59:59)", notice.displayTitle(Locale.US, utc, ms("2026-11-28 23:59:59")))
        assertEquals("［Agent］Title (2026-11-28 23:59:59)", notice.displayTitle(Locale.US, utc, ms("2026-11-29 00:00:00")))
    }

    @Test
    fun sameDayOfYearInDifferentYearIsNotToday() {
        val notice = GatewayNotice("Title", "", "Agent", ms("2025-11-28 17:01:23"))
        assertEquals("［Agent］Title (2025-11-28 17:01:23)", notice.displayTitle(Locale.US, utc, ms("2026-11-28 18:00:00")))
    }

    @Test
    fun legacyMissingMetadataNeverInventsSendTime() {
        assertEquals("［Unknown sender］Title (Unknown send time)", GatewayNotice("Title", "Body").displayTitle())
        assertEquals("［Agent］Title (Unknown send time)", GatewayNotice("Title", "Body", " Agent ").displayTitle())
    }

    @Test
    fun matchingLegacyAsciiOrFullwidthPrefixIsNotDuplicated() {
        for (title in listOf("[openclaw] Title", "［openclaw］Title")) {
            assertEquals("［openclaw］Title (17:01:23)", GatewayNotice(title, "", "openclaw", ms("2026-11-28 17:01:23")).displayTitle(Locale.US, utc, ms("2026-11-28 18:00:00")))
        }
    }

    @Test
    fun unrelatedBracketedTitleIsPreserved() {
        assertEquals("［openclaw］[urgent] Title (Unknown send time)", GatewayNotice("[urgent] Title", "", "openclaw").displayTitle())
    }

    @Test
    fun senderWithRegexCharactersIsHandledLiterally() {
        assertEquals("［agent.+］Title (Unknown send time)", GatewayNotice("[agent.+] Title", "", "agent.+").displayTitle())
    }

    @Test
    fun daylightSavingFollowsSystemZoneRules() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val notice = GatewayNotice("Title", "", "Agent", ms("2024-07-01 00:00:00"))
        assertEquals("［Agent］Title (17:00:00)", notice.displayTitle(Locale.US, zone, ms("2024-07-01 01:00:00")))
    }

    @Test
    fun defaultZoneFollowsDeviceChanges() {
        val original = TimeZone.getDefault()
        try {
            val notice = GatewayNotice("Title", "", "Agent", ms("2026-11-27 16:01:23"))
            val now = ms("2026-11-28 01:00:00")
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("［Agent］Title (00:01:23)", notice.displayTitle(locale = Locale.US, nowMs = now))
            TimeZone.setDefault(utc)
            assertEquals("［Agent］Title (2026-11-27 16:01:23)", notice.displayTitle(locale = Locale.US, nowMs = now))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
