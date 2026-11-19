/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjPromptTest {
    @Test
    fun `default prompt describes a real radio host`() {
        val prompt = DjPrompt.DEFAULT

        assertTrue(prompt.contains("just played", ignoreCase = true))
        assertTrue(prompt.contains("opinion", ignoreCase = true))
        assertTrue(prompt.contains("joke", ignoreCase = true))
        assertTrue(prompt.contains("weather", ignoreCase = true))
        assertTrue(prompt.contains("city", ignoreCase = true))
        assertTrue(prompt.contains("news", ignoreCase = true))
        assertTrue(prompt.contains("not in every link", ignoreCase = true))
        assertFalse(prompt.isBlank())
    }

    @Test
    fun `default prompt forbids reading context as a list`() {
        val prompt = DjPrompt.DEFAULT

        assertTrue(prompt.contains("as a list", ignoreCase = true))
        assertTrue(prompt.contains("never read", ignoreCase = true))
        assertTrue(prompt.contains("repeat", ignoreCase = true))
    }
}
