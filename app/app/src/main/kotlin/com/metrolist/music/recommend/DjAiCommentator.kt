/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Optional LLM link between tracks and session introductions.
 *
 * The DJ only speaks generated text when the user configured an
 * OpenAI-compatible endpoint; otherwise the caller falls back to the
 * track-specific template.
 */
object DjAiCommentator {
    suspend fun comment(
        context: Context,
        persona: DjPersona,
        previous: String?,
        nextTitle: String,
        nextArtist: String,
        fact: String? = null,
    ): String? {
        val prompt =
            buildString {
                append("You are ${persona.displayName}, a radio DJ with this personality: ")
                append(persona.promptStyle)
                append(". Write one short spoken link, at most 25 words, ")
                append("in the language of the listener, no markdown or quotes. ")
                if (!previous.isNullOrBlank()) append("Previous track: $previous. ")
                append("Next: $nextTitle by $nextArtist. ")
                if (!fact.isNullOrBlank()) {
                    append("You may use this true session detail, without inventing anything: $fact ")
                    append("Keep the link natural, not like a list of statistics.")
                }
            }
        return request(context, prompt, if (fact.isNullOrBlank()) "Say the link." else "Say the link with the session detail.")
    }

    suspend fun intro(
        context: Context,
        persona: DjPersona,
        firstTitle: String,
        firstArtist: String,
    ): String? {
        val prompt =
            buildString {
                append("You are ${persona.displayName}, a radio DJ with this personality: ")
                append(persona.promptStyle)
                append(". Open a listening session in at most 30 words. ")
                append("Mention the first track: $firstTitle by $firstArtist. ")
                append("Sound like yourself, not like a generic announcer. ")
                append("Do not say 'let the beat play'. ")
                append("Use the language of the listener, no markdown or quotes.")
            }
        return request(context, prompt, "Open the session.")
    }

    private suspend fun request(
        context: Context,
        prompt: String,
        userTurn: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val config = AiProviderResolver.resolve(context, AiFeature.DJ_VOICE)
            val key = config.apiKey.trim()
            if (key.isEmpty() || !config.isChatCapable) return@withContext null
            val base = config.baseUrl
            val model = config.model
            val provider = config.provider
            val payload =
                JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.8)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", prompt))
                            put(JSONObject().put("role", "user").put("content", userTurn))
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
            }.onFailure { Timber.tag("RadioStarDjAi").w(it, "request failed via $provider") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
}
