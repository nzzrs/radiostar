/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjSettingsTest {
    @Test
    fun `default config is cherry with a broad but safe DJ set`() {
        val config = DjConfig()

        assertEquals(DjPersona.CHERRY, config.persona)
        assertEquals(DjPresence.SOMETIMES, config.known)
        assertEquals(DjPresence.NEVER, config.far)
        assertEquals(DjPresence.FREQUENT, config.likely)
    }

    @Test
    fun `personas have a fruit emoji and a non-empty personality`() {
        DjPersona.builtins.forEach { persona ->
            assertTrue(persona.emoji.isNotBlank())
            assertTrue(persona.displayName.startsWith("DJ "))
            assertTrue(persona.tagline.isNotBlank())
        }
    }

    @Test
    fun `persona voices stay inside the TTS ranges`() {
        DjPersona.builtins.forEach { persona ->
            assertTrue(persona.pitch in 0.5f..1.5f)
            assertTrue(persona.rate in 0.5f..1.5f)
        }
    }

    @Test
    fun `services have unique ids`() {
        val ids = ScrobblerService.values().map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals("lastfm", ScrobblerService.LASTFM.id)
        assertEquals("listenbrainz", ScrobblerService.LISTENBRAINZ.id)
        assertEquals("self_hosted", ScrobblerService.SELF_HOSTED.id)
    }
}
