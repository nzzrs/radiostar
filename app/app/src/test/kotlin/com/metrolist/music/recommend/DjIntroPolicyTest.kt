/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjIntroPolicyTest {
    private fun tts(
        provider: String = "OpenAI",
        baseUrl: String = "https://api.openai.com/v1/audio/speech",
        model: String = "gpt-4o-mini-tts",
        engine: AiTtsEngine = AiTtsEngine.PROVIDER,
        key: String = "test-key",
    ): AiResolvedConfig =
        AiResolvedConfig(
            feature = AiFeature.TTS,
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            apiKey = key,
            useDefault = false,
            ttsEngine = engine,
            ttsVoice = "alloy",
        )

    @Test
    fun `direct path on a styled provider with the setting on`() {
        assertEquals(
            DjIntroPolicy.Path.DIRECT_TTS,
            DjIntroPolicy.decide(directEnabled = true, aiEnabled = false, tts = tts()),
        )
    }

    @Test
    fun `setting off keeps the chat path`() {
        assertEquals(
            DjIntroPolicy.Path.CHAT,
            DjIntroPolicy.decide(directEnabled = false, aiEnabled = true, tts = tts()),
        )
    }

    @Test
    fun `gemini tts supports style instructions`() {
        val config =
            tts(
                provider = "Gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                model = "gemini-2.5-flash-preview-tts",
            )
        assertTrue(DjIntroPolicy.supportsStyledSpeech(config))
        assertEquals(
            DjIntroPolicy.Path.DIRECT_TTS,
            DjIntroPolicy.decide(directEnabled = true, aiEnabled = true, tts = config),
        )
    }

    @Test
    fun `older openai voice models do not take instructions`() {
        val config = tts(model = "tts-1")
        assertFalse(DjIntroPolicy.supportsStyledSpeech(config))
        assertEquals(
            DjIntroPolicy.Path.CHAT,
            DjIntroPolicy.decide(directEnabled = true, aiEnabled = true, tts = config),
        )
    }

    @Test
    fun `phone engine never uses the direct path`() {
        val config = tts(engine = AiTtsEngine.PHONE)
        assertFalse(config.canSpeak)
        assertEquals(
            DjIntroPolicy.Path.CHAT,
            DjIntroPolicy.decide(directEnabled = true, aiEnabled = true, tts = config),
        )
        assertEquals(
            DjIntroPolicy.Path.LOCAL,
            DjIntroPolicy.decide(directEnabled = true, aiEnabled = false, tts = config),
        )
    }

    @Test
    fun `no tts config and no chat stays local`() {
        assertEquals(
            DjIntroPolicy.Path.LOCAL,
            DjIntroPolicy.decide(directEnabled = true, aiEnabled = false, tts = null),
        )
    }

    @Test
    fun `intro prompt carries persona, first track and local context`() {
        val prompt =
            DjIntroPolicy.introPrompt(
                persona = DjPersona.CHERRY,
                firstTitle = "ANSIEDAD",
                firstArtist = "Fuerza Regida",
                contextSummary = "Tuesday 21:30 · Lisbon, 19°C overcast",
            )
        assertTrue(prompt.contains("Cherry"))
        assertTrue(prompt.contains(DjPersona.CHERRY.tagline))
        assertTrue(prompt.contains("ANSIEDAD"))
        assertTrue(prompt.contains("Fuerza Regida"))
        assertTrue(prompt.contains("Lisbon"))
        assertTrue(prompt.contains("local context"))
    }

    @Test
    fun `intro prompt without context has no context sentence`() {
        val prompt =
            DjIntroPolicy.introPrompt(
                persona = DjPersona.LEMON,
                firstTitle = "One",
                firstArtist = "Artist A",
            )
        assertFalse(prompt.contains("local context"))
    }

    @Test
    fun `link prompt carries local context`() {
        val prompt =
            DjIntroPolicy.linkPrompt(
                persona = DjPersona.BERRY,
                previous = "One by Artist A",
                nextTitle = "Two",
                nextArtist = "Artist B",
                fact = null,
                contextSummary = "Headlines: first headline",
            )
        assertTrue(prompt.contains("Headlines: first headline"))
        assertTrue(prompt.contains("Two by Artist B"))
    }

    @Test
    fun `style instructions name the persona and never quote it`() {
        val english = DjIntroPolicy.instructions(DjPersona.CHERRY, "en-US")
        assertTrue(english.contains("Cherry"))
        assertTrue(english.contains(DjPersona.CHERRY.tagline))
        val spanish = DjIntroPolicy.instructions(DjPersona.CHERRY, "es-MX")
        assertTrue(spanish.contains("locutor"))
        assertFalse(spanish.contains("let the beat", ignoreCase = true))
    }

    @Test
    fun `direct text appends the local context`() {
        val base = DjScript.intro(DjPersona.CHERRY, "ANSIEDAD", "Fuerza Regida", "en")
        val text = DjIntroPolicy.composeText(base, "Tuesday 21:30 · Lisbon")
        assertTrue(text.startsWith(base))
        assertTrue(text.contains("Lisbon"))
        assertEquals(base, DjIntroPolicy.composeText(base, "   "))
    }

    @Test
    fun `a slow chat model falls back to the local template`() =
        runBlocking {
            val local = DjScript.intro(DjPersona.CHERRY, "ANSIEDAD", "Fuerza Regida", "en")
            val text =
                DjIntroPolicy.awaitText(timeoutMs = 20L, fallback = local) {
                    delay(2_000L)
                    "Late generated line"
                }
            assertEquals(local, text)
        }

    @Test
    fun `a fast chat model keeps its line`() =
        runBlocking {
            val text =
                DjIntroPolicy.awaitText(timeoutMs = 1_000L, fallback = "local") {
                    "generated"
                }
            assertEquals("generated", text)
        }

    @Test
    fun `blank generated text falls back to the local template`() =
        runBlocking {
            val text =
                DjIntroPolicy.awaitText(timeoutMs = 1_000L, fallback = "local") {
                    "   "
                }
            assertEquals("local", text)
        }

    @Test
    fun `local template needs no provider or network`() {
        val line = DjScript.intro(DjPersona.CHERRY, "ANSIEDAD", "Fuerza Regida", "en")
        assertEquals(DjIntroPolicy.Path.LOCAL, DjIntroPolicy.decide(true, false, null))
        assertEquals(line, DjIntroPolicy.composeText(line, ""))
        assertNull(AiTtsClient.requestTarget(null))
        assertNull(AiTtsClient.requestTarget(tts(engine = AiTtsEngine.PHONE)))
    }

    @Test
    fun `prewarm target matches the synthesize endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            AiTtsClient.requestTarget(tts()),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent",
            AiTtsClient.requestTarget(
                tts(
                    provider = "Gemini",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                    model = "gemini-2.5-flash-preview-tts",
                ),
            ),
        )
    }

    @Test
    fun `intro prompt uses the general prompt as the system instruction`() {
        val custom = "CUSTOM HOST INSTRUCTIONS"
        val prompt =
            DjIntroPolicy.introPrompt(
                persona = DjPersona.CHERRY,
                firstTitle = "ANSIEDAD",
                firstArtist = "Fuerza Regida",
                generalPrompt = custom,
            )

        assertTrue(prompt.startsWith(custom))
        assertTrue(prompt.contains(DjPersona.CHERRY.tagline))
        assertTrue(prompt.contains("ANSIEDAD"))
    }

    @Test
    fun `link prompt uses the general prompt and the default when omitted`() {
        val byDefault =
            DjIntroPolicy.linkPrompt(
                persona = DjPersona.CHERRY,
                previous = "One by Artist A",
                nextTitle = "Two",
                nextArtist = "Artist B",
                fact = null,
            )
        assertTrue(byDefault.startsWith(DjPrompt.DEFAULT))

        val custom =
            DjIntroPolicy.linkPrompt(
                persona = DjPersona.BERRY,
                previous = "One by Artist A",
                nextTitle = "Two",
                nextArtist = "Artist B",
                fact = null,
                generalPrompt = "CUSTOM LINK INSTRUCTIONS",
            )
        assertTrue(custom.startsWith("CUSTOM LINK INSTRUCTIONS"))
        assertTrue(custom.contains(DjPersona.BERRY.tagline))
    }

    @Test
    fun `prompts include earlier lines and the no repetition rule`() {
        val earlier = listOf("That bassline was huge", "The rain fits the synths")
        val intro =
            DjIntroPolicy.introPrompt(
                persona = DjPersona.LEMON,
                firstTitle = "One",
                firstArtist = "Artist A",
                previousLinks = earlier,
            )
        val link =
            DjIntroPolicy.linkPrompt(
                persona = DjPersona.LEMON,
                previous = "One by Artist A",
                nextTitle = "Two",
                nextArtist = "Artist B",
                fact = null,
                previousLinks = earlier,
            )

        listOf(intro, link).forEach { prompt ->
            assertTrue(prompt.contains("Earlier in this session you said:"))
            assertTrue(prompt.contains("That bassline was huge"))
            assertTrue(prompt.contains("The rain fits the synths"))
            assertTrue(prompt.contains("Do not repeat those jokes, opinions or phrasing"))
        }
    }

    @Test
    fun `prompts without earlier lines leave the repetition block out`() {
        val intro = DjIntroPolicy.introPrompt(DjPersona.LEMON, "One", "Artist A")
        val link =
            DjIntroPolicy.linkPrompt(
                persona = DjPersona.LEMON,
                previous = null,
                nextTitle = "Two",
                nextArtist = "Artist B",
                fact = null,
            )

        assertFalse(intro.contains("Earlier in this session"))
        assertFalse(link.contains("Earlier in this session"))
    }

    @Test
    fun `link prompt asks for a reaction and forbids reading context as a list`() {
        val prompt =
            DjIntroPolicy.linkPrompt(
                persona = DjPersona.CHERRY,
                previous = "One by Artist A",
                nextTitle = "Two",
                nextArtist = "Artist B",
                fact = null,
                contextSummary = "Tuesday 21:30 · Lisbon, 19°C overcast",
            )

        assertTrue(prompt.contains("opinion", ignoreCase = true))
        assertTrue(prompt.contains("joke", ignoreCase = true))
        assertTrue(prompt.contains("weather", ignoreCase = true))
        assertTrue(prompt.contains("read the time, weather or news as a list"))
    }

    @Test
    fun `intro prompt invites a reaction without listing local details`() {
        val prompt =
            DjIntroPolicy.introPrompt(
                persona = DjPersona.CHERRY,
                firstTitle = "ANSIEDAD",
                firstArtist = "Fuerza Regida",
            )

        assertTrue(prompt.contains("opinion", ignoreCase = true))
        assertTrue(prompt.contains("joke", ignoreCase = true))
        assertTrue(prompt.contains("read those details as a list"))
        assertTrue(prompt.contains("30 words"))
    }
}
