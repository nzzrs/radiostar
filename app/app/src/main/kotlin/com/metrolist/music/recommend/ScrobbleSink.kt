/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.metrolist.lastfm.LastFM
import com.metrolist.music.constants.EnableLastFMScrobblingKey
import com.metrolist.music.constants.ScrobbleCustomTokenKey
import com.metrolist.music.constants.ScrobbleCustomUrlKey
import com.metrolist.music.constants.ScrobbleDestinationKey
import com.metrolist.music.constants.ScrobbleOutboundListensKey
import com.metrolist.music.constants.ScrobbleTokenKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.utils.dataStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Outbound scrobbles. The destination is deliberately independent from the
 * listens import source: a user can import from ListenBrainz and submit to
 * Last.fm, or keep the app silent because another app already scrobbles.
 */
enum class ScrobbleDestination(
    val id: String,
    val label: String,
) {
    OFF("off", "Off"),
    LASTFM("lastfm", "Last.fm"),
    LISTENBRAINZ("listenbrainz", "ListenBrainz"),
    CUSTOM("custom", "Custom"),
    EXTERNAL("external", "External scrobbler"),
    ;

    companion object {
        fun fromId(id: String?): ScrobbleDestination =
            values().firstOrNull { it.id == id } ?: OFF

        /** Legacy Last.fm switch still works until the user picks a destination. */
        fun effective(preferences: Preferences): ScrobbleDestination {
            if (preferences[ScrobbleDestinationKey] == null &&
                preferences[EnableLastFMScrobblingKey] == true
            ) {
                return LASTFM
            }
            return fromId(preferences[ScrobbleDestinationKey])
        }
    }
}

object ScrobbleSink {
    private const val TAG = "RadioStarScrobbleSink"
    private const val LISTENBRAINZ_ROOT = "https://api.listenbrainz.org"

    suspend fun nowPlaying(
        context: Context,
        artist: String,
        track: String,
        album: String?,
        durationSeconds: Int?,
    ) = withContext(Dispatchers.IO) {
        val preferences = context.dataStore.data.first()
        // Reaction mirroring stays active when listen submission is off.
        if (preferences[ScrobbleOutboundListensKey] == false) return@withContext
        when (ScrobbleDestination.effective(preferences)) {
            ScrobbleDestination.LASTFM -> {
                if (LastFM.isInitialized() && !LastFM.sessionKey.isNullOrBlank()) {
                    LastFM.updateNowPlaying(
                        artist = artist,
                        track = track,
                        album = album,
                        duration = durationSeconds,
                    )
                }
            }
            ScrobbleDestination.LISTENBRAINZ -> {
                submit(
                    root = LISTENBRAINZ_ROOT,
                    token = preferences[ScrobbleTokenKey].orEmpty()
                        .ifBlank { preferences[ScrobblerTokenKey].orEmpty() },
                    body = listenBody("playing_now", artist, track, album, durationSeconds, null),
                )
            }
            ScrobbleDestination.CUSTOM -> {
                submit(
                    root = preferences[ScrobbleCustomUrlKey].orEmpty(),
                    token = preferences[ScrobbleCustomTokenKey].orEmpty()
                        .ifBlank { preferences[ScrobbleTokenKey].orEmpty() }
                        .ifBlank { preferences[ScrobblerTokenKey].orEmpty() },
                    body = listenBody("playing_now", artist, track, album, durationSeconds, null),
                )
            }
            ScrobbleDestination.OFF,
            ScrobbleDestination.EXTERNAL,
            -> Unit
        }
    }

    suspend fun scrobble(
        context: Context,
        artist: String,
        track: String,
        album: String?,
        durationSeconds: Int?,
        timestampSeconds: Long,
    ) = withContext(Dispatchers.IO) {
        val preferences = context.dataStore.data.first()
        if (preferences[ScrobbleOutboundListensKey] == false) return@withContext
        when (ScrobbleDestination.effective(preferences)) {
            ScrobbleDestination.LASTFM -> {
                if (LastFM.isInitialized() && !LastFM.sessionKey.isNullOrBlank()) {
                    LastFM.scrobble(
                        artist = artist,
                        track = track,
                        timestamp = timestampSeconds,
                        album = album,
                        duration = durationSeconds,
                    )
                }
            }
            ScrobbleDestination.LISTENBRAINZ -> {
                submit(
                    root = LISTENBRAINZ_ROOT,
                    token = preferences[ScrobbleTokenKey].orEmpty()
                        .ifBlank { preferences[ScrobblerTokenKey].orEmpty() },
                    body =
                        listenBody(
                            "single",
                            artist,
                            track,
                            album,
                            durationSeconds,
                            timestampSeconds,
                        ),
                )
            }
            ScrobbleDestination.CUSTOM -> {
                submit(
                    root = preferences[ScrobbleCustomUrlKey].orEmpty(),
                    token = preferences[ScrobbleCustomTokenKey].orEmpty()
                        .ifBlank { preferences[ScrobbleTokenKey].orEmpty() }
                        .ifBlank { preferences[ScrobblerTokenKey].orEmpty() },
                    body =
                        listenBody(
                            "single",
                            artist,
                            track,
                            album,
                            durationSeconds,
                            timestampSeconds,
                        ),
                )
            }
            ScrobbleDestination.OFF,
            ScrobbleDestination.EXTERNAL,
            -> Unit
        }
    }

    private fun listenBody(
        type: String,
        artist: String,
        track: String,
        album: String?,
        durationSeconds: Int?,
        timestampSeconds: Long?,
    ): JSONObject {
        val metadata =
            JSONObject().apply {
                put("artist_name", artist)
                put("track_name", track)
                album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                put(
                    "additional_info",
                    JSONObject().apply {
                        put("media_player", "RadioStar")
                        put("submission_client", "RadioStar")
                        durationSeconds?.let { put("duration_ms", it * 1000L) }
                    },
                )
            }
        val listen =
            JSONObject().apply {
                timestampSeconds?.let { put("listened_at", it) }
                put("track_metadata", metadata)
            }
        return JSONObject().apply {
            put("listen_type", type)
            put("payload", JSONArray().put(listen))
        }
    }

    private fun submit(
        root: String,
        token: String,
        body: JSONObject,
    ) {
        if (root.isBlank() || token.isBlank()) return
        val endpoint =
            when {
                root.endsWith("/1/submit-listens") -> root
                root.endsWith("/1") -> "$root/submit-listens"
                else -> "${root.trimEnd('/')}/1/submit-listens"
            }
        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 15_000
                setRequestProperty("content-type", "application/json")
                setRequestProperty("authorization", "Token $token")
                setRequestProperty("user-agent", "RadioStar/1.0")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Timber.tag(TAG).w("submit failed code=%d %s", code, detail.take(200))
            }
        }.onFailure { Timber.tag(TAG).w(it, "submit failed for $endpoint") }
    }
}
