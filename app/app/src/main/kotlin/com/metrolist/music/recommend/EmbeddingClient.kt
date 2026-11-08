/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Client for the laptop text tower. Vectors are requested in small chunks so a
 * cold cache cannot push one request past the read timeout.
 */
object EmbeddingClient {
    data class Item(
        val artist: String,
        val title: String,
    )

    private const val CHUNK = 48
    private const val TAG = "RadioStarEmbed"

    fun embed(
        server: String,
        items: List<Item>,
    ): List<FloatArray>? {
        if (items.isEmpty()) return emptyList()
        val vectors = ArrayList<FloatArray>(items.size)
        for (chunk in items.chunked(CHUNK)) {
            val part = embedChunk(server, chunk) ?: return null
            if (part.size != chunk.size) {
                Timber.tag(TAG).w("expected %d vectors, got %d", chunk.size, part.size)
                return null
            }
            vectors.addAll(part)
        }
        return vectors
    }

    private fun embedChunk(
        server: String,
        items: List<Item>,
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
        val connection =
            (URL("${server.trimEnd('/')}/embed").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 180_000
                setRequestProperty("content-type", "application/json")
            }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Timber.tag(TAG).w("embed http %d body=%s", code, error.take(300))
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONObject(body).getJSONArray("vectors")
            if (array.length() != items.size) {
                Timber.tag(TAG).w("embed returned %d vectors for %d items", array.length(), items.size)
                return null
            }
            List(array.length()) { index ->
                val values = array.getJSONArray(index)
                FloatArray(values.length()) { column -> values.getDouble(column).toFloat() }
            }
        } catch (error: Throwable) {
            Timber.tag(TAG).e(error, "embed request failed")
            null
        } finally {
            connection.disconnect()
        }
    }
}
