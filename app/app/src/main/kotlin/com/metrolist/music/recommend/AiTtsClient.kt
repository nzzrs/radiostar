/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.util.Base64
import com.metrolist.music.constants.AI_TTS_DEFAULT_GEMINI_MODEL
import com.metrolist.music.constants.AI_TTS_DEFAULT_MODEL
import com.metrolist.music.constants.AI_TTS_DEFAULT_VOICE
import com.metrolist.music.constants.AiTtsGeminiModelSuggestions
import com.metrolist.music.constants.AiTtsModelSuggestions
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Speech clients for the two provider families:
 * OpenAI-compatible `/audio/speech`, and Gemini `generateContent`.
 */
object AiTtsClient {
    val OPENAI_VOICES: List<String> =
        listOf(
            "alloy",
            "ash",
            "ballad",
            "coral",
            "echo",
            "fable",
            "nova",
            "onyx",
            "sage",
            "shimmer",
            "verse",
        )

    /** Gemini's 30 prebuilt studio voices. */
    val GEMINI_VOICES: List<String> =
        listOf(
            "Zephyr",
            "Puck",
            "Charon",
            "Kore",
            "Fenrir",
            "Leda",
            "Orus",
            "Aoede",
            "Callirrhoe",
            "Autonoe",
            "Enceladus",
            "Iapetus",
            "Umbriel",
            "Algieba",
            "Despina",
            "Erinome",
            "Algenib",
            "Rasalgethi",
            "Laomedeia",
            "Achernar",
            "Alnilam",
            "Schedar",
            "Gacrux",
            "Pulcherrima",
            "Achird",
            "Zubenelgenubi",
            "Vindemiatrix",
            "Sadachbia",
            "Sadaltager",
            "Sulafat",
        )

    fun isGemini(config: AiResolvedConfig): Boolean =
        config.provider == "Gemini" || config.baseUrl.contains("generativelanguage.googleapis.com")

    fun defaultModel(provider: String): String =
        if (provider == "Gemini") AI_TTS_DEFAULT_GEMINI_MODEL else AI_TTS_DEFAULT_MODEL

    fun modelSuggestions(provider: String): List<String> =
        when (provider) {
            "Gemini" -> AiTtsGeminiModelSuggestions
            "Custom" -> (AiTtsGeminiModelSuggestions + AiTtsModelSuggestions).distinct()
            else -> AiTtsModelSuggestions
        }

    fun defaultVoice(provider: String): String =
        if (provider == "Gemini") "Kore" else AI_TTS_DEFAULT_VOICE

    fun fallbackVoices(provider: String): List<String> =
        if (provider == "Gemini") GEMINI_VOICES else OPENAI_VOICES

    /** Keep an OpenAI voice out of a Gemini request (and vice versa). */
    fun normalizeVoice(provider: String, stored: String?): String {
        val voice = stored?.trim().orEmpty()
        if (voice.isEmpty()) return defaultVoice(provider)
        return if (provider == "Gemini") {
            GEMINI_VOICES.firstOrNull { it.equals(voice, ignoreCase = true) }
                ?: if (OPENAI_VOICES.any { it.equals(voice, ignoreCase = true) }) "Kore" else voice
        } else {
            if (GEMINI_VOICES.any { it.equals(voice, ignoreCase = true) }) AI_TTS_DEFAULT_VOICE else voice
        }
    }

