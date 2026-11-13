/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DjPersonaTest {
    private val nova =
        DjPersona(
            id = "custom-dj-nova",
            emoji = "🎧",
            displayName = "DJ Nova",
            tagline = "Late-night synth whispers.",
            pitch = 0.9f,
            rate = 1.1f,
        )

    @Test
    fun `builtins keep their ids emojis names and voices`() {
        assertEquals(
            listOf("cherry", "lemon", "berry", "banana", "strawberry", "coco"),
            DjPersona.builtins.map { it.id },
        )
        assertEquals(
            listOf("🍒", "🍋", "🫐", "🍌", "🍓", "🥥"),
            DjPersona.builtins.map { it.emoji },
        )
        assertEquals(
            listOf("DJ Cherry", "DJ Lemon", "DJ Berry", "DJ Banana", "DJ Strawberry", "DJ Coco"),
            DjPersona.builtins.map { it.displayName },
        )
        assertEquals(1.02f, DjPersona.CHERRY.pitch, 0.0001f)
        assertEquals(1.0f, DjPersona.CHERRY.rate, 0.0001f)
        assertTrue(DjPersona.builtins.all { it.builtin })
        assertFalse(nova.builtin)
    }

    @Test
    fun `custom personas survive a json round trip`() {
        val decoded = DjPersonaCodec.decode(DjPersonaCodec.encode(listOf(nova)))

        assertEquals(listOf(nova), decoded)
        assertFalse(decoded.single().builtin)
        assertTrue(DjPersonaCodec.decode(DjPersonaCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `broken or foreign json decodes to no personas`() {
        assertTrue(DjPersonaCodec.decode(null).isEmpty())
        assertTrue(DjPersonaCodec.decode("").isEmpty())
        assertTrue(DjPersonaCodec.decode("not json").isEmpty())
        assertTrue(DjPersonaCodec.decode("[]").isEmpty())
        assertTrue(DjPersonaCodec.decode("""{"version":1}""").isEmpty())
    }

    @Test
    fun `invalid or clashing entries are dropped on decode`() {
        val raw =
            """
            {"version":1,"personas":[
              {"id":"custom-blank","emoji":"🎧","name":"  ","tagline":"x","pitch":1.0,"rate":1.0},
              {"id":"custom-loud","emoji":"🎧","name":"Loud","tagline":"x","pitch":2.0,"rate":1.0},
              {"id":"cherry","emoji":"🎧","name":"Impostor","tagline":"x","pitch":1.0,"rate":1.0},
              {"id":"custom-dj-nova","emoji":"🎧","name":"DJ Nova","tagline":"x","pitch":1.0,"rate":1.0}
            ]}
            """.trimIndent()

        val decoded = DjPersonaCodec.decode(raw)

        assertEquals(listOf("custom-dj-nova"), decoded.map { it.id })
    }

    @Test
    fun `validation requires a name a short emoji and safe voice ranges`() {
        assertNull(DjPersonaValidation.problem(nova))
        assertNull(DjPersonaValidation.problem(nova.copy(emoji = "🎧🎧")))

        assertEquals(
            DjPersonaIssue.NAME,
            DjPersonaValidation.problem(nova.copy(displayName = "   ")),
        )
        assertEquals(
            DjPersonaIssue.EMOJI,
            DjPersonaValidation.problem(nova.copy(emoji = "")),
        )
        assertEquals(
            DjPersonaIssue.EMOJI,
            DjPersonaValidation.problem(nova.copy(emoji = "🎧🎧🎧")),
        )
        assertEquals(
            DjPersonaIssue.PITCH,
            DjPersonaValidation.problem(nova.copy(pitch = 0.49f)),
        )
        assertEquals(
            DjPersonaIssue.PITCH,
            DjPersonaValidation.problem(nova.copy(pitch = 1.51f)),
        )
        assertEquals(
            DjPersonaIssue.RATE,
            DjPersonaValidation.problem(nova.copy(rate = 0.4f)),
        )
        assertEquals(
            DjPersonaIssue.RATE,
            DjPersonaValidation.problem(nova.copy(rate = 1.6f)),
        )
        assertNull(DjPersonaValidation.problem(nova.copy(pitch = 0.5f, rate = 1.5f)))
    }

    @Test
    fun `legacy enum names migrate to persona ids`() {
        val lemon = DjPersonaLibrary.from(rawCustom = null, rawSelected = "LEMON")
        val coco = DjPersonaLibrary.from(rawCustom = null, rawSelected = "COCO")

        assertEquals("lemon", lemon.selectedId)
        assertEquals(DjPersona.LEMON, lemon.selected)
        assertEquals("coco", coco.selectedId)
        assertEquals(DjPersona.COCO, coco.selected)
        assertEquals("cherry", DjPersonaLibrary.from(null, "CHERRY").selectedId)
    }

    @Test
    fun `unknown or missing selection falls back to cherry`() {
        assertEquals(DjPersona.CHERRY, DjPersonaLibrary.from(null, null).selected)
        assertEquals("cherry", DjPersonaLibrary.from(null, null).selectedId)
        assertEquals(DjPersona.CHERRY, DjPersonaLibrary.from(null, "GHOST").selected)
        assertEquals(DjPersona.CHERRY, DjPersonaLibrary.from("not json", "nope").selected)
    }

    @Test
    fun `custom selection resolves from stored json`() {
        val library =
            DjPersonaLibrary.from(
                rawCustom = DjPersonaCodec.encode(listOf(nova)),
                rawSelected = "custom-dj-nova",
            )

        assertEquals(nova, library.selected)
        assertTrue(library.all.contains(nova))
        assertEquals(7, library.all.size)
    }

    @Test
    fun `upsert replaces an edited persona and delete falls back to cherry`() {
        val library = DjPersonaLibrary.from(DjPersonaCodec.encode(listOf(nova)), "custom-dj-nova")
        val edited = nova.copy(tagline = "New style")

        assertEquals(listOf(edited), library.upsert(edited).custom)
        assertEquals(1, library.upsert(edited).custom.size)

        val afterDelete = library.remove(nova.id)
        assertTrue(afterDelete.custom.isEmpty())
        assertEquals("cherry", afterDelete.selectedId)
    }

    @Test
    fun `new custom ids are unique slugs`() {
        assertEquals("custom-dj-nova", DjPersona.newCustomId("DJ Nova", emptySet()))
        assertEquals(
            "custom-dj-nova-2",
            DjPersona.newCustomId("DJ Nova", setOf("custom-dj-nova")),
        )
        assertEquals("custom-dj-2", DjPersona.newCustomId("  ", setOf("custom-dj")))
    }
}
