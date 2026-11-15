package app.picacgmd.feature.routing

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatSyncTimeTest {
    private lateinit var originalTimeZone: TimeZone

    @BeforeTest fun setTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @AfterTest fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test fun sameDayShowsTodayAndTime() {
        assertEquals(
            "今天 04:05",
            formatSyncTime(millis("2026-11-13T04:05:00"), now = millis("2026-11-13T17:18:00")),
        )
    }

    @Test fun anotherDayInTheSameYearShowsMonthDayAndTime() {
        assertEquals(
            "11月2日 09:30",
            formatSyncTime(millis("2026-11-02T09:30:00"), now = millis("2026-11-13T17:18:00")),
        )
    }

    @Test fun anotherYearShowsYearMonthAndDayWithoutTime() {
        assertEquals(
            "2025年3月1日",
            formatSyncTime(millis("2025-03-01T09:30:00"), now = millis("2026-11-13T17:18:00")),
        )
    }

    @Test fun minuteBeforeMidnightIsNotTodayAfterMidnight() {
        assertEquals(
            "11月12日 23:59",
            formatSyncTime(millis("2026-11-12T23:59:00"), now = millis("2026-11-13T00:01:00")),
        )
    }

    @Test fun minuteAfterMidnightIsToday() {
        assertEquals(
            "今天 00:01",
            formatSyncTime(millis("2026-11-13T00:01:00"), now = millis("2026-11-13T00:02:00")),
        )
    }

    @Test fun sameDayOfYearInAnotherYearIsNotToday() {
        assertEquals(
            "2025年11月13日",
            formatSyncTime(millis("2025-11-13T04:05:00"), now = millis("2026-11-13T17:18:00")),
        )
    }

    @Test fun sameDayOfMonthInAnotherMonthIsNotToday() {
        assertEquals(
            "10月13日 04:05",
            formatSyncTime(millis("2026-10-13T04:05:00"), now = millis("2026-11-13T17:18:00")),
        )
    }

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
}
