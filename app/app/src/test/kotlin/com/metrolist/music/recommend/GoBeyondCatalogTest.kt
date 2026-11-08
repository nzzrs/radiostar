/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBeyondCatalogTest {
    @Test
    fun `catalog has every requested section`() {
        assertEquals(GoBeyondSection.entries.toSet(), GoBeyondCatalog.sections.toSet())
        assertTrue(GoBeyondCatalog.countries.isNotEmpty())
        assertTrue(GoBeyondCatalog.genres.isNotEmpty())
        assertTrue(GoBeyondCatalog.colors.isNotEmpty())
        assertTrue(GoBeyondCatalog.moods.isNotEmpty())
        assertEquals(8, GoBeyondCatalog.periods.size)
    }

    @Test
    fun `predefined period renders its decade`() {
        val period = GoBeyondCatalog.period(1980, 1989)

        assertEquals("1980s", period.title)
        assertEquals(1980, period.selection.fromYear)
        assertEquals(1989, period.selection.toYear)
    }

    @Test
    fun `single facet title round trips through the gateway parser`() {
        val selection = GoBeyondSelection(country = "Brazil")

        assertEquals("Go Beyond · Brazil", selection.playlistName())
        assertEquals(listOf("Brazil"), GoBeyondGateway.facets(selection.playlistName()))
        assertEquals("Brazil", selection.displayTitle())
    }

    @Test
    fun `custom combination keeps every facet and searches for all of them`() {
        val selection =
            GoBeyondSelection(
                genre = "Shoegaze",
                fromYear = 1980,
                toYear = 1989,
                color = "Purple",
                mood = "Dreamy",
            )

        val query = selection.searchQueries().first()
        assertTrue(query.contains("Shoegaze"))
        assertTrue(query.contains("1980s"))
        assertTrue(query.contains("Purple"))
        assertTrue(query.contains("Dreamy"))
        assertTrue(selection.semanticPrompt().contains("purple"))
        assertEquals(
            listOf("Shoegaze", "1980s", "Purple", "Dreamy"),
            GoBeyondGateway.facets(selection.playlistName()),
        )
    }

    @Test
    fun `stable id changes when the journey changes`() {
        val first = GoBeyondSelection(country = "Brazil")
        val same = GoBeyondSelection(country = "Brazil")
        val different = GoBeyondSelection(country = "Brazil", mood = "Calm")

        assertEquals(first.stableId(), same.stableId())
        assertNotEquals(first.stableId(), different.stableId())
        assertTrue(first.stableId().startsWith("LP_RS_GO_"))
    }

    @Test
    fun `artist cards come from the supplied listener artists`() {
        val cards = GoBeyondCatalog.artistCards(listOf("Björk", "Fela Kuti", "Björk"))

        assertEquals(listOf("Björk", "Fela Kuti"), cards.map { it.title })
        assertTrue(cards.first().selection.artist == "Björk")
    }

    @Test
    fun `selection codec round trips a custom journey`() {
        val selection =
            GoBeyondSelection(
                country = "Brazil",
                genre = "Shoegaze",
                fromYear = 1980,
                toYear = 1989,
                color = "Purple",
                mood = "Dreamy",
                freeText = "night drive",
            )

        assertEquals(selection, GoBeyondSelectionCodec.decode(GoBeyondSelectionCodec.encode(selection)))
        assertEquals(null, GoBeyondSelectionCodec.decode("not json"))
    }
}
