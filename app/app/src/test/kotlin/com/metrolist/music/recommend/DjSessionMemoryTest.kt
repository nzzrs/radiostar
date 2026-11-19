/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjSessionMemoryTest {
    @Test
    fun `keeps only the last eight lines`() {
        val memory = DjSessionMemory()
        (1..10).forEach { memory.add("line $it") }

        assertEquals(8, memory.all().size)
        assertEquals((3..10).map { "line $it" }, memory.all())
    }

    @Test
    fun `clear removes every stored line`() {
        val memory = DjSessionMemory()
        memory.add("first")
        memory.add("second")

        memory.clear()

        assertTrue(memory.all().isEmpty())
        assertEquals("", memory.render())
    }

    @Test
    fun `blank lines are ignored`() {
        val memory = DjSessionMemory()
        memory.add("  ")
        memory.add("")
        memory.add("one real line")

        assertEquals(listOf("one real line"), memory.all())
    }

    @Test
    fun `multi-line spoken text is folded into one line`() {
        val memory = DjSessionMemory()
        memory.add("  That\nbassline   was huge.  ")

        assertEquals(listOf("That bassline was huge."), memory.all())
    }

    @Test
    fun `renders recent lines compactly for a prompt`() {
        val memory = DjSessionMemory()
        memory.add("First joke")
        memory.add("Second joke")

        val rendered = memory.render()

        assertTrue(rendered.contains("First joke"))
        assertTrue(rendered.contains("Second joke"))
        assertFalse(rendered.contains("\n"))
    }

    @Test
    fun `custom capacity drops the oldest lines first`() {
        val memory = DjSessionMemory(capacity = 2)
        memory.add("a")
        memory.add("b")
        memory.add("c")

        assertEquals(listOf("b", "c"), memory.all())
    }
}
