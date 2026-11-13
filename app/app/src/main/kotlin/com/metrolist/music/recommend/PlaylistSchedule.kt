/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.WeekFields

data class PlaylistRefreshPlan(
    val daily: Boolean,
    val weekly: Boolean,
)

/**
 * A daily playlist is built once per playlist day and a weekly playlist once
 * per ISO week. Repeated app starts, Home recreations or background checks do
 * not regenerate them.
 */
object PlaylistSchedule {
    /** Default playlist day start: 06:00 local time. */
    const val DAY_START_HOUR = 6L

    /** The configured day start is clamped to this range. */
    const val MIN_DAY_START_HOUR = 0L
    const val MAX_DAY_START_HOUR = 12L

    /** Background checks wake up at least this often while far from the boundary. */
    const val CHECK_TICK_MILLIS = 10 * 60_000L

    fun normalizeDayStartHour(hour: Long): Long =
        hour.coerceIn(MIN_DAY_START_HOUR, MAX_DAY_START_HOUR)

    /**
     * The playlist day starts at [dayStartHour]. Before it the previous day is
     * still current, so an app left open overnight does not rebuild at midnight.
     */
    fun scheduleDate(
        now: LocalDateTime,
        dayStartHour: Long = DAY_START_HOUR,
    ): LocalDate = now.minusHours(normalizeDayStartHour(dayStartHour)).toLocalDate()

    /** The next time the playlist day starts, at or after [now]. */
    fun nextBoundary(
        now: LocalDateTime,
        dayStartHour: Long = DAY_START_HOUR,
    ): LocalDateTime {
        val start = now.toLocalDate().atStartOfDay().plusHours(normalizeDayStartHour(dayStartHour))
        return if (now.isBefore(start)) start else start.plusDays(1)
    }

    /**
     * Delay before the next background check. It ticks every [CHECK_TICK_MILLIS]
     * while the boundary is far, then waits for the boundary itself so a session
     * left open rolls over right after the configured hour.
     */
    fun nextCheckDelayMillis(
        now: LocalDateTime,
        dayStartHour: Long = DAY_START_HOUR,
        tickMillis: Long = CHECK_TICK_MILLIS,
    ): Long {
        val untilBoundary = Duration.between(now, nextBoundary(now, dayStartHour)).toMillis() + 2_000L
        return if (untilBoundary in 1..tickMillis) untilBoundary else tickMillis
    }

    /** When a playlist of [horizon] is due again: next day start or next ISO Monday. */
    fun nextRefresh(
        now: LocalDateTime,
        dayStartHour: Long,
        horizon: PlaylistHorizon,
    ): LocalDateTime {
        var next = nextBoundary(now, dayStartHour)
        if (horizon == PlaylistHorizon.WEEKLY) {
            while (next.dayOfWeek.value != 1) next = next.plusDays(1)
        }
        return next
    }

    fun plan(
        today: LocalDate,
        lastDailyStamp: String?,
        lastWeeklyStamp: String?,
        force: Boolean = false,
    ): PlaylistRefreshPlan {
        if (force) return PlaylistRefreshPlan(daily = true, weekly = true)
        return PlaylistRefreshPlan(
            daily = lastDailyStamp != dailyStamp(today),
            weekly = lastWeeklyStamp != weeklyStamp(today),
        )
    }

    fun dailyStamp(date: LocalDate): String = date.toString()

    fun weeklyStamp(date: LocalDate): String {
        val fields = WeekFields.ISO
        return String.format(
            "%d-W%02d",
            date.get(fields.weekBasedYear()),
            date.get(fields.weekOfWeekBasedYear()),
        )
    }
}
