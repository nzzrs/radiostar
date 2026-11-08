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

class GoBeyondPersonalizeTest {
    private val now = Instant.parse("2026-11-09T01:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    private fun event(
        type: TelemetryEventType,
        songId: String,
        artist: String = "Artist",
        title: String = "Title $songId",
        at: String = "2026-11-08T20:00:00Z",
        positionMs: Long? = null,
        durationMs: Long? = null,
        facets: List<String> = emptyList(),
    ) = TelemetryStore.Event(
        type = type,
        songId = songId,
        title = title,
        artist = artist,
        playlist = null,
        seed = null,
        positionMs = positionMs,
        durationMs = durationMs,
        completed = type == TelemetryEventType.COMPLETE,
        at = Instant.parse(at).toEpochMilli(),
        facets = facets,
    )

    private fun card(
        id: String,
        section: GoBeyondSection,
        title: String,
    ): GoBeyondCard {
        val selection =
            when (section) {
                GoBeyondSection.COUNTRY -> GoBeyondSelection(country = title)
                GoBeyondSection.GENRE -> GoBeyondSelection(genre = title)
                GoBeyondSection.ARTIST -> GoBeyondSelection(artist = title)
                GoBeyondSection.PERIOD -> GoBeyondSelection(fromYear = 1980, toYear = 1989)
                GoBeyondSection.COLOR -> GoBeyondSelection(color = title)
                GoBeyondSection.MOOD -> GoBeyondSelection(mood = title)
                GoBeyondSection.CUSTOM -> GoBeyondSelection(freeText = title)
            }
        return GoBeyondCard(
            id = id,
            section = section,
            title = title,
            subtitle = "$title subtitle",
            description = "Explore $title and more.",
            selection = selection,
            accentHue = 0,
            semanticPrompt = "$title music prompt",
        )
    }

