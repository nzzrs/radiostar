/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import com.metrolist.music.constants.AdvancedTelemetryEnabledKey
import com.metrolist.music.utils.dataStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class TelemetryEventType(
    val wireName: String,
) {
    PLAY("play"),
    PAUSE("pause"),
    SKIP("skip"),
    SEEK("seek"),
    COMPLETE("complete"),
}

/**
 * Local-only listening telemetry. Nothing leaves the device: this is the event
 * log the recommendation engine can use to learn from skips and completion.
 *
 * Every write is gated by the opt-in preference, so a fresh install records
 * nothing until the user enables it in Settings -> RadioStar.
 */
object TelemetryStore {
    private const val FILE_NAME = "radiostar_telemetry.jsonl"

    data class Event(
        val type: TelemetryEventType,
        val songId: String?,
        val title: String?,
        val artist: String?,
        val playlist: String?,
        val seed: String?,
        val positionMs: Long?,
        val durationMs: Long?,
        val fromMs: Long? = null,
        val toMs: Long? = null,
        val completed: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    suspend fun record(
        context: Context,
        event: Event,
    ) {
        withContext(Dispatchers.IO) {
            val enabled = context.dataStore.data.first()[AdvancedTelemetryEnabledKey] ?: false
            if (!enabled) return@withContext
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                file.parentFile?.mkdirs()
                file.appendText(event.toJson().toString() + "\n", Charsets.UTF_8)
            }
        }
    }

    fun count(context: Context): Int {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return 0
        return runCatching {
            file.useLines { lines -> lines.count { it.isNotBlank() } }
        }.getOrDefault(0)
    }

    /**
     * Reads the local event log. Malformed or truncated lines are skipped so a
     * crash during an append never makes the whole history unusable.
     */
    fun load(context: Context): List<Event> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val result = ArrayList<Event>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching {
                    val json = JSONObject(line)
                    val type =
                        TelemetryEventType.values().firstOrNull { it.wireName == json.optString("event") }
                            ?: return@runCatching
                    result.add(
                        Event(
                            type = type,
                            songId = json.optString("songId").takeIf { it.isNotBlank() },
                            title = json.optString("title").takeIf { it.isNotBlank() },
                            artist = json.optString("artist").takeIf { it.isNotBlank() },
                            playlist = json.optString("playlist").takeIf { it.isNotBlank() },
                            seed = json.optString("seed").takeIf { it.isNotBlank() },
                            positionMs = json.optLong("positionMs").takeIf { json.has("positionMs") },
                            durationMs = json.optLong("durationMs").takeIf { json.has("durationMs") },
                            fromMs = json.optLong("fromMs").takeIf { json.has("fromMs") },
                            toMs = json.optLong("toMs").takeIf { json.has("toMs") },
                            completed = json.optBoolean("completed", false),
                            at = json.optLong("at", 0L),
                        ),
                    )
                }
            }
        }
        return result
    }

    fun clear(context: Context): Boolean =
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete() else true
        }.getOrDefault(false)

    private fun Event.toJson(): JSONObject =
        JSONObject().apply {
            put("event", type.wireName)
            put("at", at)
            put("session", TelemetrySession.id)
            songId?.let { put("songId", it) }
            title?.let { put("title", it) }
            artist?.let { put("artist", it) }
            playlist?.let { put("playlist", it) }
            seed?.let { put("seed", it) }
            positionMs?.let { put("positionMs", it) }
            durationMs?.let { put("durationMs", it) }
            fromMs?.let { put("fromMs", it) }
            toMs?.let { put("toMs", it) }
            put("completed", completed)
        }
}

object TelemetrySession {
    val id: String = UUID.randomUUID().toString()
}
