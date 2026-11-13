/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjQueueDiffTest {
    private val a = DjQueueItem("a", "Alpha", "Artist A")
    private val b = DjQueueItem("b", "Beta", "Artist B")
    private val c = DjQueueItem("c", "Gamma", "Artist C")
    private val d = DjQueueItem("d", "Delta", "Artist D")

    @Test
    fun `appending a track reports it as a later request`() {
        val change = DjQueueDiff.diff(listOf(a, b), listOf(a, b, c), currentIndex = 0)

        assertEquals(listOf(c), change.appended)
        assertTrue(change.insertedNext.isEmpty())
        assertTrue(change.isUserRequest)
        assertEquals(c, change.nextRequest)
        assertFalse(change.replaced)
    }

    @Test
    fun `play next reports the new immediate next track`() {
        val change = DjQueueDiff.diff(listOf(a, b), listOf(a, c, b), currentIndex = 0)

        assertEquals(listOf(c), change.insertedNext)
        assertTrue(change.appended.isEmpty())
        assertTrue(change.isUserRequest)
        assertEquals(c, change.nextRequest)
        assertFalse(change.replaced)
    }

    @Test
    fun `duplicate song ids count as one addition per extra occurrence`() {
        val change = DjQueueDiff.diff(listOf(a, b, a), listOf(a, b, a, a), currentIndex = 0)

        assertEquals(listOf(a), change.appended)
        assertEquals(1, change.appended.size)
        assertTrue(change.insertedNext.isEmpty())
    }

    @Test
    fun `a known duplicate is not a request when nothing was added`() {
        val change = DjQueueDiff.diff(listOf(a, b, a), listOf(a, b, a), currentIndex = 0)

        assertFalse(change.isUserRequest)
        assertTrue(change.appended.isEmpty())
        assertTrue(change.insertedNext.isEmpty())
    }

    @Test
    fun `empty queues report no request`() {
        val change = DjQueueDiff.diff(emptyList(), emptyList(), currentIndex = 0)

        assertFalse(change.isUserRequest)
        assertTrue(change.appended.isEmpty())
        assertTrue(change.insertedNext.isEmpty())
        assertFalse(change.replaced)
    }

    @Test
    fun `a session without a baseline reports no request`() {
        val change = DjQueueDiff.diff(null, listOf(a, b, c), currentIndex = 0)

        assertFalse(change.isUserRequest)
        assertTrue(change.appended.isEmpty())
        assertTrue(change.insertedNext.isEmpty())
        assertFalse(change.replaced)
    }

    @Test
    fun `an unchanged queue reports nothing`() {
        val change = DjQueueDiff.diff(listOf(a, b, c), listOf(a, b, c), currentIndex = 1)

        assertFalse(change.isUserRequest)
        assertTrue(change.appended.isEmpty())
        assertTrue(change.insertedNext.isEmpty())
        assertFalse(change.replaced)
    }

    @Test
    fun `removals and moves caused by playback are not requests`() {
        // The current track moved on, one item was dropped, nothing was added.
        val change = DjQueueDiff.diff(listOf(a, b, c, d), listOf(b, c, d), currentIndex = 0)

        assertFalse(change.isUserRequest)
        assertFalse(change.replaced)
    }

    @Test
    fun `a move of an existing item is not a request`() {
        val change = DjQueueDiff.diff(listOf(a, b, c), listOf(a, c, b), currentIndex = 0)

        assertFalse(change.isUserRequest)
        assertFalse(change.replaced)
    }

    @Test
    fun `a completely different queue is a replacement not a request`() {
        val change = DjQueueDiff.diff(listOf(a, b), listOf(c, d), currentIndex = 0)

        assertTrue(change.replaced)
        assertFalse(change.isUserRequest)
    }

    @Test
    fun `replacement on a new session keeps the old ids out of the request set`() {
        val oldSession = listOf(a, b, c)
        val newSession = listOf(d, DjQueueItem("e", "Echo", "Artist E"))

        val change = DjQueueDiff.diff(oldSession, newSession, currentIndex = 0)

        assertTrue(change.replaced)
        assertFalse(change.isUserRequest)
    }

    @Test
    fun `insert next wins over a later append for the announcement`() {
        val change = DjQueueDiff.diff(listOf(a, b), listOf(a, c, b, d), currentIndex = 0)

        assertEquals(listOf(c), change.insertedNext)
        assertEquals(listOf(d), change.appended)
        assertEquals(c, change.nextRequest)
    }

    @Test
    fun `several play next items keep their insertion order`() {
        val change = DjQueueDiff.diff(listOf(a, b), listOf(a, c, d, b), currentIndex = 0)

        assertEquals(listOf(c, d), change.insertedNext)
        assertEquals(c, change.nextRequest)
    }
}
