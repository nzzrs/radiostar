/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class SyncItem(
    val kind: String,
    val key: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val payload: JSONObject,
    val seq: Long = 0L,
    val deviceId: String? = null,
)

data class SyncPushResult(
    val accepted: List<SyncItem>,
    val conflicts: List<SyncItem>,
    val seq: Long,
)

data class SyncPullResult(
    val items: List<SyncItem>,
    val nextSince: Long,
    val hasMore: Boolean,
    val seq: Long,
)

class SyncClient(
    private val auth: ServerAuthStore,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
) {
    class SyncException(
        message: String,
        val unauthorized: Boolean = false,
        val network: Boolean = false,
    ) : Exception(message)

    suspend fun push(
        server: String,
        items: List<SyncItem>,
    ): SyncPushResult =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext SyncPushResult(emptyList(), emptyList(), 0L)
            val body =
                JSONObject().put(
                    "items",
                    JSONArray().apply {
                        items.forEach { item ->
                            put(
                                JSONObject()
                                    .put("kind", item.kind)
                                    .put("key", item.key)
                                    .put("updated_at_ms", item.updatedAtMs)
                                    .put("deleted", item.deleted)
                                    .put("payload", item.payload),
                            )
                        }
                    },
                )
            val json = post(server, "/sync/push", body)
            SyncPushResult(
                accepted = json.optJSONArray("accepted").toItems(),
                conflicts = json.optJSONArray("conflicts").toItems(),
                seq = json.optLong("seq", 0L),
            )
        }

    suspend fun pull(
        server: String,
        since: Long,
        limit: Int = 500,
    ): SyncPullResult =
        withContext(Dispatchers.IO) {
            val json = get(server, "/sync/pull?since=$since&limit=$limit")
            SyncPullResult(
                items = json.optJSONArray("items").toItems(),
                nextSince = json.optLong("next_since", since),
                hasMore = json.optBoolean("has_more", false),
                seq = json.optLong("seq", 0L),
            )
        }

    suspend fun status(server: String): JSONObject = withContext(Dispatchers.IO) { get(server, "/sync/status") }

    suspend fun wipe(server: String) = withContext(Dispatchers.IO) {
        execute(server, "DELETE", "/sync", null)
    }

    private suspend fun get(server: String, path: String): JSONObject = execute(server, "GET", path, null)

    private suspend fun post(server: String, path: String, body: JSONObject): JSONObject =
        execute(server, "POST", path, body)

    private suspend fun execute(
        server: String,
        method: String,
        path: String,
        body: JSONObject?,
    ): JSONObject {
        val normalized = server.trimEnd('/')
        var token = auth.accessToken(normalized)
            ?: throw SyncException("Not linked", unauthorized = true)
        var response = runRequest(normalized, path, method, body, token)
        if (response.first == 401) {
            auth.invalidateAccess()
            token =
                auth.accessToken(normalized)
                    ?: throw SyncException("Not linked", unauthorized = true)
            response = runRequest(normalized, path, method, body, token)
        }
        val (code, text) = response
        if (code !in 200..299) {
            val message =
                runCatching { JSONObject(text).optString("error") }
                    .getOrDefault("")
                    .ifBlank { "HTTP $code" }
            throw SyncException(message, unauthorized = code == 401)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun runRequest(
        server: String,
        path: String,
        method: String,
        body: JSONObject?,
        token: String,
    ): Pair<Int, String> {
        val builder =
            Request.Builder()
                .url("$server$path")
                .header("Authorization", "Bearer $token")
        val requestBody = body?.toString()?.toRequestBody(JSON)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.post(requestBody ?: "".toRequestBody(JSON))
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                return response.code to response.body.string()
            }
        } catch (error: IOException) {
            throw SyncException("network error", network = true)
        }
    }

    private fun JSONArray?.toItems(): List<SyncItem> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    SyncItem(
                        kind = item.optString("kind"),
                        key = item.optString("key"),
                        updatedAtMs = item.optLong("updated_at_ms", 0L),
                        deleted = item.optBoolean("deleted", false),
                        payload = item.optJSONObject("payload") ?: JSONObject(),
                        seq = item.optLong("seq", 0L),
                        deviceId = item.optString("device_id").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