    @Test
    fun `top artist card comes first`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.PLAY, "korn-1", artist = "Korn", title = "Freak"),
                    event(TelemetryEventType.COMPLETE, "korn-1", artist = "Korn", title = "Freak"),
                ),
                now,
                utc,
            )
        val taste = GoBeyondTasteProfile(insights = insights, topArtists = listOf("Korn"))
        val cards =
            listOf(
                card("country-brazil", GoBeyondSection.COUNTRY, "Brazil"),
                card("artist-korn", GoBeyondSection.ARTIST, "Korn"),
                card("genre-shoegaze", GoBeyondSection.GENRE, "Shoegaze"),
            )

        val ordered = GoBeyondPersonalize.orderCards(cards, taste)

        assertEquals("artist-korn", ordered.first().id)
    }

    @Test
    fun `positive gateway affinity card comes first`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.COMPLETE, "b1", facets = listOf("Brazil")),
                    event(TelemetryEventType.COMPLETE, "b2", facets = listOf("Brazil")),
                ),
                now,
                utc,
            )
        assertTrue((insights.gatewayAffinity["Brazil"] ?: 0.0) > 0.0)
        val cards =
            listOf(
                card("country-japan", GoBeyondSection.COUNTRY, "Japan"),
                card("country-brazil", GoBeyondSection.COUNTRY, "Brazil"),
            )

        val suggestions =
            GoBeyondPersonalize.suggestions(cards, GoBeyondTasteProfile(insights = insights))

        assertEquals("country-brazil", suggestions.first().card.id)
        assertTrue(suggestions.first().reason.contains("Brazil"))
    }

    @Test
    fun `high skip rate lowers angry mood`() {
        val insights =
            TelemetryInsights.fromEvents(
                List(6) { index ->
                    event(
                        TelemetryEventType.SKIP,
                        "skip-$index",
                        at = "2026-11-08T20:0$index:00Z",
                        positionMs = 1_000,
                        durationMs = 200_000,
                    )
                },
                now,
                utc,
            )
        assertEquals(1.0, insights.skipRate, 0.0001)
        val cards =
            listOf(
                card("mood-angry", GoBeyondSection.MOOD, "Angry"),
                card("mood-calm", GoBeyondSection.MOOD, "Calm"),
            )

        val suggestions =
            GoBeyondPersonalize.suggestions(cards, GoBeyondTasteProfile(insights = insights))
        val angry = suggestions.first { it.card.id == "mood-angry" }
        val calm = suggestions.first { it.card.id == "mood-calm" }

        assertTrue(angry.score < calm.score)
        assertTrue(angry.score < 0.0)
    }

    @Test
    fun `late night favors calm melancholic and dreamy`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.PLAY, "p1", at = "2026-11-08T23:00:00Z", artist = "Late", title = "Nocturne"),
                    event(TelemetryEventType.COMPLETE, "p1", at = "2026-11-08T23:05:00Z", artist = "Late", title = "Nocturne"),
                    event(TelemetryEventType.PLAY, "p2", at = "2026-11-08T23:10:00Z", artist = "Late", title = "Nocturne 2"),
                    event(TelemetryEventType.COMPLETE, "p2", at = "2026-11-08T23:15:00Z", artist = "Late", title = "Nocturne 2"),
                ),
                now,
                utc,
            )
        assertEquals(23, insights.bestHour)
        val titles = listOf("Calm", "Melancholic", "Dreamy", "Playful", "Focus", "Euphoric")
        val cards = titles.map { card("mood-${it.lowercase()}", GoBeyondSection.MOOD, it) }

        val ordered =
            GoBeyondPersonalize.orderCards(cards, GoBeyondTasteProfile(insights = insights))

        assertEquals(
            setOf("Calm", "Melancholic", "Dreamy"),
            ordered.take(3).map { it.title }.toSet(),
        )
        assertTrue(
            GoBeyondPersonalize
                .suggestions(cards, GoBeyondTasteProfile(insights = insights))
                .first()
                .reason
                .contains("23:00"),
        )
    }

    @Test
    fun `empty taste keeps original order`() {
        val cards =
            listOf(
                card("country-brazil", GoBeyondSection.COUNTRY, "Brazil"),
                card("artist-korn", GoBeyondSection.ARTIST, "Korn"),
                card("mood-calm", GoBeyondSection.MOOD, "Calm"),
                card("period-1980s", GoBeyondSection.PERIOD, "1980s"),
            )

        val ordered = GoBeyondPersonalize.orderCards(cards, GoBeyondTasteProfile())

        assertEquals(cards.map { it.id }, ordered.map { it.id })
        assertTrue(
            GoBeyondPersonalize
                .suggestions(cards, GoBeyondTasteProfile())
                .all { it.score == 0.0 },
        )
    }

    @Test
    fun `typo in artist name still matches`() {
        val taste = GoBeyondTasteProfile(topArtists = listOf("Korn"))
        val cards =
            listOf(
                card("country-brazil", GoBeyondSection.COUNTRY, "Brazil"),
                card("artist-kornn", GoBeyondSection.ARTIST, "Kornn"),
            )

        val suggestions = GoBeyondPersonalize.suggestions(cards, taste)

        assertEquals("artist-kornn", suggestions.first().card.id)
        assertTrue(suggestions.first().reason.contains("most played"))
    }

    @Test
    fun `ordering is deterministic and ties keep original order`() {
        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.PLAY, "a", artist = "Alpha", title = "First"),
                    event(TelemetryEventType.COMPLETE, "a", artist = "Alpha", title = "First"),
                ),
                now,
                utc,
            )
        val taste = GoBeyondTasteProfile(insights = insights, topArtists = listOf("Alpha"))
        val cards =
            listOf(
                card("genre-shoegaze", GoBeyondSection.GENRE, "Shoegaze"),
                card("artist-alpha", GoBeyondSection.ARTIST, "Alpha"),
                card("country-japan", GoBeyondSection.COUNTRY, "Japan"),
                card("genre-ambient", GoBeyondSection.GENRE, "Ambient"),
            )
        val expected = GoBeyondPersonalize.orderCards(cards, taste).map { it.id }

        repeat(3) {
            assertEquals(expected, GoBeyondPersonalize.orderCards(cards, taste).map { it.id })
        }

        val tied = listOf("Brazil", "Japan").map { card("country-${it.lowercase()}", GoBeyondSection.COUNTRY, it) }
        assertEquals(
            tied.map { it.id },
            GoBeyondPersonalize.orderCards(tied, GoBeyondTasteProfile()).map { it.id },
        )
    }

    @Test
    fun `sections use taste data and stay stable`() {
        val sections =
            listOf(
                GoBeyondSection.COUNTRY,
                GoBeyondSection.ARTIST,
                GoBeyondSection.MOOD,
                GoBeyondSection.GENRE,
            )

        assertEquals(sections, GoBeyondPersonalize.orderSections(sections, GoBeyondTasteProfile()))

        val insights =
            TelemetryInsights.fromEvents(
                listOf(
                    event(TelemetryEventType.PLAY, "a", artist = "Korn", title = "Freak"),
                    event(TelemetryEventType.COMPLETE, "a", artist = "Korn", title = "Freak"),
                ),
                now,
                utc,
            )
        val taste = GoBeyondTasteProfile(insights = insights, topArtists = listOf("Korn"))

        assertEquals(GoBeyondSection.ARTIST, GoBeyondPersonalize.orderSections(sections, taste).first())
    }
}
