/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyOrdererTest {
    private fun entry(id: String, score: Double) =
        SlateEntry(
            candidate =
                Candidate(
                    id = id,
                    title = "title-$id",
                    artists = listOf("artist-$id"),
                    vector = doubleArrayOf(1.0),
                ),
            score = score,
        )

    @Test
    fun `known energies fill their slots from quiet to loud`() {
        val entries = listOf(entry("a", 0.9), entry("b", 0.1), entry("c", 0.5))
        val rms = mapOf("a" to 0.8f, "b" to 0.1f, "c" to 0.4f)

        val ordered = EnergyOrderer.orderByEnergyArc(entries, rms)

        assertEquals(listOf("b", "c", "a"), ordered.map { it.candidate.id })
    }

    @Test
    fun `unknown candidates keep their score position`() {
        val entries = listOf(entry("a", 0.9), entry("x", 0.8), entry("b", 0.1), entry("c", 0.5))
        val rms = mapOf("a" to 0.8f, "b" to 0.1f, "c" to 0.4f)

        val ordered = EnergyOrderer.orderByEnergyArc(entries, rms)

        assertEquals(listOf("b", "x", "c", "a"), ordered.map { it.candidate.id })
    }

    @Test
    fun `a single known energy leaves the order alone`() {
        val entries = listOf(entry("a", 0.9), entry("b", 0.1))
        val ordered = EnergyOrderer.orderByEnergyArc(entries, mapOf("a" to 0.2f))

        assertEquals(entries, ordered)
    }

    @Test
    fun `no profiles leaves the order alone`() {
        val entries = listOf(entry("a", 0.9), entry("b", 0.1))

        assertEquals(entries, EnergyOrderer.orderByEnergyArc(entries, emptyMap()))
    }

    @Test
    fun `one entry is returned unchanged`() {
        val entries = listOf(entry("a", 1.0))

        assertEquals(entries, EnergyOrderer.orderByEnergyArc(entries, mapOf("a" to 0.5f)))
    }
}
