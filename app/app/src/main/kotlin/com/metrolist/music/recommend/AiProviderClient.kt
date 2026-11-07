/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AiConnectionResult(
    val ok: Boolean,
    val message: String,
    val models: List<String> = emptyList(),
)

/**
 * Small OpenAI-compatible client used by the settings screens.
 *
 * It only authenticates, lists models and sends a one-token ping. The DJ and
 * translation paths keep their own generation requests.
 */
object AiProviderClient {
    suspend fun listModels(
        provider: String,
        baseUrl: String,
        apiKey: String,
    ): AiConnectionResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext AiConnectionResult(false, "API key missing")
            val url = modelsUrl(baseUrl)
            if (url.isBlank()) return@withContext AiConnectionResult(false, "Base URL missing")
            runCatching {
                val (code, body) = request("GET", url, apiKey)
                if (code !in 200..299) {
                    return@runCatching AiConnectionResult(false, "HTTP $code")
                }
                val models = parseModels(JSONObject(body))
                if (models.isEmpty()) {
                    AiConnectionResult(false, "No models returned")
                } else {
                    AiConnectionResult(true, "${models.size} models", models)
                }
            }.getOrElse { AiConnectionResult(false, it.message ?: "Connection failed") }
        }

    suspend fun testConnection(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
    ): AiConnectionResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext AiConnectionResult(false, "API key missing")
            if (model.isBlank()) return@withContext AiConnectionResult(false, "Model missing")
            val listing = listModels(provider, baseUrl, apiKey)
            if (listing.ok) return@withContext AiConnectionResult(true, "Connected")
            val url = baseUrl.trim()
            if (url.isBlank()) return@withContext AiConnectionResult(false, "Base URL missing")
            val payload =
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 4)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", "ping"),
                        ),
                    )
                }
            runCatching {
                val (code, body) = request("POST", url, apiKey, payload.toString())
                if (code in 200..299) {
                    AiConnectionResult(true, "Connected")
                } else {
                    val detail =
                        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                    AiConnectionResult(false, detail ?: "HTTP $code")
                }
            }.getOrElse { AiConnectionResult(false, it.message ?: "Connection failed") }
        }

    private fun modelsUrl(baseUrl: String): String {
        var root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) return ""
        for (suffix in listOf("/chat/completions", "/completions", "/messages")) {
            if (root.endsWith(suffix)) {
                root = root.removeSuffix(suffix)
                break
            }
        }
        return "$root/models"
    }

    private fun parseModels(body: JSONObject): List<String> {
        val array = body.optJSONArray("data") ?: body.optJSONArray("models") ?: return emptyList()
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id =
                item.optString("id")
                    .ifBlank { item.optString("name") }
                    .removePrefix("models/")
                    .trim()
            if (id.isNotEmpty()) result.add(id)
        }
        return result.sorted()
    }

    private fun request(
        method: String,
        url: String,
        apiKey: String,
        body: String? = null,
    ): Pair<Int, String> {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("accept", "application/json")
                setRequestProperty("authorization", "Bearer $apiKey")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                }
            }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }
}
