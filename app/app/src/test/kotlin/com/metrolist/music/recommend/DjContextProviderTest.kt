/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjContextProviderTest {
    private val zone = ZoneId.of("UTC")
    private var now = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    private val geocodingBody =
        """{"results":[{"name":"Lisbon","country":"Portugal","latitude":38.7,"longitude":-9.1}]}"""
    private val weatherBody = """{"current":{"temperature_2m":19.6,"weather_code":3}}"""
    private val newsBody =
        """
        <rss><channel>
          <item><title>First headline - Source</title></item>
          <item><title>Second headline - Source</title></item>
        </channel></rss>
        """.trimIndent()

    private var cityCalls = 0
    private var geocodingCalls = 0
    private var weatherCalls = 0
    private var newsCalls = 0
    private var failNetwork = false
    private var locale: Locale = Locale.forLanguageTag("pt-PT")
    private val newsUrls = mutableListOf<String>()
    private val newsBodies = mutableMapOf<String, String>()
    private val failNewsUrls = mutableSetOf<String>()

    private fun provider(): DjContextProvider =
        DjContextProvider(
            cityLocator = DjCityLocator {
                cityCalls++
                "Lisbon"
            },
            fetcher =
                DjHttpFetcher { url ->
                    if (failNetwork) return@DjHttpFetcher null
                    when {
                        url.contains("geocoding-api") -> {
                            geocodingCalls++
                            geocodingBody
                        }

                        url.contains("api.open-meteo.com") -> {
                            weatherCalls++
                            weatherBody
                        }

                        url.contains("news.google.com") -> {
                            if (url in failNewsUrls) return@DjHttpFetcher null
                            newsCalls++
                            newsUrls += url
                            newsBodies[url] ?: newsBody
                        }

                        else -> null
                    }
                },
            clock = { now },
            zone = { zone },
            locale = { locale },
        )

    private val everything = DjContextSettings(enabled = true, weatherEnabled = true, newsEnabled = true)

    private fun global(category: DjNewsCategory): String = DjNewsUrls.topic(category, DjNewsUrls.GlobalEdition)

    private fun local(category: DjNewsCategory): String =
        DjNewsUrls.topic(category, DjNewsUrls.fromLocale(locale))

    private fun feed(vararg titles: String): String =
        "<rss><channel>" +
            titles.joinToString("") { "<item><title>$it - Source</title></item>" } +
            "</channel></rss>"

    @Test
    fun `master toggle off returns an empty summary without touching sensors or network`() =
        runBlocking {
            val summary =
                provider().summary(
                    DjContextSettings(enabled = false, weatherEnabled = true, newsEnabled = true),
                )

            assertEquals("", summary)
            assertEquals(0, cityCalls)
            assertEquals(0, geocodingCalls)
            assertEquals(0, weatherCalls)
            assertEquals(0, newsCalls)
        }

    @Test
    fun `weather and news toggles off skip their network calls`() =
        runBlocking {
            val summary = provider().summary(DjContextSettings(enabled = true))

            assertTrue(summary.contains("Lisbon"))
            assertFalse(summary.contains("°C"))
            assertFalse(summary.contains("News:"))
            assertEquals(1, cityCalls)
            assertEquals(0, geocodingCalls)
            assertEquals(0, weatherCalls)
            assertEquals(0, newsCalls)
        }

    @Test
    fun `enabled toggles fill weather and headlines`() =
        runBlocking {
            val summary = provider().summary(everything)

            assertTrue(summary.contains("Lisbon, 20°C overcast"))
            assertTrue(summary.contains("News: 2 world: First headline; Second headline"))
            assertEquals(1, geocodingCalls)
            assertEquals(1, weatherCalls)
            assertEquals(4, newsCalls)
        }

    @Test
    fun `weather and news stay cached inside the ttl`() =
        runBlocking {
            val subject = provider()
            subject.summary(everything)
            now += DjContextProvider.CACHE_TTL_MS - 1
            subject.summary(everything)

            assertEquals(1, geocodingCalls)
            assertEquals(1, weatherCalls)
            assertEquals(4, newsCalls)
        }

    @Test
    fun `expired cache refreshes weather and news`() =
        runBlocking {
            val subject = provider()
            subject.summary(everything)
            now += DjContextProvider.CACHE_TTL_MS + 1
            subject.summary(everything)

            assertEquals(2, geocodingCalls)
            assertEquals(2, weatherCalls)
            assertEquals(8, newsCalls)
        }

    @Test
    fun `manual city is the fallback when location is unavailable`() =
        runBlocking {
            val subject =
                DjContextProvider(
                    cityLocator = DjCityLocator { null },
                    fetcher = DjHttpFetcher { null },
                    clock = { now },
                    zone = { zone },
                )

            val summary = subject.summary(DjContextSettings(enabled = true, manualCity = " Porto "))

            assertTrue(summary.contains("Porto"))
        }

    @Test
    fun `network failures stay silent and keep the local parts`() =
        runBlocking {
            failNetwork = true

            val summary = provider().summary(everything)

            assertTrue(summary.contains("Lisbon"))
            assertFalse(summary.contains("°C"))
            assertFalse(summary.contains("News:"))
        }

    @Test
    fun `stale data is reused when a refresh fails`() =
        runBlocking {
            val subject = provider()
            subject.summary(everything)
            now += DjContextProvider.CACHE_TTL_MS + 1
            failNetwork = true

            val summary = subject.summary(everything)

            assertTrue(summary.contains("20°C overcast"))
            assertTrue(summary.contains("First headline"))
        }

    @Test
    fun `news fetches global and local editions for the selected categories`() =
        runBlocking {
            provider().snapshot(
                DjContextSettings(
                    enabled = true,
                    newsEnabled = true,
                    newsCategories = setOf(DjNewsCategory.WORLD, DjNewsCategory.TECHNOLOGY),
                ),
            )

            assertEquals(
                listOf(
                    "https://news.google.com/rss/headlines/section/topic/WORLD?hl=en-US&gl=US&ceid=US:en",
                    "https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=en-US&gl=US&ceid=US:en",
                    "https://news.google.com/rss/headlines/section/topic/WORLD?hl=pt&gl=PT&ceid=PT:pt",
                    "https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=pt&gl=PT&ceid=PT:pt",
                ),
                newsUrls,
            )
        }

    @Test
    fun `empty categories fall back to world and nation feeds`() =
        runBlocking {
            provider().snapshot(
                DjContextSettings(enabled = true, newsEnabled = true, newsCategories = emptySet()),
            )

            assertEquals(4, newsUrls.size)
            assertTrue(newsUrls.any { it.contains("/WORLD?") && it.contains("hl=en-US") })
            assertTrue(newsUrls.any { it.contains("/NATION?") && it.contains("hl=en-US") })
            assertTrue(newsUrls.any { it.contains("/WORLD?") && it.contains("hl=pt") })
            assertTrue(newsUrls.any { it.contains("/NATION?") && it.contains("hl=pt") })
        }

    @Test
    fun `news keeps five global and ten local headlines in total`() =
        runBlocking {
            newsBodies[global(DjNewsCategory.WORLD)] = feed(*(0 until 20).map { "Global $it" }.toTypedArray())
            newsBodies[local(DjNewsCategory.WORLD)] = feed(*(0 until 20).map { "Local $it" }.toTypedArray())

            val snapshot =
                provider().snapshot(
                    DjContextSettings(
                        enabled = true,
                        newsEnabled = true,
                        newsCategories = setOf(DjNewsCategory.WORLD, DjNewsCategory.NATION),
                    ),
                )

            assertEquals(5, snapshot?.globalHeadlines?.size)
            assertEquals("Global 0", snapshot?.globalHeadlines?.first())
            assertEquals(10, snapshot?.localHeadlines?.size)
            assertEquals("Local 0", snapshot?.localHeadlines?.first())
        }

    @Test
    fun `news dedupes titles across feeds and editions`() =
        runBlocking {
            newsBodies[global(DjNewsCategory.WORLD)] = feed("Same story", "Global only")
            newsBodies[global(DjNewsCategory.NATION)] = feed("Same story", "Global second")
            newsBodies[local(DjNewsCategory.WORLD)] = feed("Same story", "Local only")
            newsBodies[local(DjNewsCategory.NATION)] = feed("Local only", "Local second")

            val snapshot =
                provider().snapshot(
                    DjContextSettings(
                        enabled = true,
                        newsEnabled = true,
                        newsCategories = setOf(DjNewsCategory.WORLD, DjNewsCategory.NATION),
                    ),
                )

            assertEquals(listOf("Same story", "Global only", "Global second"), snapshot?.globalHeadlines)
            assertEquals(listOf("Local only", "Local second"), snapshot?.localHeadlines)
        }

    @Test
    fun `per feed failures keep headlines from healthy feeds`() =
        runBlocking {
            failNewsUrls += global(DjNewsCategory.NATION)
            failNewsUrls += local(DjNewsCategory.WORLD)
            newsBodies[global(DjNewsCategory.WORLD)] = feed("Global story")
            newsBodies[local(DjNewsCategory.NATION)] = feed("Local story")

            val snapshot =
                provider().snapshot(
                    DjContextSettings(
                        enabled = true,
                        newsEnabled = true,
                        newsCategories = setOf(DjNewsCategory.WORLD, DjNewsCategory.NATION),
                    ),
                )

            assertEquals(listOf("Global story"), snapshot?.globalHeadlines)
            assertEquals(listOf("Local story"), snapshot?.localHeadlines)
        }

    @Test
    fun `news cache key covers country language and categories`() =
        runBlocking {
            val subject = provider()
            val base = DjContextSettings(enabled = true, newsEnabled = true)
            subject.snapshot(base)
            assertEquals(4, newsUrls.size)
            subject.snapshot(base)
            assertEquals(4, newsUrls.size)

            subject.snapshot(base.copy(newsCategories = setOf(DjNewsCategory.BUSINESS)))
            assertEquals(6, newsUrls.size)
            subject.snapshot(base.copy(newsCategories = setOf(DjNewsCategory.BUSINESS)))
            assertEquals(6, newsUrls.size)

            locale = Locale.forLanguageTag("es-ES")
            subject.snapshot(base)
            assertEquals(10, newsUrls.size)
            assertTrue(newsUrls.last().contains("hl=es&gl=ES&ceid=ES:es"))
        }
}
