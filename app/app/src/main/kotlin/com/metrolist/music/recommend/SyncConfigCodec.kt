/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed wire format for DataStore preferences.
 *
 * Preferences are heterogeneous (Boolean/Int/Long/Float/String/Set<String>),
 * so the type travels with the value. Secrets are replaced by ciphertext
 * before they ever reach this codec's output.
 */
object SyncConfigCodec {
    private val excludedExact =
        setOf(
            "last_full_sync",
            "last_weekly_most_playlist_sync",
            "last_monthly_most_playlist_sync",
            "scrobblerLastSync",
            "scrobblerLastTotal",
            "scrobblerLastTotalService",
            "scrobblerLastResult",
            "scrobblerCheckpoints",
            "radiostarServer",
            "radiostarProfiles",
            "radiostarHistory",
            "radiostarResolvedListens",
            "playerVolume",
            "lastSeenVersion",
            "dismissedStandaloneUpdate",
            "simpMusicMigrationDone",
            "videoThumbnailMigrationDone",
            "discordInfoDismissed",
            "listenTogetherSessionToken",
            "listenTogetherRoomCode",
            "listenTogetherUserId",
            "listenTogetherIsHost",
            "listenTogetherSessionTimestamp",
        )

    private val excludedPrefixes =
        listOf(
            "alarm",
            "radiostarSync",
            "radiostarArtworkDraft",
        )

    private val secretFragments =
        listOf(
            "token",
            "secret",
            "password",
            "apikey",
            "session",
            "cookie",
            "credential",
            "passphrase",
        )

    fun isExcluded(name: String): Boolean =
        name in excludedExact || excludedPrefixes.any { name.startsWith(it) }

    fun isSecret(name: String): Boolean {
        val lower = name.lowercase()
        return secretFragments.any { lower.contains(it) }
    }

    fun encodeValue(value: Any?): JSONObject? =
        when (value) {
            is Boolean -> JSONObject().put("type", "bool").put("value", value)
            is Int -> JSONObject().put("type", "int").put("value", value)
            is Long -> JSONObject().put("type", "long").put("value", value)
            is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
            is String -> JSONObject().put("type", "string").put("value", value)
            is Set<*> -> {
                val array = JSONArray()
                value.filterIsInstance<String>().sorted().forEach(array::put)
                JSONObject().put("type", "stringSet").put("value", array)
            }
            else -> null
        }

    fun decodeValue(json: JSONObject): Any? =
        when (json.optString("type")) {
            "bool" -> json.optBoolean("value")
            "int" -> json.optInt("value")
            "long" -> json.optLong("value")
            "float" -> json.optDouble("value").toFloat()
            "string" -> json.optString("value")
            "stringSet" -> {
                val array = json.optJSONArray("value") ?: JSONArray()
                buildSet {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            else -> null
        }

    fun encodeSnapshot(values: Map<String, String>): String {
        val json = JSONObject()
        values.toSortedMap().forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    fun parseSnapshot(raw: String?): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        if (raw.isNullOrBlank()) return result
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        json.keys().forEach { key ->
            json.optString(key).takeIf { it.isNotEmpty() }?.let { result[key] = it }
        }
        return result
    }

    fun canonical(value: JSONObject): String = value.toString()

    /**
     * Snapshot values are compared for equality only. Secret values are hashed
     * so the snapshot itself never contains a plaintext token.
     */
    fun fingerprint(
        name: String,
        value: JSONObject,
    ): String {
        val canonical = value.toString()
        if (!isSecret(name)) return canonical
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun reactionKey(
        videoId: String?,
        title: String,
        artist: String,
    ): String =
        videoId?.takeIf { it.isNotBlank() }?.let { "yt:$it" }
            ?: "tx:${normalize(title)}|${normalize(artist)}"

    fun normalize(value: String): String =
        value
            .lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun primaryArtist(value: String): String =
        value.split(',', '&', ';').firstOrNull()?.trim().orEmpty().ifBlank { value.trim() }
}
