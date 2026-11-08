/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_BASE_URL

/** Provider defaults and fallback model suggestions for the AI screens. */
object AiProviderCatalog {
    val baseUrls: Map<String, String> =
        linkedMapOf(
            "OpenRouter" to "https://openrouter.ai/api/v1/chat/completions",
            "OpenAI" to "https://api.openai.com/v1/chat/completions",
            "Perplexity" to "https://api.perplexity.ai/chat/completions",
            "Claude" to "https://api.anthropic.com/v1/messages",
            "Gemini" to RADIOSTAR_DEFAULT_DJ_BASE_URL,
            "XAi" to "https://api.x.ai/v1/chat/completions",
            "Mistral" to "https://api.mistral.ai/v1/chat/completions",
            "Inception" to "https://api.inceptionlabs.ai/v1/chat/completions",
            "Custom" to "",
        )

    val models: Map<String, List<String>> =
        mapOf(
            "OpenRouter" to
                listOf(
                    "inception/mercury-2.5-preview",
                    "meta/muse-spark-1.3",
                    "z-ai/glm-5.3-flash",
                    "qwen/qwen3.8-flash",
                    "deepseek/deepseek-v4-flash-latest",
                    "openai/gpt-mini-latest",
                    "openai/gpt-oss-120b",
                    "google/gemini-flash-latest",
                ),
            "OpenAI" to
                listOf(
                    "gpt-5.6-sol",
                    "gpt-5.6-terra",
                    "gpt-5.6-luna",
                    "gpt-5.5-2026-04-23",
                    "gpt-5.4-2026-03-05",
                ),
            "Claude" to
                listOf(
                    "claude-opus-5",
                    "claude-sonnet-5",
                    "claude-haiku-4-5-20251001",
                    "claude-fable-5-1",
                ),
            "Gemini" to
                listOf(
                    "gemini-flash-lite-latest",
                    "gemini-pro-latest",
                    "gemini-flash-latest",
                    "gemini-3.8-flash",
                ),
            "Perplexity" to listOf("sonar", "sonar-pro", "sonar-reasoning-pro"),
            "XAi" to listOf("grok-4.3", "grok-4.6", "grok-4.1-fast"),
            "Mistral" to
                listOf(
                    "mistral-large-latest",
                    "mistral-medium-latest",
                    "mistral-small-latest",
                    "mistral-tiny-latest",
                ),
            "Inception" to listOf("mercury-2"),
            "Custom" to emptyList(),
        )

    fun baseUrl(provider: String): String = baseUrls[provider].orEmpty()

    fun fallbackModels(provider: String): List<String> = models[provider].orEmpty()

    /** Chat URL to the matching OpenAI-compatible speech endpoint. */
    fun speechUrl(baseUrl: String): String {
        var root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) return ""
        if (root.endsWith("/audio/speech")) return root
        for (suffix in listOf("/chat/completions", "/completions", "/messages", "/responses")) {
            if (root.endsWith(suffix)) {
                root = root.removeSuffix(suffix)
                break
            }
        }
        return "$root/audio/speech"
    }

    /** TTS base URL for a provider; Gemini uses generateContent, not /audio/speech. */
    fun ttsBaseUrl(provider: String): String =
        when (provider) {
            "Gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            "OpenAI" -> "https://api.openai.com/v1/audio/speech"
            "Custom" -> ""
            else -> speechUrl(baseUrl(provider))
        }
}
