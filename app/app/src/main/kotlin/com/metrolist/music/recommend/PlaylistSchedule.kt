/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalDate
import java.time.temporal.WeekFields

data class PlaylistRefreshPlan(
    val daily: Boolean,
    val weekly: Boolean,
)

/**
 * A daily playlist is built once per calendar day and a weekly playlist once
 * per ISO week. Repeated app starts, Home recreations or background checks do
 * not regenerate them.
 */
object PlaylistSchedule {
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
