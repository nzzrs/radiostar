/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import com.metrolist.lastfm.LastFM
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.LastFMUsernameKey
import com.metrolist.music.constants.ScrobbleCustomTokenKey
import com.metrolist.music.constants.ScrobbleCustomUrlKey
import com.metrolist.music.constants.ScrobbleDestinationKey
import com.metrolist.music.constants.ScrobbleTokenKey
import com.metrolist.music.constants.ScrobblerApiKeyKey
import com.metrolist.music.constants.ScrobblerLastFmUserKey
import com.metrolist.music.constants.ScrobblerListenBrainzUserKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.utils.dataStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class RemoteReaction(
    val videoId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val rating: Int,
    val updatedAtMs: Long,
    val key: String? = null,
)

/**
 * Love/hate mirror for the configured scrobbler.
 *
 * ListenBrainz and compatible servers need a recording MBID for feedback, so
 * the first lookup is resolved through their metadata endpoint. Last.fm only
 * has love/unlove; a dislike is therefore sent as unlove.
 */
object ScrobblerReactions {
    private const val TAG = "RadioStarReactions"
    private const val LISTENBRAINZ_ROOT = "https://api.listenbrainz.org"

    suspend fun push(
        context: Context,
        artist: String,
        title: String,
        album: String?,
        rating: Int,
    ) = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        when (ScrobbleDestination.effective(prefs)) {
            ScrobbleDestination.LASTFM -> {
                if (LastFM.isInitialized() && !LastFM.sessionKey.isNullOrBlank()) {
                    LastFM.setLoveStatus(artist, title, love = rating == 1)
                }
            }
            ScrobbleDestination.LISTENBRAINZ,
            ScrobbleDestination.CUSTOM,
            -> {
                val root =
                    if (ScrobbleDestination.effective(prefs) == ScrobbleDestination.LISTENBRAINZ) {
                        LISTENBRAINZ_ROOT
                    } else {
                        prefs[ScrobbleCustomUrlKey].orEmpty().trimEnd('/')
                    }
                val token =
                    prefs[ScrobbleTokenKey].orEmpty()
                        .ifBlank { prefs[ScrobblerTokenKey].orEmpty() }
                if (root.isBlank() || token.isBlank()) return@withContext
                val mbid = resolveMbid(root, artist, title) ?: return@withContext
                postJson(
                    "$root/1/feedback/recording-feedback",
                    token,
                    JSONObject().put("recording_mbid", mbid).put("score", rating),
                )
            }
            ScrobbleDestination.OFF,
            ScrobbleDestination.EXTERNAL,
            -> Unit
        }
    }

    suspend fun pull(context: Context): List<RemoteReaction> =
        withContext(Dispatchers.IO) {
            val prefs = context.dataStore.data.first()
            runCatching {
                when (ScrobbleDestination.effective(prefs)) {
                    ScrobbleDestination.LASTFM -> pullLastFm(prefs)
                    ScrobbleDestination.LISTENBRAINZ -> pullListenBrainz(LISTENBRAINZ_ROOT, prefs)
                    ScrobbleDestination.CUSTOM -> {
                        val root = prefs[ScrobbleCustomUrlKey].orEmpty().trimEnd('/')
                        if (root.isBlank()) emptyList() else pullListenBrainz(root, prefs)
                    }
                    ScrobbleDestination.OFF,
                    ScrobbleDestination.EXTERNAL,
                    -> emptyList()
                }
            }.onFailure { Timber.tag(TAG).w(it, "reaction pull failed") }.getOrDefault(emptyList())
        }

    private fun pullLastFm(prefs: androidx.datastore.preferences.core.Preferences): List<RemoteReaction> {
        val user =
            prefs[ScrobblerLastFmUserKey].orEmpty()
                .ifBlank { prefs[LastFMUsernameKey].orEmpty() }
        val apiKey =
            prefs[ScrobblerApiKeyKey].orEmpty().ifBlank { BuildConfig.LASTFM_API_KEY }
        if (user.isBlank() || apiKey.isBlank()) return emptyList()
        val body =
            getJson(
                "https://ws.audioscrobbler.com/2.0/?method=user.getLovedTracks" +
                    "&user=${encode(user)}&api_key=${encode(apiKey)}&format=json&limit=200",
            )
        val tracks = body.optJSONObject("lovedtracks")?.opt("track").asArray()
        return tracks.mapNotNull { track ->
            val title = track.optString("name")
            val artist = track.optJSONObject("artist")?.optString("#text") ?: track.optString("artist")
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            RemoteReaction(
                videoId = null,
                title = title,
                artist = artist,
                album = null,
                rating = 1,
                updatedAtMs = track.optJSONObject("date")?.optString("uts")?.toLongOrNull()?.times(1000) ?: 0L,
            )
        }
    }

    private fun pullListenBrainz(
        root: String,
        prefs: androidx.datastore.preferences.core.Preferences,
    ): List<RemoteReaction> {
        val token =
            prefs[ScrobbleTokenKey].orEmpty()
                .ifBlank { prefs[ScrobblerTokenKey].orEmpty() }
        if (token.isBlank()) return emptyList()
        val user =
            prefs[ScrobblerListenBrainzUserKey].orEmpty().ifBlank {
                runCatching {
                    getJson("$root/1/validate-token", token).optString("user_name")
                }.getOrNull().orEmpty()
            }
        if (user.isBlank()) return emptyList()
        val body =
            getJson(
                "$root/1/feedback/user/${encode(user)}/get-feedback?count=100&metadata=true",
                token,
            )
        val feedback = body.optJSONArray("feedback") ?: JSONArray()
        return buildList {
            for (index in 0 until feedback.length()) {
                val item = feedback.optJSONObject(index) ?: continue
                val metadata = item.optJSONObject("track_metadata") ?: continue
                val title = metadata.optString("track_name")
                val artist = metadata.optString("artist_name")
                val score = item.optInt("score", 0)
                if (title.isBlank() || artist.isBlank() || score == 0) continue
                add(
                    RemoteReaction(
                        videoId = null,
                        title = title,
                        artist = artist,
                        album = metadata.optString("release_name").takeIf { it.isNotBlank() },
                        rating = if (score > 0) 1 else -1,
                        updatedAtMs = item.optLong("created", 0L) * 1000L,
                    ),
                )
            }
        }
    }

    private fun resolveMbid(root: String, artist: String, title: String): String? =
        runCatching {
            val body =
                getJson(
                    "$root/1/metadata/lookup/?artist_name=${encode(artist)}&recording_name=${encode(title)}",
                )
            body.optString("recording_mbid").takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun getJson(url: String, token: String = ""): JSONObject {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("accept", "application/json")
                    setRequestProperty("user-agent", "RadioStar/1.0")
                    if (token.isNotBlank()) setRequestProperty("authorization", "Token $token")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${connection.responseCode}")
                    }
                    return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            } catch (error: Throwable) {
                last = error
            }
            Thread.sleep(400L * (attempt + 1))
        }
        throw last ?: IllegalStateException("request failed")
    }

    private fun postJson(url: String, token: String, body: JSONObject) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("authorization", "Token $token")
            setRequestProperty("user-agent", "RadioStar/1.0")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) {
                Timber.tag(TAG).w("feedback failed code=%d", connection.responseCode)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Any?.asArray(): List<JSONObject> =
        when (this) {
            is JSONArray -> List(length()) { optJSONObject(it) }.filterNotNull()
            is JSONObject -> listOf(this)
            else -> emptyList()
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
