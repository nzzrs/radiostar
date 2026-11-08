/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.LastMonthlyMostPlaylistSyncKey
import com.metrolist.music.constants.LastWeeklyMostPlaylistSyncKey
import com.metrolist.music.constants.ScrobblerBaseUrlKey
import com.metrolist.music.constants.ScrobblerCheckpointsKey
import com.metrolist.music.constants.ScrobblerLastFmUserKey
import com.metrolist.music.constants.ScrobblerLastResultKey
import com.metrolist.music.constants.ScrobblerLastSyncKey
import com.metrolist.music.constants.ScrobblerLastTotalKey
import com.metrolist.music.constants.ScrobblerLastTotalServiceKey
import com.metrolist.music.constants.ScrobblerListenBrainzUserKey
import com.metrolist.music.constants.ScrobblerServiceKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.utils.dataStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

enum class ScrobblerService(
    val id: String,
    val label: String,
) {
    NONE("none", "Off"),
    LASTFM("lastfm", "Last.fm"),
    LISTENBRAINZ("listenbrainz", "ListenBrainz"),
    SELF_HOSTED("self_hosted", "Self-hosted"),
}

data class ScrobblerConfig(
    val service: ScrobblerService,
    val lastFmUser: String,
    val listenBrainzUser: String,
    val baseUrl: String,
    val token: String,
    val apiKey: String = "",
)

data class ListenImportResult(
    val added: Int,
    val fetched: Int,
    val total: Long?,
    val complete: Boolean,
    val message: String,
    val localTotal: Int = 0,
    val account: String? = null,
)

/**
 * Pulls listens into a local append-only store.
 *
 * Each page is fetched with bounded retries, validated, appended with
 * duplicate rejection, and only then advances the service checkpoint. A
 * failure leaves the checkpoint at the last committed page.
 */
object ScrobblerImporter {
    private const val TAG = "RadioStarScrobble"
    private const val MAX_PAGES = 1000
    private const val PAGE_SIZE = 1000
    private const val PAGE_DELAY_MS = 300L

