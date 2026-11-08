/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBeyondGeographyTest {
    @Test
    fun `catalog contains every ISO country with a unique code`() {
        assertTrue(GoBeyondGeography.all.size > 190)
        assertEquals(
            GoBeyondGeography.all.size,
            GoBeyondGeography.all.map { it.code }.distinct().size,
        )
        assertTrue(GoBeyondGeography.all.none { it.name.isBlank() })
    }

    @Test
    fun `flag is built from two regional indicator symbols`() {
        val flag = GoBeyondGeography.flag("US")

        assertEquals(2, flag.codePointCount(0, flag.length))
        assertEquals(
            listOf(0x1F1FA, 0x1F1F8),
            flag.codePoints().toArray().toList(),
        )
        assertEquals("", GoBeyondGeography.flag("USA"))
    }

    @Test
    fun `flag is exposed on each country`() {
        val brazil = GoBeyondGeography.country("BR")

        assertNotNull(brazil)
        assertEquals("\uD83C\uDDE7\uD83C\uDDF7", brazil?.flag)
    }

    @Test
    fun `country lookup ignores case and whitespace`() {
        assertEquals("US", GoBeyondGeography.country(" us ")?.code)
        assertNull(GoBeyondGeography.country("ZZ"))
    }

    @Test
    fun `by name resolves common aliases and misspellings`() {
        assertEquals("BR", GoBeyondGeography.byName("brasil")?.code)
        assertEquals("GB", GoBeyondGeography.byName("UK")?.code)
        assertEquals("US", GoBeyondGeography.byName("America")?.code)
        assertEquals("NL", GoBeyondGeography.byName("Holland")?.code)
        assertEquals("CI", GoBeyondGeography.byName("Ivory Coast")?.code)
        assertEquals("TR", GoBeyondGeography.byName("Türkiye")?.code)
    }

    @Test
    fun `search united includes the united states and the united kingdom`() {
        val codes = GoBeyondGeography.search("united").map { it.code }.toSet()

        assertTrue("US" in codes)
        assertTrue("GB" in codes)
    }

    @Test
    fun `search normalizes accents and apostrophes`() {
        assertTrue(GoBeyondGeography.search("cote divoire").any { it.code == "CI" })
        assertTrue(GoBeyondGeography.search("côte d'ivoire").any { it.code == "CI" })
    }

    @Test
    fun `search tolerates small typos`() {
        assertEquals("PR", GoBeyondGeography.search("purto rico").firstOrNull()?.code)
    }

    @Test
    fun `search returns nothing for nonsense`() {
        assertTrue(GoBeyondGeography.search("zzzz").isEmpty())
    }

    @Test
    fun `empty search returns the head of the catalog`() {
        assertEquals(
            GoBeyondGeography.all.take(5),
            GoBeyondGeography.search("", limit = 5),
        )
        assertTrue(GoBeyondGeography.search("  ", limit = 0).isEmpty())
    }

    @Test
    fun `known countries use a curated adjective`() {
        assertEquals("Brazilian", GoBeyondGeography.country("BR")?.adjective)
        assertEquals("Brazilian", GoBeyondGeography.adjective("br"))
        assertEquals("South Korean", GoBeyondGeography.country("KR")?.adjective)
        assertEquals("New Zealander", GoBeyondGeography.country("NZ")?.adjective)
    }

    @Test
    fun `unknown adjective falls back to the country name`() {
        assertEquals("Chad", GoBeyondGeography.country("TD")?.adjective)
        assertNull(GoBeyondGeography.adjective("ZZ"))
    }

    @Test
    fun `hue is stable and inside the colour wheel`() {
        val first = GoBeyondGeography.country("BR")
        val second = GoBeyondGeography.all.first { it.code == "BR" }

        assertNotNull(first)
        assertEquals(first?.hue, second.hue)
        assertTrue(first!!.hue in 0..359)
        assertTrue(GoBeyondGeography.all.all { it.hue in 0..359 })
    }
}
