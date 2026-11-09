/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoBeyondResolverTest {
    @Test
    fun `typos resolve to canonical facets`() {
        assertEquals("Shoegaze", GoBeyondResolver.resolveGenre("shoegaz"))
        assertEquals("Purple", GoBeyondResolver.resolveColor("purpel"))
        assertEquals("Melancholic", GoBeyondResolver.resolveMood("melancolic"))
        assertEquals("Brazil", GoBeyondResolver.resolveCountry("Brasil"))
        assertEquals("Hip-Hop", GoBeyondResolver.resolveGenre("hihop"))
    }

    @Test
    fun `free text is sanitized`() {
        val selection =
            GoBeyondResolver.resolve(
                GoBeyondSelection(freeText = "  dreamy\u0000   night drive  "),
            )

        assertEquals("dreamy night drive", selection.freeText)
    }

    @Test
    fun `nonsense colour is dropped`() {
        assertNull(GoBeyondResolver.resolveColor("asdfgh"))
    }

    @Test
    fun `colour literal terms include aliases`() {
        val terms = GoBeyondResolver.literalColorTerms("purpel")

        assertEquals(true, terms.any { it == "purple" })
        assertEquals(true, terms.any { it == "violet" })
    }
}
