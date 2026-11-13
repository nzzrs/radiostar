/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: a mood card is a request for a sound, not for songs whose title
 * happens to contain the mood word. "Euphoric" used to search "euphoric music"
 * and filled the playlist with tracks literally called "Euphoria".
 */
class GoBeyondMoodQueryTest {
    private val forbidden = listOf("Euphoric", "euphoric", "big release", "bright energy", "uplifting")

    @Test
    fun `euphoric journeys never search the literal mood word`() {
        val queries = GoBeyondSelection(mood = "Euphoric").searchQueries()

        println("euphoric queries=$queries")
        assertTrue(queries.isNotEmpty())
        forbidden.forEach { term ->
            assertFalse(
                "query list must not contain '$term': $queries",
                queries.any { query -> query.contains(term, ignoreCase = true) },
            )
        }
    }
}
