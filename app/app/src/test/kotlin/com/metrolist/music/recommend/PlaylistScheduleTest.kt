/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalDate
import java.time.LocalDateTime
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
    fun `playlist day rolls over at six in the morning`() {
        assertEquals(
            LocalDate.of(2026, 11, 5),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 6, 5, 59)),
        )
        assertEquals(
            LocalDate.of(2026, 11, 6),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 6, 6, 0)),
        )
    }

    @Test
    fun `a custom day start moves the playlist day boundary`() {
        assertEquals(
            LocalDate.of(2026, 11, 5),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 6, 7, 59), 8L),
        )
        assertEquals(
            LocalDate.of(2026, 11, 6),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 6, 8, 0), 8L),
        )
    }

    @Test
    fun `a midnight day start follows the calendar`() {
        assertEquals(
            LocalDate.of(2026, 11, 6),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 6, 0, 0), 0L),
        )
        assertEquals(
            LocalDate.of(2026, 11, 5),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 5, 23, 59), 0L),
        )
    }

    @Test
    fun `the day start hour is clamped to the supported range`() {
        assertEquals(
            LocalDate.of(2026, 11, 5),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 6, 11, 59), 18L),
        )
        assertEquals(
            LocalDate.of(2026, 11, 6),
            PlaylistSchedule.scheduleDate(LocalDateTime.of(2026, 11, 6, 0, 0), -3L),
        )
    }

    @Test
    fun `the next check lands on the configured boundary`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 6, 8, 0),
            PlaylistSchedule.nextBoundary(LocalDateTime.of(2026, 11, 6, 7, 30), 8L),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 7, 8, 0),
            PlaylistSchedule.nextBoundary(LocalDateTime.of(2026, 11, 6, 9, 0), 8L),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 7, 0, 0),
            PlaylistSchedule.nextBoundary(LocalDateTime.of(2026, 11, 6, 0, 0), 0L),
        )
    }

    @Test
    fun `the periodic check waits for a boundary that is close`() {
        val now = LocalDateTime.of(2026, 11, 6, 5, 55)

        assertEquals(5 * 60_000L + 2_000L, PlaylistSchedule.nextCheckDelayMillis(now, 6L))
    }

    @Test
    fun `the periodic check falls back to the tick when the boundary is far`() {
        val now = LocalDateTime.of(2026, 11, 6, 13, 0)

        assertEquals(10 * 60_000L, PlaylistSchedule.nextCheckDelayMillis(now, 6L))
    }

    @Test
    fun `weekly refresh waits for the next iso monday`() {
        val now = LocalDateTime.of(2026, 11, 6, 9, 0)

        assertEquals(
            LocalDateTime.of(2026, 11, 9, 6, 0),
            PlaylistSchedule.nextRefresh(now, 6L, PlaylistHorizon.WEEKLY),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 7, 6, 0),
            PlaylistSchedule.nextRefresh(now, 6L, PlaylistHorizon.DAILY),
        )
    }

    @Test
    fun `iso week wraps at the year boundary`() {
        assertEquals("2025-W52", PlaylistSchedule.weeklyStamp(LocalDate.of(2025, 12, 28)))
        assertEquals("2026-W01", PlaylistSchedule.weeklyStamp(LocalDate.of(2025, 12, 29)))
    }
}
