/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_BASE_URL
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_MODEL
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_PROVIDER
import com.metrolist.music.constants.RadioStarDjApiKeyKey
import com.metrolist.music.constants.RadioStarDjBaseUrlKey
import com.metrolist.music.constants.RadioStarDjModelKey
import com.metrolist.music.constants.RadioStarDjProviderKey
import com.metrolist.music.utils.dataStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Optional LLM link between tracks. The DJ only speaks the generated text if
 * the user configured an OpenAI-compatible endpoint; otherwise the caller
 * falls back to the template announcement.
 */
object DjAiCommentator {
    suspend fun comment(
        context: Context,
        persona: DjPersona,
        previous: String?,
        nextTitle: String,
        nextArtist: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val prefs = context.dataStore.data.first()
            val secureKey =
                prefs[RadioStarDjApiKeyKey]?.takeIf { it.isNotBlank() }?.let(SecureKeyStore::decrypt)
            val key = secureKey?.trim()?.takeIf { it.isNotEmpty() }
                ?: prefs[OpenRouterApiKey]?.trim().orEmpty()
            if (key.isEmpty()) return@withContext null
            val base =
                prefs[RadioStarDjBaseUrlKey]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: RADIOSTAR_DEFAULT_DJ_BASE_URL
            val model =
                prefs[RadioStarDjModelKey]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: RADIOSTAR_DEFAULT_DJ_MODEL
            val provider = prefs[RadioStarDjProviderKey] ?: RADIOSTAR_DEFAULT_DJ_PROVIDER
            val prompt =
                buildString {
                    append("You are ${persona.displayName}, a radio DJ with this personality: ")
                    append(persona.tagline)
                    append(". Write one short spoken link, at most 25 words, ")
                    append("in the language of the listener, no markdown or quotes. ")
                    if (!previous.isNullOrBlank()) append("Previous track: $previous. ")
                    append("Next: $nextTitle by $nextArtist.")
                }
            val payload =
                JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.8)
                    put(
                        "messages",
                        org.json.JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", prompt))
                            put(JSONObject().put("role", "user").put("content", "Say the link."))
                        },
                    )
                }
            runCatching {
                val connection = (URL(base).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("authorization", "Bearer $key")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            }.onFailure { Timber.tag("RadioStarDjAi").w(it, "comment failed via $provider") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
}
