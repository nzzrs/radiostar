/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenHistoryTest {
    @Test
    fun `parses the export header and rows`() {
        val csv =
            """
            Title,Artist,Album,YouTube Video ID
            Take a Look Around,Limp Bizkit,, -wGdGUTBbqQ
            Africa,Toto,,
            """.trimIndent()

        val entries = ListenHistory.parseCsv(csv)

        assertEquals(2, entries.size)
        assertEquals("Take a Look Around", entries[0].title)
        assertEquals("Limp Bizkit", entries[0].artist)
        assertEquals("-wGdGUTBbqQ", entries[0].videoId)
        assertEquals("Africa", entries[1].title)
        assertEquals(null, entries[1].album)
    }

    @Test
    fun `accepts a file without a header`() {
        val entries = ListenHistory.parseCsv("Song,Artist,Album,id\n")

        assertEquals(1, entries.size)
        assertEquals("Song", entries[0].title)
    }

    @Test
    fun `skips rows with a blank title`() {
        val csv = "Title,Artist\n,\nGood,Artist\n"

        assertEquals(listOf("Good"), ListenHistory.parseCsv(csv).map { it.title })
    }

    @Test
    fun `quoted commas and escaped quotes survive`() {
        val csv = "Title,Artist\n\"Song, Part 2\",\"The \"\"Quoted\"\" Band\"\n"

        val entry = ListenHistory.parseCsv(csv).single()

        assertEquals("Song, Part 2", entry.title)
        assertEquals("The \"Quoted\" Band", entry.artist)
    }

    @Test
    fun `featuring credits collapse to the primary artist`() {
        assertEquals("Kendrick Lamar", ListenHistory.primaryArtist("Kendrick Lamar featuring Jay Rock"))
        assertEquals("Michael Jackson", ListenHistory.primaryArtist("Michael Jackson"))
        assertEquals("A", ListenHistory.primaryArtist("A & B"))
        assertEquals("A", ListenHistory.primaryArtist("A, B"))
    }

    @Test
    fun `candidate keys match a featuring credit`() {
        val entry = ListenHistory.Entry("Money Trees", "Kendrick Lamar featuring Jay Rock")
        val candidate =
            Candidate(
                id = "id",
                title = "Money Trees",
                artists = listOf("Kendrick Lamar", "Jay Rock"),
                vector = doubleArrayOf(1.0),
            )

        assertTrue(ListenHistory.keyFor(candidate) in ListenHistory.historyKeys(listOf(entry)))
    }

    @Test
    fun `keys are case and whitespace insensitive`() {
        assertEquals(
            ListenHistory.key("  Money   Trees ", "Kendrick Lamar"),
            ListenHistory.key("money trees", "KENDRICK LAMAR"),
        )
    }

    @Test
    fun `stored keys reject malformed values`() {
        val stored = ListenHistory.fromStored(setOf("song|artist", "", "broken", "  "))

        assertEquals(setOf("song|artist"), stored)
    }

    @Test
    fun `blank csv gives no entries`() {
        assertTrue(ListenHistory.parseCsv("").isEmpty())
        assertTrue(ListenHistory.parseCsv("\n\n").isEmpty())
    }

    @Test
    fun `bom before the header is ignored`() {
        val entries = ListenHistory.parseCsv("\uFEFFTitle,Artist\nSong,Artist\n")

        assertEquals(1, entries.size)
        assertFalse(entries[0].title.startsWith("\uFEFF"))
    }
}
