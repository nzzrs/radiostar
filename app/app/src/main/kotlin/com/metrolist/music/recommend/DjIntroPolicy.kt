/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Chooses how the opening link is produced.
 *
 * A styled TTS provider already knows how to say the local template, so the
 * intro does not have to wait for a chat completion. Everything here is pure
 * Kotlin and unit-tested; [com.metrolist.music.playback.DjDirector] only runs
 * the chosen path.
 */
object DjIntroPolicy {
    /** Chat budget for the intro; the local template wins after this. */
    const val INTRO_CHAT_TIMEOUT_MS = 3_000L

    /** How long the intro may wait for local context before speaking. */
    const val INTRO_CONTEXT_TIMEOUT_MS = 600L

    /** Prepended to the local template when the direct path is used. */
    private const val MAX_INTRO_CHARS = 320

    enum class Path {
        /** Local text straight to a styled TTS provider. */
        DIRECT_TTS,

        /** Chat model writes the link, then TTS speaks it. */
        CHAT,

        /** Local template only; no chat request is required. */
        LOCAL,
    }

    /**
     * Direct TTS needs a provider that accepts style instructions and an
     * explicit user setting; otherwise the chat path stays in charge.
     */
    fun decide(
        directEnabled: Boolean,
        aiEnabled: Boolean,
        tts: AiResolvedConfig?,
    ): Path =
        when {
            directEnabled && tts?.canSpeak == true && supportsStyledSpeech(tts) -> Path.DIRECT_TTS
            aiEnabled -> Path.CHAT
            else -> Path.LOCAL
        }

    /**
     * OpenAI only honours `instructions` on the newer TTS models, while Gemini
     * TTS takes the style as part of the read-aloud prompt.
     */
    fun supportsStyledSpeech(config: AiResolvedConfig): Boolean {
        if (isGemini(config)) return true
        val model = config.model.trim().lowercase(Locale.ROOT)
        return model.startsWith("gpt-4o-mini-tts") || model.startsWith("gpt-4o-tts")
    }

    fun isGemini(config: AiResolvedConfig): Boolean =
        config.provider == "Gemini" || config.baseUrl.contains("generativelanguage.googleapis.com")

    /** Voice direction for the provider; never spoken by the listener. */
    fun instructions(
        persona: DjPersona,
        language: String,
    ): String {
        val name = persona.displayName.removePrefix("DJ ")
        return if (language.lowercase(Locale.ROOT).startsWith("es")) {
            "Eres $name, locutor de radio. ${persona.tagline} " +
                "Habla con calidez, de forma natural y conversacional, con una sonrisa en la voz. " +
                "Sin prisa y sin tono de anuncio genérico."
        } else {
            "You are $name, a radio host. ${persona.tagline} " +
                "Speak warmly, naturally and conversationally, with a smile in your voice. " +
                "Unhurried, never like a generic announcer."
        }
    }

    /** Appends the local context sentence, when there is one. */
    fun composeText(
        base: String,
        contextSummary: String,
    ): String {
        if (base.isBlank()) return ""
        val extra = contextSummary.trim()
        if (extra.isEmpty()) return base
        return "$base $extra".take(MAX_INTRO_CHARS)
    }

    fun introPrompt(
        persona: DjPersona,
        firstTitle: String,
        firstArtist: String,
        contextSummary: String = "",
    ): String =
        buildString {
            append("You are ${persona.displayName}, a radio DJ with this personality: ")
            append(persona.tagline)
            append(". Open a listening session in at most 30 words. ")
            append("Mention the first track: $firstTitle by $firstArtist. ")
            append("Sound like yourself, not like a generic announcer. ")
            if (contextSummary.isNotBlank()) {
                append("You may use this true local context if it fits, without inventing anything: ")
                append(contextSummary.trim())
                append(". ")
            }
            append("Do not say 'let the beat play'. ")
            append("Use the language of the listener, no markdown or quotes.")
        }

    fun linkPrompt(
        persona: DjPersona,
        previous: String?,
        nextTitle: String,
        nextArtist: String,
        fact: String?,
        contextSummary: String = "",
    ): String =
        buildString {
            append("You are ${persona.displayName}, a radio DJ with this personality: ")
            append(persona.tagline)
            append(". Write one short spoken link, at most 25 words, ")
            append("in the language of the listener, no markdown or quotes. ")
            if (!previous.isNullOrBlank()) append("Previous track: $previous. ")
            append("Next: $nextTitle by $nextArtist. ")
            if (!fact.isNullOrBlank()) {
                append("You may use this true session detail, without inventing anything: $fact ")
                append("Keep the link natural, not like a list of statistics. ")
            }
            if (contextSummary.isNotBlank()) {
                append("You may use this true local context if it fits, without inventing anything: ")
                append(contextSummary.trim())
                append(". ")
            }
        }

    /**
     * Waits at most [timeoutMs] for the chat model and falls back to the local
     * template at once, so a slow provider never delays the first track.
     */
    suspend fun awaitText(
        timeoutMs: Long,
        fallback: String,
        generate: suspend () -> String?,
    ): String {
        if (fallback.isBlank()) return ""
        val generated =
            withTimeoutOrNull(timeoutMs) {
                try {
                    generate()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        return generated?.takeIf { it.isNotBlank() } ?: fallback
    }
}
