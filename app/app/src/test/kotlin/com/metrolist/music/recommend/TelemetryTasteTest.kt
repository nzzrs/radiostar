/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTasteTest {
    private val now = 1_800_000_000_000L

    private fun event(
        type: TelemetryEventType,
        songId: String,
        artist: String = "Artist",
        positionMs: Long? = null,
        durationMs: Long? = null,
        at: Long = now,
    ) = TelemetryStore.Event(
        type = type,
        songId = songId,
        title = "Title $songId",
        artist = artist,
        playlist = null,
        seed = null,
        positionMs = positionMs,
        durationMs = durationMs,
        at = at,
    )

    @Test
    fun `completed tracks get a positive song score`() {
        val bias =
            TelemetryTaste.fromEvents(
                listOf(
                    event(TelemetryEventType.COMPLETE, "a"),
                    event(TelemetryEventType.COMPLETE, "a"),
                    event(TelemetryEventType.COMPLETE, "a"),
                ),
                now,
            )

        assertTrue(bias.score("a", listOf("Artist")) > 0.3)
    }

    @Test
    fun `early skips are stronger than late skips`() {
        val early =
            TelemetryTaste.fromEvents(
                listOf(event(TelemetryEventType.SKIP, "a", positionMs = 5_000, durationMs = 200_000)),
                now,
            )
        val late =
            TelemetryTaste.fromEvents(
                listOf(event(TelemetryEventType.SKIP, "a", positionMs = 190_000, durationMs = 200_000)),
                now,
            )

        assertTrue(early.score("a", emptyList()) < late.score("a", emptyList()))
        assertTrue(late.score("a", emptyList()) < 0.0)
    }

    @Test
    fun `artist signal uses the telemetry artist names`() {
        val bias =
            TelemetryTaste.fromEvents(
                listOf(
                    event(TelemetryEventType.COMPLETE, "a", artist = "Artist A"),
                    event(TelemetryEventType.COMPLETE, "b", artist = "Artist A"),
                    event(TelemetryEventType.COMPLETE, "c", artist = "Artist A"),
                ),
                now,
            )

        assertTrue(bias.score(null, listOf("artist a")) > 0.2)
    }

    @Test
    fun `old events decay`() {
        val fresh =
            TelemetryTaste.fromEvents(
                listOf(event(TelemetryEventType.COMPLETE, "a")),
                now,
            )
        val old =
            TelemetryTaste.fromEvents(
                listOf(
                    event(
                        TelemetryEventType.COMPLETE,
                        "a",
                        at = now - 180L * 86_400_000L,
                    ),
                ),
                now,
            )

        assertTrue(fresh.score("a", emptyList()) > old.score("a", emptyList()))
    }

    @Test
    fun `unknown tracks have no bias`() {
        val bias = TelemetryTaste.fromEvents(listOf(event(TelemetryEventType.COMPLETE, "a")), now)
        assertTrue(bias.score("b", emptyList()) == 0.0)
    }

    @Test
    fun `empty log gives an empty bias`() {
        val bias = TelemetryTaste.fromEvents(emptyList(), now)
        assertTrue(bias.songScores.isEmpty())
        assertTrue(bias.artistScores.isEmpty())
    }
}
