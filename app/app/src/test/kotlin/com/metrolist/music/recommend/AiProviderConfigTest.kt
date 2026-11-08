/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.AiTranslationApiKeyKey
import com.metrolist.music.constants.AiTranslationModelKey
import com.metrolist.music.constants.AiTranslationProviderKey
import com.metrolist.music.constants.AiTranslationUseDefaultKey
import com.metrolist.music.constants.AI_TTS_ENGINE_PROVIDER
import com.metrolist.music.constants.AiTtsEngineKey
import com.metrolist.music.constants.AiTtsUseDefaultKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterModelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderConfigTest {
    @Test
    fun `speech url derives from chat url`() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            AiProviderCatalog.speechUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            AiProviderCatalog.speechUrl("https://api.openai.com/v1/audio/speech"),
        )
    }

    @Test
    fun `tts with the default provider keeps the speech model`() {
        val prefs =
            mutablePreferencesOf(
                AiProviderKey to "OpenAI",
                OpenRouterBaseUrlKey to "https://api.openai.com/v1/chat/completions",
                OpenRouterModelKey to "gpt-5.6-sol",
                OpenRouterApiKey to "global-key",
            )

        val config = AiProviderResolver.fromPreferences(prefs, AiFeature.TTS)

        assertEquals("gpt-4o-mini-tts", config.model)
        assertTrue(config.useDefault)
    }

    @Test
    fun `gemini tts uses a gemini model and voice`() {
        val prefs =
            mutablePreferencesOf(
                AiProviderKey to "Gemini",
                OpenRouterBaseUrlKey to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                OpenRouterModelKey to "gemini-flash-lite-latest",
                OpenRouterApiKey to "global-key",
                AiTtsEngineKey to AI_TTS_ENGINE_PROVIDER,
                AiTtsUseDefaultKey to true,
            )

        val config = AiProviderResolver.fromPreferences(prefs, AiFeature.TTS)

        assertEquals("gemini-2.5-flash-preview-tts", config.model)
        assertEquals("Kore", config.ttsVoice)
        assertTrue(config.canSpeak)
    }

    @Test
    fun `translation uses the global provider and key by default`() {
        val prefs =
            mutablePreferencesOf(
                AiProviderKey to "Gemini",
                OpenRouterBaseUrlKey to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                OpenRouterModelKey to "gemini-flash-lite-latest",
                OpenRouterApiKey to "global-key",
                AiTranslationModelKey to "gemini-pro-latest",
            )

        val config = AiProviderResolver.fromPreferences(prefs, AiFeature.TRANSLATION)

        assertTrue(config.useDefault)
        assertEquals("Gemini", config.provider)
        assertEquals("global-key", config.apiKey)
        assertEquals("gemini-pro-latest", config.model)
    }

    @Test
    fun `translation custom provider and key override the default`() {
        val prefs =
            mutablePreferencesOf(
                AiProviderKey to "OpenRouter",
                OpenRouterApiKey to "global-key",
                AiTranslationUseDefaultKey to false,
                AiTranslationProviderKey to "DeepL",
                AiTranslationApiKeyKey to "custom-key",
                DeeplApiKey to "deepl-key",
                AiTranslationModelKey to "",
            )

        val config = AiProviderResolver.fromPreferences(prefs, AiFeature.TRANSLATION)

        assertEquals("DeepL", config.provider)
        assertEquals("deepl-key", config.apiKey)
    }
}
