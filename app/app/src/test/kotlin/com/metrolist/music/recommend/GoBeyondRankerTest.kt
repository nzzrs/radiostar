/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBeyondRankerTest {
    private fun candidate(
        id: String,
        artist: String = "Artist",
        vector: DoubleArray? = null,
    ) = GoBeyondCandidate(
        id = id,
        title = "Title $id",
        artists = listOf(artist),
        durationSeconds = 200,
        vector = vector,
    )

    @Test
    fun `positive telemetry lifts a candidate`() {
        val bias =
            TelemetryBias(
                songScores = mapOf("good" to 1.0),
                artistScores = emptyMap(),
            )
        val ranked =
            GoBeyondRanker.rank(
                listOf(candidate("good"), candidate("plain")),
                GoBeyondRankContext(bias = bias),
            )

        assertEquals("good", ranked.first().candidate.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `skipped and recently played songs fall behind fresh ones`() {
        val context =
            GoBeyondRankContext(
                recentIds = setOf("recent"),
                skippedIds = setOf("skipped"),
                maxPerArtist = 3,
            )
        val ranked =
            GoBeyondRanker.rank(
                listOf(candidate("recent"), candidate("skipped"), candidate("fresh")),
                context,
            )

        assertEquals("fresh", ranked.first().candidate.id)
        assertTrue(ranked.first { it.candidate.id == "skipped" }.score < 0.0)
        assertFalse(ranked.first().candidate.id in setOf("recent", "skipped"))
    }

    @Test
    fun `one artist cannot fill the playlist`() {
        val ranked =
            GoBeyondRanker.rank(
                listOf(
                    candidate("a1", "One"),
                    candidate("a2", "One"),
                    candidate("a3", "One"),
                    candidate("b1", "Two"),
                ),
                GoBeyondRankContext(maxPerArtist = 1, limit = 4),
            )

        assertEquals(2, ranked.size)
        assertEquals(1, ranked.count { it.candidate.artists.first() == "One" })
    }

    @Test
    fun `semantic prompt vector rewards close candidates`() {
        val prompt = doubleArrayOf(1.0, 0.0)
        val ranked =
            GoBeyondRanker.rank(
                listOf(
                    candidate("far", vector = doubleArrayOf(-1.0, 0.0)),
                    candidate("close", vector = doubleArrayOf(1.0, 0.0)),
                ),
                GoBeyondRankContext(promptVector = prompt),
            )

        assertEquals("close", ranked.first().candidate.id)
        assertTrue(ranked.first().score > 0.5)
    }

    @Test
    fun `novelty appetite rewards unheard artists`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    TelemetryStore.Event(
                        type = TelemetryEventType.COMPLETE,
                        songId = "known",
                        title = "Known",
                        artist = "Known",
                        playlist = null,
                        seed = null,
                        positionMs = null,
                        durationMs = null,
                        completed = true,
                        at = System.currentTimeMillis(),
                    ),
                ),
            )
        val context =
            GoBeyondRankContext(
                heardIds = setOf("known"),
                insights = insights,
            )
        val ranked =
            GoBeyondRanker.rank(listOf(candidate("known", "Known"), candidate("new", "New")), context)

        assertEquals("new", ranked.first().candidate.id)
    }
}
