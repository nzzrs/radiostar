/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryInsightsTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    private fun event(
        type: TelemetryEventType,
        songId: String,
        artist: String = "Artist",
        at: String = "2026-11-08T10:00:00Z",
        positionMs: Long? = null,
        durationMs: Long? = null,
        session: String? = "s1",
        facets: List<String> = emptyList(),
    ) = TelemetryStore.Event(
        type = type,
        songId = songId,
        title = "Title $songId",
        artist = artist,
        album = "Album",
        playlist = null,
        seed = null,
        positionMs = positionMs,
        durationMs = durationMs,
        completed = type == TelemetryEventType.COMPLETE,
        at = Instant.parse(at).toEpochMilli(),
        session = session,
        facets = facets,
    )

    @Test
    fun `rates and top artists are derived from terminal events`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.PLAY, "a", artist = "Alpha"),
                    event(TelemetryEventType.COMPLETE, "a", artist = "Alpha"),
                    event(TelemetryEventType.PLAY, "b", artist = "Beta"),
                    event(TelemetryEventType.SKIP, "b", artist = "Beta", positionMs = 10_000, durationMs = 200_000),
                    event(TelemetryEventType.COMPLETE, "c", artist = "Alpha"),
                ),
                now,
                utc,
            )

        assertEquals(5, insights.events)
        assertEquals(2, insights.completes)
        assertEquals(1, insights.skips)
        assertEquals(1, insights.earlySkips)
        assertEquals(2, insights.plays)
        assertEquals(3, insights.distinctSongs)
        assertEquals(2, insights.distinctArtists)
        assertEquals("alpha", insights.topArtists.first().name)
        assertEquals(2, insights.topArtists.first().listens)
        assertEquals(1, insights.sessions)
        assertEquals(2, insights.activeHours[10])
        assertTrue(insights.completionRate in 0.5..0.8)
    }

    @Test
    fun `session ids and large gaps both split sessions`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.PLAY, "a", at = "2026-11-08T09:00:00Z", session = "one"),
                    event(TelemetryEventType.PLAY, "b", at = "2026-11-08T09:05:00Z", session = "one"),
                    event(TelemetryEventType.PLAY, "c", at = "2026-11-08T12:00:00Z", session = null),
                    event(TelemetryEventType.PLAY, "d", at = "2026-11-08T12:10:00Z", session = null),
                ),
                now,
                utc,
            )

        assertEquals(2, insights.sessions)
        assertEquals(2, insights.activeHours[9])
        assertEquals(2, insights.activeHours[12])
    }

    @Test
    fun `explicit reactions outrank passive plays for taste`() {
        val bias =
            TelemetryTaste.fromEvents(
                listOf(
                    event(TelemetryEventType.PLAY, "a"),
                    event(TelemetryEventType.LIKE, "b"),
                    event(TelemetryEventType.DISLIKE, "c"),
                ),
                now,
            )

        assertTrue(bias.score("b", emptyList()) > 0.2)
        assertTrue(bias.score("c", emptyList()) < -0.2)
        assertEquals(0.0, bias.score("a", emptyList()), 0.0001)
    }

    @Test
    fun `gateway facets accumulate affinity`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.COMPLETE, "a", facets = listOf("Brazil", "Shoegaze")),
                    event(TelemetryEventType.COMPLETE, "b", facets = listOf("Brazil")),
                    event(
                        TelemetryEventType.SKIP,
                        "c",
                        positionMs = 5_000,
                        durationMs = 200_000,
                        facets = listOf("Japan"),
                    ),
                ),
                now,
                utc,
            )

        assertTrue((insights.gatewayAffinity["Brazil"] ?: 0.0) > 0.3)
        assertTrue((insights.gatewayAffinity["Japan"] ?: 0.0) < 0.0)
    }

    @Test
    fun `empty log has neutral insights`() {
        val insights = TelemetryInsights.fromEvents(emptyList(), now, utc)

        assertEquals(0, insights.events)
        assertEquals(0.0, insights.completionRate, 0.0001)
        assertEquals(0.0, insights.noveltyAppetite, 0.0001)
        assertTrue(insights.topArtists.isEmpty())
    }
}
