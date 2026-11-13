/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Upper bound for the context sentence injected into DJ prompts. */
const val DjContextSummaryLimit = 180

private val djTimeFormat = DateTimeFormatter.ofPattern("HH:mm")

data class DjWeather(
    val temperatureC: Int,
    val conditions: String,
)

data class DjContextSnapshot(
    val time: LocalTime? = null,
    val weekday: String = "",
    val city: String = "",
    val weather: DjWeather? = null,
    val headlines: List<String> = emptyList(),
) {
    /**
     * One short, deterministic sentence for prompts. Missing pieces are
     * skipped; with no data at all the result is empty.
     */
    fun promptSummary(maxLength: Int = DjContextSummaryLimit): String {
        if (maxLength <= 0) return ""
        val news = headlines.filter { it.isNotBlank() }
        var keepHeadlines = minOf(news.size, 2)
        var text = render(news, keepHeadlines)
        while (text.length > maxLength && keepHeadlines > 0) {
            keepHeadlines = if (keepHeadlines > 1) 1 else 0
            text = render(news, keepHeadlines)
        }
        if (text.length <= maxLength) return text
        return text.take(maxLength - 1).trimEnd() + "…"
    }

    private fun render(
        headlines: List<String>,
        headlineCount: Int,
    ): String {
        val moment = weekday.trim()
        val clock = time?.format(djTimeFormat).orEmpty()
        val whenPart = listOf(moment, clock).filter { it.isNotEmpty() }.joinToString(" ")
        val placePart =
            buildString {
                if (city.isNotBlank()) append(city.trim())
                weather?.let {
                    if (isNotEmpty()) append(", ")
                    append("${it.temperatureC}°C ${it.conditions.trim()}")
                }
            }
        val newsPart =
            headlines
                .take(headlineCount)
                .joinToString("; ")
                .takeIf { it.isNotEmpty() }
                ?.let { "News: $it" }
                .orEmpty()
        return listOf(whenPart, placePart, newsPart).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

/** Open-Meteo current weather payload. */
object DjWeatherParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class WeatherResponse(
        val current: CurrentWeather? = null,
    )

    @Serializable
    private data class CurrentWeather(
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("weather_code") val code: Int? = null,
    )

    fun parseWeather(body: String): DjWeather? {
        if (body.isBlank()) return null
        val current = runCatching { json.decodeFromString<WeatherResponse>(body).current }.getOrNull() ?: return null
        val temperature = current.temperature ?: return null
        return DjWeather(temperature.roundToInt(), weatherLabel(current.code))
    }

    /** WMO weather code to a short, speakable label. */
    fun weatherLabel(code: Int?): String =
        when (code) {
            null -> "unsettled"
            0 -> "clear"
            1 -> "mostly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            in 51..57 -> "drizzle"
            in 61..67 -> "rain"
            in 71..77 -> "snow"
            in 80..82 -> "showers"
            in 85..86 -> "snow showers"
            in 95..99 -> "thunderstorm"
            else -> "unsettled"
        }
}

@Serializable
data class DjGeoPlace(
    val name: String,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
)

/** Open-Meteo geocoding payload. */
object DjGeocodingParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class GeocodingResponse(
        val results: List<DjGeoPlace> = emptyList(),
    )

    fun parseFirst(body: String): DjGeoPlace? =
        runCatching { json.decodeFromString<GeocodingResponse>(body).results.firstOrNull() }.getOrNull()
}

/** Minimal RSS reader for public news feeds. */
object DjNewsParser {
    private val itemPattern =
        Regex(
            "<item\\b[^>]*>(.*?)</item>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val titlePattern =
        Regex(
            "<title\\b[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val tagPattern = Regex("<[^>]+>")
    private val whitespacePattern = Regex("\\s+")
    private val entityPattern = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val sourcePattern = Regex("\\s+[-–—]\\s+[^-–—]{1,40}$")
    private val namedEntities =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
        )

    fun parse(
        xml: String,
        limit: Int = 3,
    ): List<String> {
        if (limit <= 0 || xml.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        for (item in itemPattern.findAll(xml)) {
            val rawTitle = titlePattern.find(item.groupValues[1])?.groupValues?.get(1) ?: continue
            val title = cleanTitle(rawTitle)
            if (title.isNotEmpty()) result.add(title)
            if (result.size >= limit) break
        }
        return result.toList()
    }

    fun cleanTitle(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("<![CDATA[") && text.endsWith("]]>")) {
            text = text.substring(9, text.length - 3)
        }
        text = tagPattern.replace(text, "")
        text = decodeEntities(text)
        text = sourcePattern.replace(text, "")
        return whitespacePattern.replace(text, " ").trim()
    }

    private fun decodeEntities(text: String): String =
        entityPattern.replace(text) { match ->
            val token = match.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) ->
                    token.drop(2).toIntOrNull(16)?.let { codePointToString(it) } ?: match.value

                token.startsWith("#") ->
                    token.drop(1).toIntOrNull()?.let { codePointToString(it) } ?: match.value

                else -> namedEntities[token.lowercase()] ?: match.value
            }
        }

    private fun codePointToString(code: Int): String? =
        runCatching { String(Character.toChars(code)) }.getOrNull()
}
