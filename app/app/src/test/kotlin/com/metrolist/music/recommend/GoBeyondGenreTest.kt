/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBeyondGenreTest {
    private data class Candidate(
        val id: String,
        val artist: String,
        val tags: Set<String>?,
    )

    @Test
    fun `genre tags follow the catalog aliases`() {
        assertTrue(GoBeyondGenre.tagsFor("Rock").contains("rock"))
        assertTrue(GoBeyondGenre.tagsFor("Hip-Hop").contains("hip hop"))
        assertTrue(GoBeyondGenre.tagsFor("Hip-Hop").contains("rap"))
        assertTrue(GoBeyondGenre.tagsFor("Drum and Bass").contains("drum and bass"))
    }

    @Test
    fun `matching accepts specific and parent tags`() {
        assertTrue(GoBeyondGenre.matches("Rock", listOf("alternative rock", "nu metal")))
        assertTrue(GoBeyondGenre.matches("Metal", listOf("heavy metal")))
        assertTrue(GoBeyondGenre.matches("Hip-Hop", listOf("hip hop", "rap")))
        assertFalse(GoBeyondGenre.matches("Rock", listOf("cumbia", "chicha")))
    }

    @Test
    fun `unknown tags do not exclude a track`() {
        assertTrue(GoBeyondGenre.accepts("Rock", null))
        assertTrue(GoBeyondGenre.accepts("Rock", emptySet()))
        assertFalse(GoBeyondGenre.accepts("Rock", setOf("cumbia")))
    }

    @Test
    fun `filter drops known wrong genre and fills only from unknown`() {
        val items =
            listOf(
                Candidate("1", "Los Saicos", setOf("rock", "garage rock")),
                Candidate("2", "Cumbia Peruana", setOf("cumbia", "chicha")),
                Candidate("3", "Unknown One", null),
                Candidate("4", "Unlisted Artist", emptySet()),
                Candidate("5", "Another Unknown", null),
            )

        val result =
            GoBeyondGenre.filter(
                genre = "Rock",
                items = items,
                tagsOf = { it.tags },
                fillTo = 2,
            )

        assertEquals(listOf("1", "3"), result.accepted.map { it.id })
        assertEquals(1, result.rejected)
        assertEquals(1, result.verified)
    }

    @Test
    fun `filter keeps only verified tracks when there are enough`() {
        val items =
            listOf(
                Candidate("1", "A", setOf("rock")),
                Candidate("2", "B", setOf("rock")),
                Candidate("3", "C", null),
            )

        val result =
            GoBeyondGenre.filter(
                genre = "Rock",
                items = items,
                tagsOf = { it.tags },
                fillTo = 2,
            )

        assertEquals(listOf("1", "2"), result.accepted.map { it.id })
    }
}
