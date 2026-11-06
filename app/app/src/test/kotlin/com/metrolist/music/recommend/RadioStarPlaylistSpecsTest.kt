/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStarPlaylistSpecsTest {
    @Test
    fun `there are three daily and three weekly playlists`() {
        assertEquals(3, RadioStarPlaylistSpecs.daily.size)
        assertEquals(3, RadioStarPlaylistSpecs.weekly.size)
        assertEquals(6, RadioStarPlaylistSpecs.all.size)
    }

    @Test
    fun `ids are stable and unique`() {
        val ids = RadioStarPlaylistSpecs.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals("LP_RS_DAILY_JAMS", RadioStarPlaylistSpecs.daily.first { it.kind == MixKind.JAMS }.id)
        assertEquals("LP_RS_WEEKLY_EXPLORE", RadioStarPlaylistSpecs.weekly.first { it.kind == MixKind.EXPLORE }.id)
    }

    @Test
    fun `daily and weekly versions use different listening windows`() {
        val daily = RadioStarPlaylistSpecs.daily.first { it.kind == MixKind.DISCOVER }
        val weekly = RadioStarPlaylistSpecs.weekly.first { it.kind == MixKind.DISCOVER }

        assertTrue(weekly.historyDays > daily.historyDays)
    }

    @Test
    fun `names match the product language`() {
        assertEquals(
            listOf(
                "Daily Discover",
                "Daily Jam",
                "Daily Explore",
                "Weekly Discover",
                "Weekly Jam",
                "Weekly Explore",
            ),
            RadioStarPlaylistSpecs.all.map { it.title },
        )
    }

    @Test
    fun `lookup by id works and rejects unknown ids`() {
        assertEquals(
            RadioStarPlaylistSpecs.DAILY_JAMS_ID,
            RadioStarPlaylistSpecs.byId(RadioStarPlaylistSpecs.DAILY_JAMS_ID)?.id,
        )
        assertEquals(null, RadioStarPlaylistSpecs.byId("LP_UNKNOWN"))
    }
}
