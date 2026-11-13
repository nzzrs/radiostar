/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.RadioStarDjContextCityKey
import com.metrolist.music.constants.RadioStarDjContextKey
import com.metrolist.music.constants.RadioStarDjContextNewsKey
import com.metrolist.music.constants.RadioStarDjContextWeatherKey
import com.metrolist.music.utils.dataStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Caller-supplied preferences so the provider stays free of DataStore. */
data class DjContextSettings(
    val enabled: Boolean = true,
    val weatherEnabled: Boolean = false,
    val newsEnabled: Boolean = false,
    val manualCity: String = "",
)

fun interface DjHttpFetcher {
    fun get(url: String): String?
}

fun interface DjCityLocator {
    fun locate(): String?
}

/**
 * Gathers the local context the DJ may mention: time, city, weather and
 * headlines. Work happens off the main thread, failures stay silent, and
 * remote data is cached in memory for [CACHE_TTL_MS].
 */
class DjContextProvider(
    private val cityLocator: DjCityLocator,
    private val fetcher: DjHttpFetcher = DjHttpFetcher(::httpGet),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private data class Cached<T>(
        val value: T,
        val at: Long,
        val key: String = "",
    )

    @Volatile private var locatedCity: Cached<String?>? = null

    @Volatile private var weather: Cached<DjWeather?>? = null

    @Volatile private var headlines: Cached<List<String>>? = null

    /** Ready-to-inject sentence, or an empty string when context is off. */
    suspend fun summary(settings: DjContextSettings): String = snapshot(settings)?.promptSummary().orEmpty()

    suspend fun snapshot(settings: DjContextSettings): DjContextSnapshot? {
        if (!settings.enabled) return null
        val moment = Instant.ofEpochMilli(clock()).atZone(zone())
        val city = resolveCity(settings)
        val weather = if (settings.weatherEnabled && !city.isNullOrBlank()) resolveWeather(city) else null
        val news = if (settings.newsEnabled) resolveHeadlines() else emptyList()
        return DjContextSnapshot(
            time = moment.toLocalTime(),
            weekday = weekdayName(moment.dayOfWeek),
            city = city.orEmpty(),
            weather = weather,
            headlines = news,
        )
    }

    private suspend fun resolveCity(settings: DjContextSettings): String? {
        val cached = locatedCity
        val located =
            if (cached != null && isFresh(cached.at)) {
                cached.value
            } else {
                val fresh =
                    withContext(Dispatchers.IO) {
                        runCatching { cityLocator.locate() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                locatedCity = Cached(fresh, clock())
                fresh
            }
        return located ?: settings.manualCity.trim().takeIf { it.isNotEmpty() }
    }

    private suspend fun resolveWeather(city: String): DjWeather? {
        val cached = weather
        if (cached != null && cached.key == city && isFresh(cached.at)) return cached.value
        val fresh = withContext(Dispatchers.IO) { fetchWeather(city) }
        if (fresh != null) {
            weather = Cached(fresh, clock(), city)
            return fresh
        }
        return cached?.takeIf { it.key == city }?.value
    }

    private suspend fun resolveHeadlines(): List<String> {
        val cached = headlines
        if (cached != null && isFresh(cached.at)) return cached.value
        val fresh = withContext(Dispatchers.IO) { fetchHeadlines() }
        if (fresh != null) {
            headlines = Cached(fresh, clock())
            return fresh
        }
        return cached?.value.orEmpty()
    }

    private fun fetchWeather(city: String): DjWeather? {
        val query = URLEncoder.encode(city, Charsets.UTF_8.name())
        val geocoding = fetcher.get("$GEOCODING_URL?name=$query&count=1&language=en&format=json") ?: return null
        val place = DjGeocodingParser.parseFirst(geocoding) ?: return null
        val weatherBody =
            fetcher.get(
                "$WEATHER_URL?latitude=${place.latitude}&longitude=${place.longitude}" +
                    "&current=temperature_2m,weather_code&timezone=auto",
            ) ?: return null
        return DjWeatherParser.parseWeather(weatherBody)
    }

    private fun fetchHeadlines(): List<String>? {
        val body = fetcher.get(NEWS_URL) ?: return null
        return DjNewsParser.parse(body, NEWS_LIMIT).takeIf { it.isNotEmpty() }
    }

    private fun isFresh(at: Long): Boolean {
        val age = clock() - at
        return age in 0 until CACHE_TTL_MS
    }

    private fun weekdayName(day: DayOfWeek): String {
        val name = day.name.lowercase(Locale.ENGLISH).replace('_', ' ')
        return name.replaceFirstChar { it.uppercase(Locale.ENGLISH) }
    }

    /** Android-backed provider using coarse last-known location. */
    companion object {
        const val CACHE_TTL_MS = 30 * 60_000L

        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val WEATHER_URL = "https://api.open-meteo.com/v1/forecast"
        private const val NEWS_URL = "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"
        private const val NEWS_LIMIT = 3
        private const val TIMEOUT_MS = 5_000

        fun create(context: Context): DjContextProvider {
            val appContext = context.applicationContext
            return DjContextProvider(cityLocator = LocationCityLocator(appContext))
        }

        /** One provider per process, so the remote caches survive between links. */
        fun shared(context: Context): DjContextProvider =
            shared ?: synchronized(this) {
                shared ?: create(context).also { shared = it }
            }

        fun fromPreferences(prefs: Preferences): DjContextSettings =
            DjContextSettings(
                enabled = prefs[RadioStarDjContextKey] ?: true,
                weatherEnabled = prefs[RadioStarDjContextWeatherKey] ?: false,
                newsEnabled = prefs[RadioStarDjContextNewsKey] ?: false,
                manualCity = prefs[RadioStarDjContextCityKey].orEmpty(),
            )

        suspend fun settings(context: Context): DjContextSettings =
            fromPreferences(context.dataStore.data.first())

        /**
         * Ready-to-inject sentence for the shared provider. Location is only
         * used when the permission already exists; the manual city is the
         * fallback, so no permission prompt is tied to this call.
         */
        suspend fun summary(context: Context): String =
            shared(context).summary(settings(context))

        @Volatile private var shared: DjContextProvider? = null

        private fun httpGet(url: String): String? =
            runCatching {
                val connection =
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        setRequestProperty("User-Agent", "RadioStar")
                    }
                try {
                    if (connection.responseCode !in 200..299) {
                        null
                    } else {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
    }
}

/**
 * Resolves a city from the coarse last-known location. It checks the
 * permission on every call so revoking it in Settings takes effect at once.
 */
class LocationCityLocator(private val context: Context) : DjCityLocator {
    override fun locate(): String? {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location =
            PROVIDERS
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .filter { it.hasAccuracy() || it.time > 0 }
                .maxByOrNull { it.time }
                ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private companion object {
        val PROVIDERS =
            listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
    }
}
