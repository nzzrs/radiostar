/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBeyondFuzzyTest {
    @Test
    fun `normalize strips accents and punctuation`() {
        assertEquals("cote d ivoire", GoBeyondFuzzy.normalize("Côte d'Ivoire"))
        assertEquals("beyonce", GoBeyondFuzzy.normalize("Beyoncé"))
        assertEquals("hip hop", GoBeyondFuzzy.normalize("Hip-Hop"))
        assertEquals("r and b", GoBeyondFuzzy.normalize("R&B"))
        assertEquals("hello world", GoBeyondFuzzy.normalize("  Hello,   WORLD!!  "))
        assertEquals("", GoBeyondFuzzy.normalize("   "))
    }

    @Test
    fun `tokens are normalized and unique`() {
        assertEquals(setOf("hip", "hop"), GoBeyondFuzzy.tokens("Hip-Hop, hip!"))
        assertEquals(emptySet<String>(), GoBeyondFuzzy.tokens("  "))
    }

    @Test
    fun `distance counts adjacent transposition as one edit`() {
        assertEquals(1, GoBeyondFuzzy.distance("purpel", "purple"))
        assertEquals(1, GoBeyondFuzzy.distance("abc", "acb"))
        assertEquals(1, GoBeyondFuzzy.distance("shoegaz", "shoegaze"))
        assertEquals(0, GoBeyondFuzzy.distance("same", "same"))
    }

    @Test
    fun `distance is symmetric`() {
        val pairs =
            listOf(
                "purpel" to "purple",
                "shoegaz" to "shoegaze",
                "melancolic" to "melancholic",
                "hihop" to "hip hop",
                "" to "city pop",
            )

        for ((a, b) in pairs) {
            assertEquals("distance($a, $b)", GoBeyondFuzzy.distance(a, b), GoBeyondFuzzy.distance(b, a))
        }
    }

    @Test
    fun `ratio is bounded and rewards similar text`() {
        assertEquals(1.0, GoBeyondFuzzy.ratio("Purple", "purple"), 0.0001)
        assertTrue(GoBeyondFuzzy.ratio("purpel", "purple") >= 0.8)
        assertTrue(GoBeyondFuzzy.ratio("pop city", "city pop") >= 0.9)
        assertTrue(GoBeyondFuzzy.ratio("purpel", "purple") <= 1.0)
        assertTrue(GoBeyondFuzzy.ratio("purple", "blue") >= 0.0)
    }

    @Test
    fun `bestMatch fixes transposed letters`() {
        val match = GoBeyondFuzzy.bestMatch("purpel", listOf("Purple", "Blue"))

        assertNotNull(match)
        assertEquals("Purple", match?.value)
        assertTrue(match!!.score >= 0.62)
    }

    @Test
    fun `bestMatch fixes missing letters`() {
        val match = GoBeyondFuzzy.bestMatch("shoegaz", listOf("Shoegaze", "House"))

        assertNotNull(match)
        assertEquals("Shoegaze", match?.value)
    }

    @Test
    fun `bestMatch fixes extra letters`() {
        val match = GoBeyondFuzzy.bestMatch("melancolic", listOf("Melancholic", "Upbeat"))

        assertNotNull(match)
        assertEquals("Melancholic", match?.value)
    }

    @Test
    fun `bestMatch joins split words`() {
        val match = GoBeyondFuzzy.bestMatch("hihop", listOf("Hip-Hop", "House"))

        assertNotNull(match)
        assertEquals("Hip-Hop", match?.value)
    }

    @Test
    fun `bestMatch handles reordered and partially misspelled tokens`() {
        val reordered = GoBeyondFuzzy.bestMatch("pop city", listOf("City Pop", "Ambient"))
        assertNotNull(reordered)
        assertEquals("City Pop", reordered?.value)

        val exact = GoBeyondFuzzy.bestMatch("city pop", listOf("City Pop", "Ambient"))
        assertNotNull(exact)
        assertEquals("City Pop", exact?.value)

        val misspelled = GoBeyondFuzzy.bestMatch("cidade pop", listOf("City Pop", "Ambient"))
        assertNotNull(misspelled)
        assertEquals("City Pop", misspelled?.value)
    }

    @Test
    fun `bestMatch rejects nonsense and blank queries`() {
        val candidates = listOf("Purple", "Blue", "Shoegaze", "Melancholic", "Hip-Hop")

        assertNull(GoBeyondFuzzy.bestMatch("asdfgh", candidates))
        assertNull(GoBeyondFuzzy.bestMatch("", candidates))
        assertNull(GoBeyondFuzzy.bestMatch("   ", candidates))
    }

    @Test
    fun `rank is descending and deterministic`() {
        val candidates = listOf("Blue", "Purple", "Purpose", "Blue")
        val first = GoBeyondFuzzy.rank("purpel", candidates)
        val second = GoBeyondFuzzy.rank("purpel", candidates)

        assertEquals(first, second)
        assertEquals(candidates.size, first.size)
        assertTrue(first.zipWithNext().all { (left, right) -> left.score >= right.score })
        assertEquals("Purple", first.first().value)
    }

    @Test
    fun `containsTerm matches tokens and prefixes`() {
        assertTrue(GoBeyondFuzzy.containsTerm("Shoegaze", "shoe"))
        assertTrue(GoBeyondFuzzy.containsTerm("City Pop", "pop"))
        assertTrue(GoBeyondFuzzy.containsTerm("City Pop", "city pop"))
        assertTrue(GoBeyondFuzzy.containsTerm("Melancholic", "melan"))
        assertFalse(GoBeyondFuzzy.containsTerm("City Pop", "hip hop"))
        assertFalse(GoBeyondFuzzy.containsTerm("", "pop"))
    }

    @Test
    fun `sanitizeUserText removes control characters and dangerous punctuation`() {
        assertEquals("hi there", GoBeyondFuzzy.sanitizeUserText("  hi\u0000\u0007 there  "))
        val cleaned = GoBeyondFuzzy.sanitizeUserText("<script>alert(1)</script>")
        assertFalse(cleaned.contains('<'))
        assertFalse(cleaned.contains('>'))
        assertFalse(cleaned.contains('('))
        assertFalse(cleaned.contains(')'))
        assertFalse(cleaned.contains('/'))
        assertTrue(cleaned.contains("script"))
    }

    @Test
    fun `sanitizeUserText collapses whitespace and caps length`() {
        assertEquals("city pop", GoBeyondFuzzy.sanitizeUserText("  city   pop  "))
        assertEquals("hello", GoBeyondFuzzy.sanitizeUserText("hello world", 5))
        assertEquals("", GoBeyondFuzzy.sanitizeUserText("hello", 0))
        assertTrue(GoBeyondFuzzy.sanitizeUserText("a".repeat(100)).length <= 64)
    }

    @Test
    fun `sanitizeUserText rejects blank and nonsense input`() {
        assertEquals("", GoBeyondFuzzy.sanitizeUserText(""))
        assertEquals("", GoBeyondFuzzy.sanitizeUserText("   "))
        assertEquals("", GoBeyondFuzzy.sanitizeUserText("asdfgh"))
        assertEquals("", GoBeyondFuzzy.sanitizeUserText("qwerty"))
        assertEquals("", GoBeyondFuzzy.sanitizeUserText("!!!"))
        assertEquals("hip hop", GoBeyondFuzzy.sanitizeUserText("hip hop"))
    }
}
