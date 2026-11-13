/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A stable, resource-free description of when a playlist last changed. */
sealed interface PlaylistUpdateLabel {
    data class Today(val time: String) : PlaylistUpdateLabel

    data class Yesterday(val time: String) : PlaylistUpdateLabel

    data class DaysAgo(val days: Long) : PlaylistUpdateLabel

    data object Unknown : PlaylistUpdateLabel
}

/**
 * Relative labels use the playlist day, not the calendar day: an update at
 * 05:40 with a 06:00 day start still counts as yesterday.
 */
object PlaylistUpdateFormatter {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun label(
        updatedAt: LocalDateTime?,
        now: LocalDateTime,
        dayStartHour: Long = PlaylistSchedule.DAY_START_HOUR,
    ): PlaylistUpdateLabel {
        if (updatedAt == null) return PlaylistUpdateLabel.Unknown
        val today = PlaylistSchedule.scheduleDate(now, dayStartHour)
        val updatedDay = PlaylistSchedule.scheduleDate(updatedAt, dayStartHour)
        val days = ChronoUnit.DAYS.between(updatedDay, today)
        return when {
            days <= 0L -> PlaylistUpdateLabel.Today(updatedAt.format(timeFormatter))
            days == 1L -> PlaylistUpdateLabel.Yesterday(updatedAt.format(timeFormatter))
            else -> PlaylistUpdateLabel.DaysAgo(days)
        }
    }
}
