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
    fun `there are three daily, three weekly and one special playlist`() {
        assertEquals(3, RadioStarPlaylistSpecs.daily.size)
        assertEquals(3, RadioStarPlaylistSpecs.weekly.size)
        assertEquals(PlaylistHorizon.SPECIAL, RadioStarPlaylistSpecs.cherry.horizon)
        assertEquals(7, RadioStarPlaylistSpecs.all.size)
    }

    @Test
    fun `ids are stable and unique`() {
        val ids = RadioStarPlaylistSpecs.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals("LP_RS_DAILY_JAMS", RadioStarPlaylistSpecs.daily.first { it.kind == MixKind.JAMS }.id)
        assertEquals("LP_RS_WEEKLY_EXPLORE", RadioStarPlaylistSpecs.weekly.first { it.kind == MixKind.EXPLORE }.id)
        assertEquals("LP_RS_CHERRY", RadioStarPlaylistSpecs.cherry.id)
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
                "DJ Cherry",
            ),
            RadioStarPlaylistSpecs.all.map { it.title },
        )
    }

    @Test
    fun `cherry is a small high-diversity playlist`() {
        val cherry = RadioStarPlaylistSpecs.cherry

        assertEquals(MixKind.CHERRY, cherry.kind)
        assertEquals(20, cherry.size)
        assertEquals(1, MixBuilder.params(MixKind.CHERRY).maxPerArtist)
        assertEquals(false, MixBuilder.params(MixKind.CHERRY).excludeHistory)
    }

    @Test
    fun `lookup by id works and rejects unknown ids`() {
        assertEquals(
            RadioStarPlaylistSpecs.CHERRY_ID,
            RadioStarPlaylistSpecs.byId(RadioStarPlaylistSpecs.CHERRY_ID)?.id,
        )
        assertEquals(null, RadioStarPlaylistSpecs.byId("LP_UNKNOWN"))
    }
}
