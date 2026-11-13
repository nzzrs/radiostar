/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBeyondSonicTest {
    private fun candidate(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist",
        vector: DoubleArray? = null,
    ) = GoBeyondCandidate(
        id = id,
        title = title,
        artists = listOf(artist),
        durationSeconds = 200,
        vector = vector,
        targetScore = 0.6,
    )

    private fun profile(
        bpm: Double? = null,
        rms: Float? = null,
        minor: Boolean? = null,
    ) = AudioProfile(
        bpm = bpm,
        key = minor?.let { MusicalKey(0, it) },
        rms = rms,
    )

    private fun euphoric() = GoBeyondSonic.targetForMood("Euphoric")!!

    @Test
    fun `every mood has a curated sonic target`() {
        GoBeyondCatalog.moods.forEach { mood ->
            val target = GoBeyondSonic.targetForMood(mood.title)
            assertTrue("missing target for ${mood.title}", target != null)
            assertTrue("empty tags for ${mood.title}", target!!.tags.isNotEmpty())
            assertTrue("empty bpm range for ${mood.title}", target.bpmRange.start < target.bpmRange.endInclusive)
            assertTrue("empty energy range for ${mood.title}", target.energyRange.start < target.energyRange.endInclusive)
        }
    }

    @Test
    fun `mood targets follow the documented curves`() {
        val euphoric = euphoric()
        assertEquals(120.0, euphoric.bpmRange.start, 0.001)
        assertEquals(140.0, euphoric.bpmRange.endInclusive, 0.001)
        assertEquals(SonicMode.MAJOR, euphoric.mode)
        assertTrue(euphoric.energyRange.endInclusive >= 0.40f)
        assertTrue(euphoric.energyRange.start >= 0.18f)

        val calm = GoBeyondSonic.targetForMood("Calm")!!
        assertEquals(60.0, calm.bpmRange.start, 0.001)
        assertEquals(90.0, calm.bpmRange.endInclusive, 0.001)
        assertTrue(calm.energyRange.endInclusive <= 0.14f)

        val melancholic = GoBeyondSonic.targetForMood("Melancholic")!!
        assertEquals(SonicMode.MINOR, melancholic.mode)

        val angry = GoBeyondSonic.targetForMood("Angry")!!
        assertEquals(130.0, angry.bpmRange.start, 0.001)
        assertEquals(180.0, angry.bpmRange.endInclusive, 0.001)

        assertEquals(euphoric, GoBeyondSonic.targetForMood("happy"))
    }

    @Test
    fun `in range profiles score higher than out of range profiles`() {
        val target = euphoric()
        val inside = GoBeyondSonic.score(profile(bpm = 128.0, rms = 0.30f, minor = false), target)!!
        val outside = GoBeyondSonic.score(profile(bpm = 70.0, rms = 0.05f, minor = true), target)!!

        assertTrue(inside > 0.9)
        assertTrue(outside < 0.5)
        assertTrue(inside > outside)
    }

    @Test
    fun `missing profile is neutral not penalised`() {
        assertNull(GoBeyondSonic.score(null, euphoric()))
        assertNull(GoBeyondSonic.score(profile(bpm = 128.0), null))

        val ranked =
            GoBeyondRanker.rank(
                listOf(candidate("unknown"), candidate("known")),
                GoBeyondRankContext(
                    profiles = mapOf("known" to profile(bpm = 128.0, rms = 0.30f, minor = false)),
                    sonicTarget = euphoric(),
                    limit = 2,
                ),
            )

        assertEquals("known", ranked.first().candidate.id)
        assertTrue(ranked.first { it.candidate.id == "unknown" }.score > 0.0)
    }

    @Test
    fun `cold start without profiles keeps semantic order`() {
        val candidates = listOf(candidate("a", artist = "A"), candidate("b", artist = "B"), candidate("c", artist = "C"))
        val plain = GoBeyondRanker.rank(candidates, GoBeyondRankContext(limit = 3))
        val cold =
            GoBeyondRanker.rank(
                candidates,
                GoBeyondRankContext(profiles = emptyMap(), sonicTarget = euphoric(), limit = 3),
            )

        assertEquals(plain.map { it.candidate.id }, cold.map { it.candidate.id })
        assertEquals(plain.map { it.score }, cold.map { it.score })
        assertEquals(candidates.map { it.id }, GoBeyondSonic.order(plain, emptyMap(), euphoric()).map { it.candidate.id })
    }

    @Test
    fun `literal mood titles sink below sounding candidates`() {
        val context =
            GoBeyondRankContext(
                promptVector = doubleArrayOf(1.0),
                literalMoodTerms = GoBeyondSonic.literalMoodTerms("Euphoric"),
                profiles = mapOf("literal" to profile(bpm = 128.0, rms = 0.30f, minor = false)),
                sonicTarget = euphoric(),
                limit = 2,
            )
        val ranked =
            GoBeyondRanker.rank(
                listOf(
                    candidate("literal", title = "Euphoria", artist = "Euphoric Sound", vector = doubleArrayOf(1.0)),
                    candidate("plain", title = "Ordinary Song"),
                ),
                context,
            )

        assertNotEquals("literal", ranked.first().candidate.id)
        assertTrue(ranked.first { it.candidate.id == "literal" }.score < 0.5)
    }

    @Test
    fun `every mood query avoids its title aliases and descriptors`() {
        GoBeyondCatalog.moods.forEach { mood ->
            val queries = GoBeyondSelection(mood = mood.title).searchQueries()
            assertTrue("no queries for ${mood.title}", queries.isNotEmpty())
            val terms = GoBeyondSonic.literalMoodTerms(mood.title)
            queries.forEach { query ->
                terms.forEach { term ->
                    assertFalse(
                        "${mood.title} query '$query' leaked '$term'",
                        query.contains(term, ignoreCase = true),
                    )
                }
            }
        }
    }

    @Test
    fun `mood queries use curated tags and seed radio`() {
        val queries = GoBeyondSelection(mood = "Euphoric").searchQueries(seedArtists = listOf("Daft Punk"))

        assertTrue(queries.any { it.contains("house", ignoreCase = true) })
        assertTrue(queries.any { it.contains("Daft Punk radio", ignoreCase = true) })
        assertFalse(queries.any { it.contains("euphoric", ignoreCase = true) })
        assertFalse(queries.any { it.contains("euphoric music", ignoreCase = true) })
    }

    @Test
    fun `final selection follows an energy arc inside the target`() {
        val target = euphoric()
        val entries =
            listOf(
                GoBeyondScore(candidate("loud"), 1.0),
                GoBeyondScore(candidate("quiet"), 0.9),
                GoBeyondScore(candidate("mid"), 0.8),
            )
        val profiles =
            mapOf(
                "loud" to profile(rms = 0.40f),
                "quiet" to profile(rms = 0.20f),
                "mid" to profile(rms = 0.30f),
            )

        val ordered = GoBeyondSonic.order(entries, profiles, target)

        assertEquals(listOf("quiet", "mid", "loud"), ordered.map { it.candidate.id })
    }

    @Test
    fun `unknown tracks keep their semantic slot during ordering`() {
        val target = euphoric()
        val entries =
            listOf(
                GoBeyondScore(candidate("loud"), 1.0),
                GoBeyondScore(candidate("unknown"), 0.95),
                GoBeyondScore(candidate("quiet"), 0.9),
            )
        val profiles =
            mapOf(
                "loud" to profile(rms = 0.40f),
                "quiet" to profile(rms = 0.20f),
            )

        val ordered = GoBeyondSonic.order(entries, profiles, target)

        assertEquals(listOf("quiet", "unknown", "loud"), ordered.map { it.candidate.id })
    }

    @Test
    fun `out of target profiles do not move the slate`() {
        val target = euphoric()
        val entries = listOf(GoBeyondScore(candidate("a"), 1.0), GoBeyondScore(candidate("b"), 0.9))
        val profiles = mapOf("a" to profile(rms = 0.01f), "b" to profile(rms = 0.02f))

        assertEquals(entries, GoBeyondSonic.order(entries, profiles, target))
    }

    @Test
    fun `query to rank integration keeps euphoria out of the top`() {
        val selection = GoBeyondResolver.resolve(GoBeyondSelection(mood = "Euphoric"))
        val queries = selection.searchQueries(seedArtists = listOf("Daft Punk"))
        val target = GoBeyondSonic.targetFor(selection)!!

        assertTrue(queries.any { it.contains("dance pop", ignoreCase = true) })
        assertFalse(queries.any { GoBeyondSonic.containsLiteralMood(it, selection.mood) })

        val ranked =
            GoBeyondRanker.rank(
                candidates =
                    listOf(
                        candidate("euphoria", title = "Euphoria", vector = doubleArrayOf(1.0)),
                        candidate("anthem", title = "Sunlight Anthem", vector = doubleArrayOf(0.8, 0.6)),
                    ),
                context =
                    GoBeyondRankContext(
                        promptVector = doubleArrayOf(1.0, 0.0),
                        literalMoodTerms = GoBeyondSonic.literalMoodTerms(selection.mood),
                        profiles = mapOf("euphoria" to profile(bpm = 128.0, rms = 0.30f, minor = false)),
                        sonicTarget = target,
                        limit = 2,
                    ),
            )

        assertEquals("anthem", ranked.first().candidate.id)
    }
}
