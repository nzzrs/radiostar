/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistScheduleTest {
    private val today = LocalDate.of(2026, 11, 6)

    @Test
    fun `first run generates both horizons`() {
        val plan = PlaylistSchedule.plan(today, lastDailyStamp = null, lastWeeklyStamp = null)

        assertTrue(plan.daily)
        assertTrue(plan.weekly)
    }

    @Test
    fun `the same day and week does nothing`() {
        val plan =
            PlaylistSchedule.plan(
                today,
                lastDailyStamp = PlaylistSchedule.dailyStamp(today),
                lastWeeklyStamp = PlaylistSchedule.weeklyStamp(today),
            )

        assertFalse(plan.daily)
        assertFalse(plan.weekly)
    }

    @Test
    fun `the next day only rebuilds the daily lists`() {
        val plan =
            PlaylistSchedule.plan(
                today,
                lastDailyStamp = PlaylistSchedule.dailyStamp(today.minusDays(1)),
                lastWeeklyStamp = PlaylistSchedule.weeklyStamp(today),
            )

        assertTrue(plan.daily)
        assertFalse(plan.weekly)
    }

    @Test
    fun `the next week rebuilds the weekly lists`() {
        val plan =
            PlaylistSchedule.plan(
                today,
                lastDailyStamp = PlaylistSchedule.dailyStamp(today),
                lastWeeklyStamp = PlaylistSchedule.weeklyStamp(today.minusWeeks(1)),
            )

        assertFalse(plan.daily)
        assertTrue(plan.weekly)
    }

    @Test
    fun `a failure that does not write the stamp retries next time`() {
        val plan = PlaylistSchedule.plan(today, lastDailyStamp = null, lastWeeklyStamp = null)

        assertTrue(plan.daily)
    }

    @Test
    fun `force regenerates both`() {
        val plan =
            PlaylistSchedule.plan(
                today,
                lastDailyStamp = PlaylistSchedule.dailyStamp(today),
                lastWeeklyStamp = PlaylistSchedule.weeklyStamp(today),
                force = true,
            )

        assertTrue(plan.daily)
        assertTrue(plan.weekly)
    }

    @Test
    fun `iso week wraps at the year boundary`() {
        assertEquals("2025-W52", PlaylistSchedule.weeklyStamp(LocalDate.of(2025, 12, 28)))
        assertEquals("2026-W01", PlaylistSchedule.weeklyStamp(LocalDate.of(2025, 12, 29)))
    }
}
