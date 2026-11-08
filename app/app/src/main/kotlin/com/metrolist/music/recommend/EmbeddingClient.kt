/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Client for the laptop text tower. Vectors are requested in small chunks so a
 * cold cache cannot push one request past Cloudflare's response timeout.
 */
object EmbeddingClient {
    data class Item(
        val artist: String,
        val title: String,
    )

    private const val CHUNK = 16
    private const val TAG = "RadioStarEmbed"

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

    suspend fun embed(
        server: String,
        items: List<Item>,
        auth: ServerAuthStore,
    ): List<FloatArray>? {
        if (items.isEmpty()) return emptyList()
        val vectors = ArrayList<FloatArray>(items.size)
        for (chunk in items.chunked(CHUNK)) {
            val part = embedChunk(server, chunk, auth) ?: return null
            if (part.size != chunk.size) {
                Timber.tag(TAG).w("expected %d vectors, got %d", chunk.size, part.size)
                return null
            }
            vectors.addAll(part)
        }
        return vectors
    }

    private suspend fun embedChunk(
        server: String,
        items: List<Item>,
        auth: ServerAuthStore,
    ): List<FloatArray>? {
        val payload =
            JSONObject().put(
                "items",
                JSONArray().apply {
                    items.forEach { item ->
                        put(
                            JSONObject()
                                .put("artist", item.artist)
                                .put("title", item.title),
                        )
                    }
                },
            )

        for (attempt in 0 until 2) {
            val token = auth.accessToken(server) ?: return null
            val request =
                Request.Builder()
                    .url("${server.trimEnd('/')}/embed")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        return parseVectors(body, items.size)
                    }
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    when {
                        response.code == 401 && attempt == 0 -> {
                            auth.invalidateAccess()
                            delay(50)
                        }
                        response.code == 429 && attempt == 0 -> {
                            delay(((retryAfter ?: 2L).coerceAtMost(10L)) * 1000L)
                        }
                        response.code >= 500 && attempt == 0 -> {
                            delay(400)
                        }
                        else -> {
                            Timber.tag(TAG).w("embed http %d body=%s", response.code, body.take(300))
                            return null
                        }
                    }
                }
            } catch (error: IOException) {
                Timber.tag(TAG).w(error, "embed request failed")
                if (attempt == 0) delay(400) else return null
            }
        }
        return null
    }

    private fun parseVectors(body: String, expected: Int): List<FloatArray>? {
        return runCatching {
            val array = JSONObject(body).getJSONArray("vectors")
            if (array.length() != expected) {
                Timber.tag(TAG).w("embed returned %d vectors for %d items", array.length(), expected)
                return null
            }
            List(array.length()) { index ->
                val values = array.getJSONArray(index)
                FloatArray(values.length()) { column -> values.getDouble(column).toFloat() }
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "invalid embed response")
            null
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
