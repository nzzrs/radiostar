/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    private val halfLife = 600_000.0

    @Test
    fun `state is empty before the first update`() {
        val state = SessionState(halfLife)
        assertFalse(state.isPrimed)
        assertNull(state.current())
    }

    @Test
    fun `first update adopts the embedding`() {
        val state = SessionState(halfLife)
        val vector = state.update(floatArrayOf(1f, 0f), atMs = 0L)
        assertEquals(1.0, vector[0], 1e-9)
        assertEquals(0.0, vector[1], 1e-9)
        assertTrue(state.isPrimed)
    }

    @Test
    fun `one half-life gives equal weight to the new track`() {
        val state = SessionState(halfLife)
        state.update(floatArrayOf(1f, 0f), atMs = 0L)
        val vector = state.update(floatArrayOf(0f, 1f), atMs = halfLife.toLong())

        val expected = 1.0 / sqrt(2.0)
        assertEquals(expected, vector[0], 1e-6)
        assertEquals(expected, vector[1], 1e-6)
    }

    @Test
    fun `a long gap replaces the state`() {
        val state = SessionState(halfLife)
        state.update(floatArrayOf(1f, 0f), atMs = 0L)
        val vector = state.update(floatArrayOf(0f, 1f), atMs = halfLife.toLong() * 20)

        assertEquals(0.0, vector[0], 1e-6)
        assertEquals(1.0, vector[1], 1e-6)
    }

    @Test
    fun `a skip repels the state`() {
        val state = SessionState(halfLife)
        state.update(floatArrayOf(1f, 0f), atMs = 0L)
        val vector = state.update(floatArrayOf(0f, 1f), atMs = halfLife.toLong(), positive = false)

        val expected = 1.0 / sqrt(2.0)
        assertEquals(expected, vector[0], 1e-6)
        assertEquals(-expected, vector[1], 1e-6)
    }

    @Test
    fun `the state is always unit length`() {
        val state = SessionState(halfLife)
        state.update(floatArrayOf(3f, 4f), atMs = 0L)
        state.update(floatArrayOf(-2f, 7f), atMs = 30_000L)
        val vector = requireNotNull(state.current())

        var energy = 0.0
        for (value in vector) energy += value * value
        assertEquals(1.0, sqrt(energy), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero embedding is rejected`() {
        SessionState(halfLife).update(floatArrayOf(0f, 0f), atMs = 0L)
    }

    @Test
    fun `reset clears the state`() {
        val state = SessionState(halfLife)
        state.update(floatArrayOf(1f, 0f), atMs = 0L)
        state.reset()
        assertFalse(state.isPrimed)
    }
}
