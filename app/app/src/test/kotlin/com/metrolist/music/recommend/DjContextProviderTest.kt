/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.ZoneId
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
                            newsCalls++
                            newsBody
                        }

                        else -> null
                    }
                },
            clock = { now },
            zone = { zone },
        )

    private val everything = DjContextSettings(enabled = true, weatherEnabled = true, newsEnabled = true)

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
            assertTrue(summary.contains("News: First headline; Second headline"))
            assertEquals(1, geocodingCalls)
            assertEquals(1, weatherCalls)
            assertEquals(1, newsCalls)
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
            assertEquals(1, newsCalls)
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
            assertEquals(2, newsCalls)
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
}