    /**
     * [style] is voice direction, never spoken. OpenAI reads it from the
     * `instructions` field (gpt-4o-mini-tts and newer); Gemini TTS receives it
     * as part of the read-aloud prompt.
     */
    suspend fun synthesize(
        config: AiResolvedConfig,
        text: String,
        style: String? = null,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!config.canSpeak || text.isBlank()) return@withContext null
            if (isGemini(config)) {
                synthesizeGemini(config, text, style)
            } else {
                synthesizeOpenAi(config, text, style)
            }
        }

    /**
     * Pays the request setup before the first line needs it: resolves the
     * endpoint and warms DNS. It never synthesizes audio, so no clip is held
     * in memory and no tokens are spent.
     */
    suspend fun prewarm(config: AiResolvedConfig?) =
        withContext(Dispatchers.IO) {
            val target = requestTarget(config) ?: return@withContext
            val warm = warmedTargets.add(target)
            if (!warm) return@withContext
            runCatching {
                val host = URL(target).host
                if (host.isNotBlank()) InetAddress.getByName(host)
            }.onFailure { Timber.tag(TAG).d(it, "tts prewarm failed") }
        }

    /** Request URL for [config], or null when speech is not configured. */
    fun requestTarget(config: AiResolvedConfig?): String? {
        if (config == null || !config.canSpeak) return null
        return if (isGemini(config)) {
            geminiUrl(config)
        } else {
            AiProviderCatalog.speechUrl(config.baseUrl).takeIf { it.isNotBlank() }
        }
    }

    fun supportsStyleInstructions(config: AiResolvedConfig): Boolean =
        DjIntroPolicy.supportsStyledSpeech(config)

    /**
     * Lists voices. Gemini has a Voice Library endpoint and a fixed prebuilt
     * set. OpenAI has no public voice-list endpoint, so its known set is used.
     */
    suspend fun listVoices(config: AiResolvedConfig): List<String> =
        withContext(Dispatchers.IO) {
            if (isGemini(config)) {
                val url = "${geminiRoot(config.baseUrl)}/voices"
                val found =
                    runCatching { fetchVoices(url, config.apiKey, googleKey = true) }
                        .getOrElse {
                            Timber.tag(TAG).w(it, "Gemini voice listing failed")
                            emptyList()
                        }
                return@withContext found
                    .map { voice -> GEMINI_VOICES.firstOrNull { it.equals(voice, ignoreCase = true) } ?: voice }
                    .distinct()
            }
            val speech = AiProviderCatalog.speechUrl(config.baseUrl)
            if (speech.isBlank()) return@withContext emptyList()
            val root = speech.removeSuffix("/audio/speech")
            for (endpoint in listOf("$root/audio/voices", "$root/voices")) {
                val voices =
                    runCatching { fetchVoices(endpoint, config.apiKey, googleKey = false) }
                        .getOrElse {
                            Timber.tag(TAG).w(it, "voice listing failed for %s", endpoint)
                            emptyList()
                        }
                if (voices.isNotEmpty()) return@withContext voices
            }
            emptyList()
        }

    private fun synthesizeOpenAi(
        config: AiResolvedConfig,
        text: String,
        style: String?,
    ): ByteArray? {
        val url = AiProviderCatalog.speechUrl(config.baseUrl)
        if (url.isBlank()) return null
        val payload =
            JSONObject().apply {
                put("model", config.model)
                put("input", text)
                put("voice", config.ttsVoice)
                put("response_format", "mp3")
                if (!style.isNullOrBlank() && supportsStyleInstructions(config)) {
                    put("instructions", style)
                }
            }
        return runCatching {
            val connection = openPost(url, config.apiKey, bearer = true)
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val (code, body) = readResponse(connection)
            if (code !in 200..299) {
                Timber.tag(TAG).w("speech HTTP %d: %s", code, errorMessage(body.toString(Charsets.UTF_8)))
                return@runCatching null
            }
            body.takeIf { it.isNotEmpty() }
        }.getOrElse {
            Timber.tag(TAG).w(it, "speech request failed")
            null
        }
    }

    private fun synthesizeGemini(
        config: AiResolvedConfig,
        text: String,
        style: String?,
    ): ByteArray? {
        val url = geminiUrl(config) ?: return null
        val voice = normalizeVoice("Gemini", config.ttsVoice)
        // Gemini TTS is an LLM: without a read-aloud instruction it may answer
        // with text instead of audio. The style/persona rides in the prompt.
        val direction = style?.trim().orEmpty()
        val prompt =
            buildString {
                if (direction.isNotEmpty()) {
                    append(direction)
                    append("\n\n")
                }
                append("Read aloud the following text exactly, with a natural radio DJ voice. ")
                append("Do not add or omit words:\n\n")
                append(text)
            }
        val payload =
            JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                    ),
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", voice),
                                ),
                            ),
                        ),
                )
            }
        return runCatching {
            val connection = openPost(url, config.apiKey, bearer = false)
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val (code, body) = readResponse(connection)
            if (code !in 200..299) {
                Timber.tag(TAG).w("Gemini speech HTTP %d: %s", code, errorMessage(body.toString(Charsets.UTF_8)))
                return@runCatching null
            }
            val parts =
                JSONObject(body.toString(Charsets.UTF_8)).optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return@runCatching null
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                val encoded = inline.optString("data")
                if (encoded.isBlank()) continue
                val mime = inline.optString("mimeType").ifBlank { inline.optString("mime_type") }
                val audio = Base64.decode(encoded, Base64.DEFAULT)
                if (audio.isEmpty()) continue
                return@runCatching if (mime.contains("wav", ignoreCase = true) || audio.isWav()) {
                    audio
                } else {
                    pcmToWav(audio, sampleRate(mime))
                }
            }
            null
        }.getOrElse {
            Timber.tag(TAG).w(it, "Gemini speech request failed")
            null
        }
    }

    private fun openPost(url: String, apiKey: String, bearer: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "audio/*,application/json")
            if (bearer) {
                setRequestProperty("authorization", "Bearer $apiKey")
            } else {
                setRequestProperty("x-goog-api-key", apiKey)
            }
        }

    private fun readResponse(connection: HttpURLConnection): Pair<Int, ByteArray> =
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }

    private fun fetchVoices(url: String, apiKey: String, googleKey: Boolean): List<String> {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("accept", "application/json")
                if (apiKey.isNotBlank()) {
                    if (googleKey) {
                        setRequestProperty("x-goog-api-key", apiKey)
                    } else {
                        setRequestProperty("authorization", "Bearer $apiKey")
                    }
                }
            }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Timber.tag(TAG).w("voice listing HTTP %d for %s", code, url)
                return emptyList()
            }
            val body = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            return parseVoices(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVoices(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val array =
            runCatching { JSONArray(body) }.getOrNull()
                ?: runCatching { JSONObject(body) }.getOrNull()?.let {
                    it.optJSONArray("data") ?: it.optJSONArray("voices")
                }
                ?: return emptyList()
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val voice =
                when (val item = array.opt(index)) {
                    is String ->
                        item
                    is JSONObject ->
                        item.optString("id")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("display_name") }
                            .ifBlank { item.optString("voice_id") }
                            .ifBlank { item.optString("voice") }
                    else ->
                        ""
                }.trim().removePrefix("voices/")
            if (voice.isNotEmpty()) result.add(voice)
        }
        return result.sorted()
    }

    private fun geminiUrl(config: AiResolvedConfig): String? {
        val model = config.model.removePrefix("models/").trim()
        if (model.isBlank()) return null
        return "${geminiRoot(config.baseUrl)}/models/$model:generateContent"
    }

    private fun geminiRoot(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val beta = base.indexOf("/v1beta")
        val v1 = base.indexOf("/v1")
        return when {
            beta >= 0 -> base.substring(0, beta) + "/v1beta"
            v1 >= 0 -> base.substring(0, v1) + "/v1"
            else -> "https://generativelanguage.googleapis.com/v1beta"
        }
    }

    private fun sampleRate(mime: String): Int =
        Regex("rate=(\\d+)").find(mime)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24_000

    private fun ByteArray.isWav(): Boolean =
        size >= 12 && this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte()

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + pcm.size)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(pcm.size)
            }
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    private fun errorMessage(body: String): String =
        runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("").take(200)

    private val warmedTargets = ConcurrentHashMap.newKeySet<String>()

    private const val TAG = "RadioStarTts"
}
