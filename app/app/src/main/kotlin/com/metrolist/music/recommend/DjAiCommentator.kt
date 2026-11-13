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
        contextSummary: String = "",
    ): String? {
        val prompt =
            DjIntroPolicy.linkPrompt(
                persona = persona,
                previous = previous,
                nextTitle = nextTitle,
                nextArtist = nextArtist,
                fact = fact,
                contextSummary = contextSummary,
            )
        return request(context, prompt, if (fact.isNullOrBlank()) "Say the link." else "Say the link with the session detail.")
    }

    suspend fun intro(
        context: Context,
        persona: DjPersona,
        firstTitle: String,
        firstArtist: String,
        contextSummary: String = "",
        timeoutMs: Long = DjIntroPolicy.INTRO_CHAT_TIMEOUT_MS,
    ): String? {
        val prompt =
            DjIntroPolicy.introPrompt(
                persona = persona,
                firstTitle = firstTitle,
                firstArtist = firstArtist,
                contextSummary = contextSummary,
            )
        return request(context, prompt, "Open the session.", timeoutMs)
    }

    private suspend fun request(
        context: Context,
        prompt: String,
        userTurn: String,
        timeoutMs: Long = 0L,
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
                    // A tight intro budget has to abort the socket itself; a
                    // coroutine timeout cannot interrupt a blocking read.
                    connectTimeout = if (timeoutMs > 0L) minOf(8_000L, timeoutMs).toInt() else 8_000
                    readTimeout = if (timeoutMs > 0L) minOf(20_000L, timeoutMs).toInt() else 20_000
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
