package ro.alintudor.oracle.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Permanent, rule-based NYSE full-closure calendar plus regular/early-close session hours. */
object OracleMarketCalendar {
    private val NEW_YORK = ZoneId.of("America/New_York")
    private val OPEN_TIME = LocalTime.of(9, 30)
    private val CLOSE_TIME = LocalTime.of(16, 0)
    private val EARLY_CLOSE_TIME = LocalTime.of(13, 0)
    private val BUCHAREST = ZoneId.of("Europe/Bucharest")

    /**
     * The single canonical Growth snapshot anchor: 16:00 Romania time on the
     * most recent valid trading day. Every place that needs this value (freeze
     * checks, bootstrap migration, live-data validation) must call this exact
     * function — previously three separate files each had their own copy of
     * this logic, which is a real risk of silent divergence even though they
     * currently compute the same thing.
     */
    fun growthAnchor(nowMillis: Long): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(BUCHAREST)
        var date = if (now.toLocalTime().isBefore(LocalTime.of(16, 0))) now.toLocalDate().minusDays(1) else now.toLocalDate()
        while (!isTradingDay(date)) date = date.minusDays(1)
        return ZonedDateTime.of(date, LocalTime.of(16, 0), BUCHAREST).toInstant().toEpochMilli()
    }

    data class Status(
        val open: Boolean,
        val label: String,
        val countdown: String
    )

    fun isTradingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY &&
            date.dayOfWeek != DayOfWeek.SUNDAY &&
            fullClosureName(date) == null

    fun fullClosureName(date: LocalDate): String? {
        observedFixedHolidays(date.year)[date]?.let { return it }
        if (date == nthWeekday(date.year, Month.JANUARY, DayOfWeek.MONDAY, 3)) return "Martin Luther King Jr. Day"
        if (date == nthWeekday(date.year, Month.FEBRUARY, DayOfWeek.MONDAY, 3)) return "Presidents' Day"
        if (date == easterSunday(date.year).minusDays(2)) return "Good Friday"
        if (date == lastWeekday(date.year, Month.MAY, DayOfWeek.MONDAY)) return "Memorial Day"
        if (date == nthWeekday(date.year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1)) return "Labor Day"
        if (date == nthWeekday(date.year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)) return "Thanksgiving Day"
        return null
    }

    /** NYSE early-close sessions relevant to the published 2026 calendar. */
    private fun earlyCloseTime(date: LocalDate): LocalTime? = when (date) {
        LocalDate.of(2026, Month.JULY, 3),
        LocalDate.of(2026, Month.NOVEMBER, 27),
        LocalDate.of(2026, Month.DECEMBER, 24) -> EARLY_CLOSE_TIME
        else -> null
    }

    fun sessionCloseTime(date: LocalDate): LocalTime = earlyCloseTime(date) ?: CLOSE_TIME

    fun status(nowMillis: Long = System.currentTimeMillis()): Status {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), NEW_YORK)
        val date = now.toLocalDate()

        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            return closedStatus(now, nextTradingOpen(now))
        }
        fullClosureName(date)?.let {
            return closedStatus(now, nextTradingOpen(now))
        }

        val open = ZonedDateTime.of(date, OPEN_TIME, NEW_YORK)
        val close = ZonedDateTime.of(date, sessionCloseTime(date), NEW_YORK)
        return when {
            now.isBefore(open) -> closedStatus(now, open)
            now.isBefore(close) -> Status(true, "MARKET IS OPEN", "${formatRemaining(now, close)} until close.")
            else -> closedStatus(now, nextTradingOpen(now))
        }
    }

    private fun closedStatus(now: ZonedDateTime, target: ZonedDateTime): Status =
        Status(false, "MARKET IS CLOSED", "${formatRemaining(now, target)} until open.")

    private fun nextTradingOpen(now: ZonedDateTime): ZonedDateTime {
        var date = now.toLocalDate().plusDays(1)
        while (!isTradingDay(date)) date = date.plusDays(1)
        return ZonedDateTime.of(date, OPEN_TIME, NEW_YORK)
    }

    private fun formatRemaining(from: ZonedDateTime, target: ZonedDateTime): String {
        val seconds = Duration.between(from, target).seconds.coerceAtLeast(0L)
        val totalMinutes = (seconds + 59L) / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return "$hours hours and $minutes minutes"
    }

    private fun observedFixedHolidays(year: Int): Map<LocalDate, String> {
        val fixed = listOf(
            Month.JANUARY to 1 to "New Year's Day",
            Month.JUNE to 19 to "Juneteenth",
            Month.JULY to 4 to "Independence Day",
            Month.DECEMBER to 25 to "Christmas Day"
        )
        return buildMap {
            for ((pair, name) in fixed) {
                val actual = LocalDate.of(year, pair.first, pair.second)
                val observed = when (actual.dayOfWeek) {
                    // Independence Day 2026 is an NYSE early-close day, not a full closure.
                    DayOfWeek.SATURDAY -> if (pair.first == Month.JULY && pair.second == 4 && year == 2026) actual else actual.minusDays(1)
                    DayOfWeek.SUNDAY -> actual.plusDays(1)
                    else -> actual
                }
                if (!(year == 2026 && pair.first == Month.JULY && pair.second == 4)) put(observed, name)
            }
        }
    }

    private fun nthWeekday(year: Int, month: Month, day: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    private fun lastWeekday(year: Int, month: Month, day: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(day))

    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }
}
