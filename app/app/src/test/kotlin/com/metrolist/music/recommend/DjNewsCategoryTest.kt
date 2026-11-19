/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjNewsCategoryTest {
    @Test
    fun `default categories keep world and nation news on`() {
        assertEquals(setOf(DjNewsCategory.WORLD, DjNewsCategory.NATION), DjNewsCategory.Default)
        assertTrue(DjNewsCategory.Default.isNotEmpty())
    }

    @Test
    fun `categories expose google news topic ids`() {
        assertEquals("WORLD", DjNewsCategory.WORLD.topicId)
        assertEquals("NATION", DjNewsCategory.NATION.topicId)
        assertEquals("BUSINESS", DjNewsCategory.BUSINESS.topicId)
        assertEquals("TECHNOLOGY", DjNewsCategory.TECHNOLOGY.topicId)
        assertEquals("SCIENCE", DjNewsCategory.SCIENCE.topicId)
        assertEquals("HEALTH", DjNewsCategory.HEALTH.topicId)
        assertEquals("SPORTS", DjNewsCategory.SPORTS.topicId)
        assertEquals("ENTERTAINMENT", DjNewsCategory.ENTERTAINMENT.topicId)
        assertTrue(DjNewsCategory.entries.all { it.topicId == it.name })
    }

    @Test
    fun `storage round trips known categories`() {
        val categories = setOf(DjNewsCategory.SPORTS, DjNewsCategory.SCIENCE)

        val stored = DjNewsCategory.toStorage(categories)

        assertEquals(setOf("SPORTS", "SCIENCE"), stored)
        assertEquals(categories, DjNewsCategory.fromStorage(stored))
    }

    @Test
    fun `storage ignores unknown values and case`() {
        assertEquals(
            setOf(DjNewsCategory.SPORTS),
            DjNewsCategory.fromStorage(setOf("sports", "unknown")),
        )
    }

    @Test
    fun `empty or unknown storage falls back to the default`() {
        assertEquals(DjNewsCategory.Default, DjNewsCategory.fromStorage(emptySet()))
        assertEquals(DjNewsCategory.Default, DjNewsCategory.fromStorage(setOf("NOPE")))
    }

    @Test
    fun `normalize and storage replace an empty selection with the default`() {
        assertEquals(DjNewsCategory.Default, DjNewsCategory.normalize(emptySet()))
        assertEquals(
            DjNewsCategory.Default.map { it.name }.toSet(),
            DjNewsCategory.toStorage(emptySet()),
        )
    }

    @Test
    fun `urls build home and topic feeds for an edition`() {
        val edition = DjNewsUrls.fromLocale(Locale.forLanguageTag("pt-PT"))

        assertEquals(
            "https://news.google.com/rss?hl=pt&gl=PT&ceid=PT:pt",
            DjNewsUrls.home(edition),
        )
        assertEquals(
            "https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=pt&gl=PT&ceid=PT:pt",
            DjNewsUrls.topic(DjNewsCategory.TECHNOLOGY, edition),
        )
        assertEquals(
            "https://news.google.com/rss/headlines/section/topic/WORLD?hl=en-US&gl=US&ceid=US:en",
            DjNewsUrls.topic(DjNewsCategory.WORLD, DjNewsUrls.GlobalEdition),
        )
    }

    @Test
    fun `missing locale region falls back to the global edition`() {
        assertEquals(
            DjNewsUrls.GlobalEdition,
            DjNewsUrls.fromLocale(Locale.forLanguageTag("pt")),
        )
    }
}
