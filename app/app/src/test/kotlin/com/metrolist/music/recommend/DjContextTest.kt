/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjContextTest {
    @Test
    fun `summary formats time city weather and headlines deterministically`() {
        val snapshot =
            DjContextSnapshot(
                time = LocalTime.of(18, 42),
                weekday = "Friday",
                city = "Lisbon",
                weather = DjWeather(temperatureC = 21, conditions = "mostly clear"),
                headlines = listOf("Metro line reopens", "Storm reaches the coast"),
            )

        assertEquals(
            "Friday 18:42 · Lisbon, 21°C mostly clear · News: Metro line reopens; Storm reaches the coast",
            snapshot.promptSummary(),
        )
    }

    @Test
    fun `summary keeps only the first two headlines`() {
        val snapshot =
            DjContextSnapshot(
                time = LocalTime.of(7, 5),
                weekday = "Monday",
                headlines = listOf("One", "Two", "Three"),
            )

        assertEquals("Monday 07:05 · News: One; Two", snapshot.promptSummary())
    }

    @Test
    fun `summary falls back to whatever data exists`() {
        assertEquals("", DjContextSnapshot().promptSummary())
        assertEquals("18:42", DjContextSnapshot(time = LocalTime.of(18, 42)).promptSummary())
        assertEquals("Friday", DjContextSnapshot(weekday = "Friday").promptSummary())
        assertEquals("Porto", DjContextSnapshot(city = "Porto").promptSummary())
        assertEquals(
            "0°C rain",
            DjContextSnapshot(weather = DjWeather(0, "rain")).promptSummary(),
        )
        assertEquals(
            "News: Only headline",
            DjContextSnapshot(headlines = listOf("Only headline")).promptSummary(),
        )
    }

    @Test
    fun `summary is capped at the prompt limit`() {
        val snapshot =
            DjContextSnapshot(
                time = LocalTime.of(23, 59),
                weekday = "Wednesday",
                city = "City ".repeat(40).trim(),
                weather = DjWeather(-12, "heavy snow showers"),
                headlines = listOf("h".repeat(90), "i".repeat(90)),
            )

        val summary = snapshot.promptSummary()

        assertTrue(summary.length <= DjContextSummaryLimit)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `summary respects a custom limit`() {
        val snapshot = DjContextSnapshot(weekday = "Friday", city = "Lisbon", headlines = listOf("One", "Two"))

        val summary = snapshot.promptSummary(maxLength = 20)

        assertTrue(summary.length <= 20)
    }

    @Test
    fun `zero limit yields an empty summary`() {
        assertEquals("", DjContextSnapshot(weekday = "Friday").promptSummary(maxLength = 0))
    }

    @Test
    fun `weather parser reads the current block`() {
        val body =
            """
            {"latitude":38.7,"longitude":-9.1,"current":{"time":"2026-11-13T18:30",
            "temperature_2m":21.4,"weather_code":2}}
            """.trimIndent()

        assertEquals(DjWeather(21, "partly cloudy"), DjWeatherParser.parseWeather(body))
    }

    @Test
    fun `weather parser maps wmo ranges`() {
        fun weather(code: Int) =
            DjWeatherParser.parseWeather(
                """{"current":{"temperature_2m":10.0,"weather_code":$code}}""",
            )?.conditions

        assertEquals("clear", weather(0))
        assertEquals("fog", weather(45))
        assertEquals("drizzle", weather(53))
        assertEquals("rain", weather(65))
        assertEquals("snow", weather(73))
        assertEquals("showers", weather(81))
        assertEquals("snow showers", weather(86))
        assertEquals("thunderstorm", weather(96))
        assertEquals("unsettled", weather(199))
    }

    @Test
    fun `weather parser rejects malformed and empty payloads`() {
        assertNull(DjWeatherParser.parseWeather(""))
        assertNull(DjWeatherParser.parseWeather("not json"))
        assertNull(DjWeatherParser.parseWeather("""{"current":{}}"""))
        assertNull(DjWeatherParser.parseWeather("""{"latitude":1}"""))
    }

    @Test
    fun `geocoding parser returns the first match`() {
        val body =
            """
            {"results":[{"name":"Lisbon","country":"Portugal","latitude":38.7167,"longitude":-9.1333},
            {"name":"Lisbon","country":"United States","latitude":40.0,"longitude":-80.0}]}
            """.trimIndent()

        val place = DjGeocodingParser.parseFirst(body)

        assertEquals("Lisbon", place?.name)
        assertEquals(38.7167, place?.latitude ?: 0.0, 0.0001)
        assertNull(DjGeocodingParser.parseFirst("""{"results":[]}"""))
        assertNull(DjGeocodingParser.parseFirst("nope"))
    }

    @Test
    fun `news parser reads titles decodes entities and drops the source`() {
        val feed =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Top stories</title>
              <item><title>Metro &amp; tram line reopens - Reuters</title><link>https://example.com/1</link></item>
              <item><title><![CDATA[Storm reaches the coast <b>tonight</b> - BBC]]></title></item>
              <item><title>Rates hold at 3&#37; - Financial Times</title></item>
              <item><title>Metro &amp; tram line reopens - Reuters</title></item>
            </channel></rss>
            """.trimIndent()

        assertEquals(
            listOf("Metro & tram line reopens", "Storm reaches the coast tonight", "Rates hold at 3%"),
            DjNewsParser.parse(feed, limit = 5),
        )
    }

    @Test
    fun `news parser honours the limit and survives junk`() {
        val feed =
            """
            <rss><channel>
              <item><title>One - Source</title></item>
              <item><title>Two - Source</title></item>
              <item><title>Three - Source</title></item>
            </channel></rss>
            """.trimIndent()

        assertEquals(listOf("One", "Two"), DjNewsParser.parse(feed, limit = 2))
        assertEquals(emptyList<String>(), DjNewsParser.parse("", limit = 3))
        assertEquals(emptyList<String>(), DjNewsParser.parse("<rss><channel/></rss>", limit = 3))
    }
}
