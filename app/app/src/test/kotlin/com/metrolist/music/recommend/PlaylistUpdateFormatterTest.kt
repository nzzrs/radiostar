/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistUpdateFormatterTest {
    private val now = LocalDateTime.of(2026, 11, 6, 9, 30)

    @Test
    fun `an update on the same playlist day is today`() {
        assertEquals(
            PlaylistUpdateLabel.Today("07:12"),
            PlaylistUpdateFormatter.label(LocalDateTime.of(2026, 11, 6, 7, 12), now, 6L),
        )
    }

    @Test
    fun `the hours before the day start still belong to yesterday`() {
        assertEquals(
            PlaylistUpdateLabel.Yesterday("05:40"),
            PlaylistUpdateFormatter.label(LocalDateTime.of(2026, 11, 6, 5, 40), now, 6L),
        )
    }

    @Test
    fun `the previous playlist day is yesterday`() {
        assertEquals(
            PlaylistUpdateLabel.Yesterday("18:05"),
            PlaylistUpdateFormatter.label(LocalDateTime.of(2026, 11, 5, 18, 5), now, 6L),
        )
    }

    @Test
    fun `older updates report the day count`() {
        assertEquals(
            PlaylistUpdateLabel.DaysAgo(3),
            PlaylistUpdateFormatter.label(LocalDateTime.of(2026, 11, 3, 21, 0), now, 6L),
        )
    }

    @Test
    fun `a missing update time is unknown`() {
        assertEquals(
            PlaylistUpdateLabel.Unknown,
            PlaylistUpdateFormatter.label(null, now, 6L),
        )
    }
}
