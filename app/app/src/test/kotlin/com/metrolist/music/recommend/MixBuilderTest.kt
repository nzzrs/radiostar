/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixBuilderTest {
    private val session = doubleArrayOf(1.0, 0.0, 0.0)

    private fun candidate(id: String, artist: String, vararg vector: Double) =
        Candidate(
            id = id,
            title = "title-$id",
            artists = listOf(artist),
            vector = vector.copyOf(),
        )

    @Test
    fun `discover drops tracks already in history`() {
        val pool =
            listOf(
                candidate("a", "A", 1.0, 0.0, 0.0),
                candidate("b", "B", 0.0, 1.0, 0.0),
                candidate("c", "C", 0.0, 0.0, 1.0),
            )

        val slate = MixBuilder.build(session, pool, MixKind.DISCOVER, k = 3, historyIds = setOf("b"))

        assertEquals(2, slate.size)
        assertFalse(slate.any { it.candidate.id == "b" })
    }

    @Test
    fun `discover drops tracks matched by a ListenBrainz history key`() {
        val pool =
            listOf(
                candidate("a", "A", 1.0, 0.0, 0.0),
                candidate("b", "B", 0.0, 1.0, 0.0),
            )
        val heard = ListenHistory.historyKeys(listOf(ListenHistory.Entry("title-a", "A")))

        val slate =
            MixBuilder.build(
                session,
                pool,
                MixKind.DISCOVER,
                k = 2,
                historyKeys = heard,
            )

        assertEquals(listOf("b"), slate.map { it.candidate.id })
    }

    @Test
    fun `jams ignores the history key`() {
        val pool =
            listOf(
                candidate("a", "A", 1.0, 0.0, 0.0),
                candidate("b", "B", 0.0, 1.0, 0.0),
            )
        val heard = ListenHistory.historyKeys(listOf(ListenHistory.Entry("title-a", "A")))

        val slate = MixBuilder.build(session, pool, MixKind.JAMS, k = 2, historyKeys = heard)

        assertEquals(2, slate.size)
    }

    @Test
    fun `discover drops the seed artist`() {
        val pool =
            listOf(
                candidate("a", "Seed", 1.0, 0.0, 0.0),
                candidate("b", "Other", 0.0, 1.0, 0.0),
            )

        val slate = MixBuilder.build(session, pool, MixKind.DISCOVER, k = 2, seedArtist = "Seed")

        assertEquals(listOf("b"), slate.map { it.candidate.id })
    }

    @Test
    fun `jams keeps history and the seed artist`() {
        val pool =
            listOf(
                candidate("a", "Seed", 1.0, 0.0, 0.0),
                candidate("b", "Other", 0.0, 1.0, 0.0),
            )

        val slate =
            MixBuilder.build(
                session,
                pool,
                MixKind.JAMS,
                k = 2,
                historyIds = setOf("a"),
                seedArtist = "Seed",
            )

        assertEquals(2, slate.size)
    }

    @Test
    fun `artist cap is enforced and refilled`() {
        val pool =
            listOf(
                candidate("a1", "A", 1.0, 0.0, 0.0),
                candidate("a2", "A", 0.9, 0.1, 0.0),
                candidate("a3", "A", 0.8, 0.2, 0.0),
                candidate("b1", "B", 0.0, 1.0, 0.0),
            )

        val discover = MixBuilder.build(session, pool, MixKind.DISCOVER, k = 3)
        assertEquals(3, discover.size)
        assertTrue(discover.count { it.candidate.artists.first() == "A" } <= 2)

        val explore = MixBuilder.build(session, pool, MixKind.EXPLORE, k = 3)
        assertEquals(2, explore.size)
        assertEquals(1, explore.count { it.candidate.artists.first() == "A" })
    }

    @Test
    fun `slate never exceeds k`() {
        val pool = (0 until 20).map { index -> candidate("c$index", "artist-$index", 1.0, index * 0.01, 0.0) }

        assertEquals(5, MixBuilder.build(session, pool, MixKind.JAMS, k = 5).size)
    }

    @Test
    fun `highest affinity ranks first`() {
        val pool =
            listOf(
                candidate("near", "A", 1.0, 0.0, 0.0),
                candidate("far", "B", 0.0, 1.0, 0.0),
            )

        val slate = MixBuilder.build(session, pool, MixKind.DISCOVER, k = 1)
        assertEquals("near", slate.first().candidate.id)
    }

    @Test
    fun `empty pool gives an empty slate`() {
        assertTrue(MixBuilder.build(session, emptyList(), MixKind.DISCOVER).isEmpty())
    }

    @Test
    fun `telemetry bias can promote a track the listener finishes`() {
        val pool =
            listOf(
                candidate("near", "A", 1.0, 0.0, 0.0),
                candidate("liked", "B", 0.9, 0.1, 0.0),
            )
        val bias = TelemetryBias(songScores = mapOf("liked" to 1.0))

        val slate = MixBuilder.build(session, pool, MixKind.JAMS, k = 1, bias = bias)

        assertEquals("liked", slate.first().candidate.id)
    }

    @Test
    fun `telemetry bias can demote a track the listener skips`() {
        val pool =
            listOf(
                candidate("skipped", "A", 1.0, 0.0, 0.0),
                candidate("other", "B", 0.9, 0.1, 0.0),
            )
        val bias = TelemetryBias(songScores = mapOf("skipped" to -1.0))

        val slate = MixBuilder.build(session, pool, MixKind.JAMS, k = 1, bias = bias)

        assertEquals("other", slate.first().candidate.id)
    }

    @Test
    fun `selection is deterministic`() {
        val pool = (0 until 12).map { index -> candidate("c$index", "artist-${index % 4}", 1.0, index * 0.1, 0.0) }

        val first = MixBuilder.build(session, pool, MixKind.EXPLORE, k = 6).map { it.candidate.id }
        val second = MixBuilder.build(session, pool, MixKind.EXPLORE, k = 6).map { it.candidate.id }

        assertEquals(first, second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty session is rejected`() {
        MixBuilder.build(DoubleArray(0), listOf(candidate("a", "A", 1.0, 0.0, 0.0)), MixKind.DISCOVER)
    }
}
