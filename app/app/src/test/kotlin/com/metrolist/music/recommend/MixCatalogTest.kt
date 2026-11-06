/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixCatalogTest {
    private fun entry(id: String) =
        SlateEntry(
            candidate =
                Candidate(
                    id = id,
                    title = "title-$id",
                    artists = listOf("artist-$id", "guest-$id"),
                    vector = doubleArrayOf(1.0),
                ),
            score = 0.5,
        )

    @Test
    fun `builds cards in discover jams explore order`() {
        val slates =
            mapOf(
                MixKind.EXPLORE to listOf(entry("e")),
                MixKind.JAMS to listOf(entry("j")),
                MixKind.DISCOVER to listOf(entry("d")),
            )

        val cards = MixCatalog.build(slates, "Seed")

        assertEquals(
            listOf(MixKind.DISCOVER, MixKind.JAMS, MixKind.EXPLORE),
            cards.map { it.kind },
        )
    }

    @Test
    fun `preview keeps the first three tracks`() {
        val slate = (1..5).map { entry("t$it") }

        val card = MixCatalog.build(mapOf(MixKind.JAMS to slate), null).single()

        assertEquals(3, card.tracks.size)
        assertEquals(listOf("t1", "t2", "t3"), card.tracks.map { it.id })
    }

    @Test
    fun `artists are joined for display`() {
        val card = MixCatalog.build(mapOf(MixKind.DISCOVER to listOf(entry("a"))), null).single()

        assertEquals("artist-a, guest-a", card.tracks.single().artists)
    }

    @Test
    fun `empty mixes are omitted`() {
        val cards =
            MixCatalog.build(
                mapOf(MixKind.JAMS to emptyList(), MixKind.DISCOVER to listOf(entry("d"))),
                null,
            )

        assertEquals(listOf(MixKind.DISCOVER), cards.map { it.kind })
    }

    @Test
    fun `seed title is carried to every card`() {
        val slates =
            mapOf(
                MixKind.DISCOVER to listOf(entry("d")),
                MixKind.JAMS to listOf(entry("j")),
            )

        val cards = MixCatalog.build(slates, "DEMENCIA")

        assertTrue(cards.all { it.seedTitle == "DEMENCIA" })
    }
}