    suspend fun import(
        context: Context,
        config: ScrobblerConfig,
        onProgress: (Int, Long?) -> Unit = { _, _ -> },
    ): ListenImportResult =
        withContext(Dispatchers.IO) {
            if (config.service == ScrobblerService.NONE) {
                return@withContext ListenImportResult(0, 0, null, false, "No scrobbler selected")
            }
            var effective = config
            if (config.service == ScrobblerService.LISTENBRAINZ && config.token.isNotBlank()) {
                val token = config.token.trim()
                val tokenCheck =
                    runCatching { fetchTokenUser("https://api.listenbrainz.org", token) }
                val tokenUser = tokenCheck.getOrNull()
                if (tokenUser == null) {
                    val reason =
                        tokenCheck.exceptionOrNull()?.message?.let { " Could not reach ListenBrainz: $it" }
                            ?: " ListenBrainz rejected the token."
                    return@withContext ListenImportResult(
                        0,
                        0,
                        null,
                        false,
                        "ListenBrainz account check failed.$reason",
                    )
                }
                effective = config.copy(listenBrainzUser = tokenUser)
            }
            val prefs = context.dataStore.data.first()
            val checkpoints = runCatching { JSONObject(prefs[ScrobblerCheckpointsKey].orEmpty()) }.getOrDefault(JSONObject())
            var since = checkpoints.optLong(effective.service.id, 0L)
            val store = ListenStore(context)
            var fetched = 0
            var added = 0
            var maxSeen = since
            var complete = false
            val total = fetchTotal(effective)
            // A previous capped run may have saved a checkpoint with only part
            // of the history. Backfill from zero when the local count is short.
            if (total != null && since > 0L) {
                val existing = store.load().count { it.service == effective.service.id }
                if (existing < total - 5L) since = 0L
            }
            try {
                when (effective.service) {
                    ScrobblerService.LASTFM -> {
                        val user = config.lastFmUser.trim()
                        if (user.isEmpty()) return@withContext ListenImportResult(0, 0, null, false, "Last.fm username missing")
                        val apiKey = config.apiKey.trim().ifBlank { BuildConfig.LASTFM_API_KEY }
                        if (apiKey.isEmpty()) {
                            return@withContext ListenImportResult(0, 0, null, false, "Last.fm API key not configured")
                        }
                        for (page in 1..MAX_PAGES) {
                            if (since == 0L && total != null && fetched >= total) break
                            val url =
                                "https://ws.audioscrobbler.com/2.0/?method=user.getRecentTracks" +
                                    "&user=${encode(user)}&api_key=${encode(apiKey)}" +
                                    "&format=json&limit=200&extended=1&page=$page" +
                                    if (since > 0) "&from=$since" else ""
                            val body = getJson(url)
                            val recent = body.optJSONObject("recenttracks") ?: break
                            val tracks = recent.opt("track").asArray()
                            if (tracks.isEmpty()) break
                            val records = tracks.mapNotNull { it.toLastFmListen() }
                            fetched += tracks.size
                            val pageMax = records.maxOfOrNull { it.listenedAt } ?: 0L
                            if (pageMax > maxSeen) maxSeen = pageMax
                            added += store.append(records)
                            onProgress(fetched, total)
                            val totalPages = recent.optJSONObject("@attr")?.optInt("totalPages", page) ?: page
                            if (page >= totalPages) break
                            delay(PAGE_DELAY_MS)
                        }
                        complete = since > 0L || total == null || fetched >= total - 2
                    }
                    ScrobblerService.LISTENBRAINZ,
                    ScrobblerService.SELF_HOSTED,
                    -> {
                        val user =
                            if (effective.service == ScrobblerService.LISTENBRAINZ) {
                                effective.listenBrainzUser.trim()
                            } else {
                                effective.lastFmUser.trim().ifBlank { effective.listenBrainzUser.trim() }
                            }
                        if (user.isEmpty()) {
                            return@withContext ListenImportResult(0, 0, null, false, "ListenBrainz username missing")
                        }
                        val root =
                            if (effective.service == ScrobblerService.LISTENBRAINZ) {
                                "https://api.listenbrainz.org"
                            } else {
                                effective.baseUrl.trim().trimEnd('/')
                            }
                        if (root.isBlank()) return@withContext ListenImportResult(0, 0, null, false, "Server URL missing")
                        val incremental = since > 0L
                        var cursor = 0L
                        var exhausted = false
                        for (page in 1..MAX_PAGES) {
                            val url =
                                "$root/1/user/${encode(user)}/listens?count=$PAGE_SIZE" +
                                    if (cursor > 0) "&max_ts=$cursor" else ""
                            val body = getJson(url, effective.token.trim())
                            val listens = body.optJSONObject("payload")?.optJSONArray("listens") ?: break
                            if (listens.length() == 0) {
                                exhausted = true
                                break
                            }
                            val pageRecords = listens.toListenRecords(effective.service.id)
                            fetched += listens.length()
                            val newRecords = if (incremental) pageRecords.filter { it.listenedAt > since } else pageRecords
                            val pageMax = newRecords.maxOfOrNull { it.listenedAt } ?: 0L
                            if (pageMax > maxSeen) maxSeen = pageMax
                            added += store.append(newRecords)
                            onProgress(fetched, total)
                            val pageMin = pageRecords.minOfOrNull { it.listenedAt } ?: 1L
                            if (incremental && pageMin <= since) {
                                exhausted = true
                                break
                            }
                            cursor = pageMin - 1L
                            if (cursor <= 0) {
                                exhausted = true
                                break
                            }
                            delay(PAGE_DELAY_MS)
                        }
                        // The service-reported count can lag behind the actual
                        // history, so only reaching the end of the history marks
                        // a full import as complete.
                        complete = exhausted
                    }
                    ScrobblerService.NONE -> Unit
                }
                val localTotal = store.count(effective.service.id)
                if (complete) saveCheckpoint(context, effective.service, maxSeen)
                context.dataStore.edit {
                    it[ScrobblerLastTotalKey] = total ?: 0L
                    it[ScrobblerLastTotalServiceKey] = effective.service.id
                    if (complete) {
                        // Imported history may already exist from an older build;
                        // rebuild the history-based Most playlists once even when
                        // this incremental pass found nothing new.
                        it[LastWeeklyMostPlaylistSyncKey] = 0L
                        it[LastMonthlyMostPlaylistSyncKey] = 0L
                        it[ScrobblerLastSyncKey] = System.currentTimeMillis()
                    }
                }
                val serviceTotal = total?.toString() ?: "?"
                val accountLabel =
                    effective.listenBrainzUser.trim().takeIf {
                        effective.service == ScrobblerService.LISTENBRAINZ ||
                            effective.service == ScrobblerService.SELF_HOSTED
                    }
                val checked =
                    if (since > 0L) {
                        "Incremental check: $fetched newest. Service reports $serviceTotal; local $localTotal."
                    } else {
                        "Full check: $fetched fetched, service reports $serviceTotal; local $localTotal."
                    }
                ListenImportResult(
                    added = added,
                    fetched = fetched,
                    total = total,
                    complete = complete,
                    localTotal = localTotal,
                    account = accountLabel,
                    message =
                        when {
                            !complete -> "Imported $added new. $checked Incomplete, will retry."
                            fetched == 0 -> "No new listens. $checked"
                            else -> "Imported $added new. $checked"
                        },
                )
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "listen import failed")
                ListenImportResult(
                    added,
                    fetched,
                    total,
                    false,
                    "Import failed: ${error.message ?: "network error"}",
                )
            }
        }

    suspend fun clearImported(
        context: Context,
        service: ScrobblerService,
    ) = withContext(Dispatchers.IO) {
        ListenStore(context).clear(service.id)
        val prefs = context.dataStore.data.first()
        val checkpoints = runCatching { JSONObject(prefs[ScrobblerCheckpointsKey].orEmpty()) }.getOrDefault(JSONObject())
        checkpoints.remove(service.id)
        context.dataStore.edit {
            it[ScrobblerCheckpointsKey] = checkpoints.toString()
            it[ScrobblerLastTotalKey] = 0L
            it[ScrobblerLastTotalServiceKey] = service.id
            it[ScrobblerLastResultKey] = ""
            it[ScrobblerLastSyncKey] = 0L
        }
    }

    private fun fetchTokenUser(root: String, token: String): String? {
        val body = getJson("${root.trimEnd('/')}/1/validate-token", token)
        return body.optString("user_name").takeIf { body.optBoolean("valid") && it.isNotBlank() }
    }

    private fun fetchTotal(config: ScrobblerConfig): Long? =
        runCatching {
            when (config.service) {
                ScrobblerService.LASTFM -> {
                    val user = config.lastFmUser.trim()
                    val apiKey = config.apiKey.trim().ifBlank { BuildConfig.LASTFM_API_KEY }
                    if (user.isEmpty() || apiKey.isEmpty()) return null
                    val body =
                        getJson(
                            "https://ws.audioscrobbler.com/2.0/?method=user.getInfo" +
                                "&user=${encode(user)}&api_key=${encode(apiKey)}&format=json",
                        )
                    body.optJSONObject("user")?.optString("playcount")?.toLongOrNull()
                }
                ScrobblerService.LISTENBRAINZ,
                ScrobblerService.SELF_HOSTED,
                -> {
                    val user =
                        if (config.service == ScrobblerService.LISTENBRAINZ) {
                            config.listenBrainzUser.trim()
                        } else {
                            config.lastFmUser.trim().ifBlank { config.listenBrainzUser.trim() }
                        }
                    val root =
                        if (config.service == ScrobblerService.LISTENBRAINZ) {
                            "https://api.listenbrainz.org"
                        } else {
                            config.baseUrl.trim().trimEnd('/')
                        }
                    if (user.isEmpty() || root.isBlank()) return null
                    val body = getJson("$root/1/user/${encode(user)}/listen-count", config.token.trim())
                    body.optJSONObject("payload")?.optLong("count")
                }
                ScrobblerService.NONE -> null
            }
        }.getOrNull()

    private suspend fun saveCheckpoint(
        context: Context,
        service: ScrobblerService,
        value: Long,
    ) {
        if (value <= 0L) return
        val prefs = context.dataStore.data.first()
        val checkpoints = runCatching { JSONObject(prefs[ScrobblerCheckpointsKey].orEmpty()) }.getOrDefault(JSONObject())
        checkpoints.put(service.id, value)
        context.dataStore.edit { it[ScrobblerCheckpointsKey] = checkpoints.toString() }
    }

    private fun getJson(url: String, token: String = ""): JSONObject {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("accept", "application/json")
                    setRequestProperty("user-agent", "RadioStar/1.0")
                    if (token.isNotEmpty()) setRequestProperty("authorization", "Token $token")
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

    private fun JSONObject.toLastFmListen(): ImportedListen? {
        if (optJSONObject("@attr")?.optString("nowplaying") == "true") return null
        val timestamp = optJSONObject("date")?.optString("uts")?.toLongOrNull() ?: return null
        val title = optString("name")
        val artist = optJSONObject("artist")?.optString("#text") ?: optString("artist")
        val album = optJSONObject("album")?.optString("#text")
        if (title.isBlank() || artist.isBlank()) return null
        return ImportedListen(
            service = ScrobblerService.LASTFM.id,
            title = title,
            artist = artist,
            album = album?.takeIf { it.isNotBlank() },
            listenedAt = timestamp,
        )
    }

    private fun JSONArray.toListenRecords(service: String): List<ImportedListen> =
        List(length()) { index ->
            val listen = optJSONObject(index) ?: return@List null
            val metadata = listen.optJSONObject("track_metadata") ?: return@List null
            val timestamp = listen.optLong("listened_at", 0L)
            val title = metadata.optString("track_name")
            val artist = metadata.optString("artist_name")
            if (timestamp <= 0L || title.isBlank() || artist.isBlank()) return@List null
            ImportedListen(
                service = service,
                title = title,
                artist = artist,
                album = metadata.optString("release_name").takeIf { it.isNotBlank() },
                listenedAt = timestamp,
            )
        }.filterNotNull()

    private fun Any?.asArray(): List<JSONObject> =
        when (this) {
            is JSONArray -> List(length()) { optJSONObject(it) }.filterNotNull()
            is JSONObject -> listOf(this)
            else -> emptyList()
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
