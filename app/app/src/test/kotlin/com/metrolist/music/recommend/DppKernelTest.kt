package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DppKernelTest {
    @Test
    fun `select returns k unique indices`() {
        val u = unitMatrix(n = 20, d = 4, seed = 8)
        val kernel = DppKernel.build(u, BooleanArray(20) { true }, DoubleArray(20) { 1.0 })

        val picked = DppKernel.select(kernel, k = 8)

        assertEquals(8, picked.size)
        assertEquals(8, picked.toSet().size)
        assertTrue(picked.all { it in 0 until 20 })
    }

    @Test
    fun `duplicate vectors are not both selected`() {
        val u = unitMatrix(n = 12, d = 4, seed = 9)
        u[1] = u[0].copyOf()
        val kernel = DppKernel.build(u, BooleanArray(12) { true }, DoubleArray(12) { 1.0 })

        val picked = DppKernel.select(kernel, k = 4)

        assertFalse(picked.contains(0) && picked.contains(1))
    }

    @Test
    fun `cross modal entries are scaled by rho`() {
        val u = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.8, 0.6, 0.0, 0.0),
            doubleArrayOf(0.6, 0.0, 0.8, 0.0),
            doubleArrayOf(0.0, 0.8, 0.6, 0.0),
        )
        val isAudio = booleanArrayOf(true, true, false, false)
        val rho = 0.6
        val kernel = DppKernel.build(u, isAudio, DoubleArray(4) { 1.0 }, rho = rho)
        val gram = gramMatrix(u)

        assertEquals(gram[0][1], kernel[0][1], 1e-9)
        assertEquals(gram[2][3], kernel[2][3], 1e-9)
        assertEquals(rho * gram[0][2], kernel[0][2], 1e-9)
        assertEquals(rho * gram[1][3], kernel[1][3], 1e-9)
    }

    @Test
    fun `diagonal equals quality squared plus jitter`() {
        val u = unitMatrix(n = 8, d = 4, seed = 5)
        val quality = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8)
        val kernel = DppKernel.build(u, BooleanArray(8) { false }, quality)
        val meanSquaredQuality = quality.sumOf { it * it } / quality.size
        val jitter = 1e-6 * meanSquaredQuality

        for (i in quality.indices) {
            assertEquals(quality[i] * quality[i] + jitter, kernel[i][i], 1e-9)
        }
    }

    @Test
    fun `empty input returns an empty selection`() {
        assertTrue(DppKernel.selectSlate(emptyArray(), BooleanArray(0), DoubleArray(0), 5).isEmpty())
        assertTrue(DppKernel.select(emptyArray(), 3).isEmpty())
        assertTrue(DppKernel.build(emptyArray(), BooleanArray(0), DoubleArray(0)).isEmpty())
    }

    @Test
    fun `short input clamps k to the number of candidates`() {
        val kernel = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )

        val picked = DppKernel.select(kernel, k = 10)

        assertEquals(listOf(0, 1, 2), picked)
    }

    @Test
    fun `arguments are validated`() {
        val u = unitMatrix(n = 3, d = 2, seed = 7)
        val isAudio = BooleanArray(3) { true }
        val quality = DoubleArray(3) { 1.0 }

        assertThrows(IllegalArgumentException::class.java) {
            DppKernel.build(u, isAudio, quality, rho = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DppKernel.build(u, isAudio, quality, beta = -0.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DppKernel.build(u, isAudio, DoubleArray(3) { -1.0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            DppKernel.build(u, isAudio, DoubleArray(2) { 1.0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            DppKernel.build(u, BooleanArray(2) { true }, quality)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DppKernel.select(arrayOf(doubleArrayOf(1.0), doubleArrayOf(1.0, 2.0)), 1)
        }
    }

    @Test
    fun `golden selection matches the python reference`() {
        val u = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.8, 0.6, 0.0, 0.0),
            doubleArrayOf(0.6, 0.0, 0.8, 0.0),
            doubleArrayOf(0.0, 0.8, 0.6, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.6, 0.0, 0.8),
            doubleArrayOf(0.0, 0.0, 0.6, 0.8),
            doubleArrayOf(0.5, 0.5, 0.5, 0.5),
        )
        val isAudio = booleanArrayOf(true, false, true, false, true, false, true, false)
        val scores = doubleArrayOf(0.9, 0.4, 0.7, 0.3, 0.95, 0.5, 0.6, 0.45)

        val picked = DppKernel.selectSlate(u, isAudio, scores, k = 4)

        assertEquals(listOf(4, 0, 6, 5), picked)
    }

    private fun unitMatrix(n: Int, d: Int, seed: Int): Array<DoubleArray> {
        val random = Random(seed.toLong())
        return Array(n) {
            val row = DoubleArray(d) { random.nextGaussian() }
            val norm = kotlin.math.sqrt(row.sumOf { value -> value * value })
            DoubleArray(d) { index -> row[index] / norm }
        }
    }

    private fun gramMatrix(u: Array<DoubleArray>): Array<DoubleArray> {
        return Array(u.size) { i ->
            DoubleArray(u.size) { j ->
                var dot = 0.0
                for (d in u[i].indices) {
                    dot += u[i][d] * u[j][d]
                }
                dot
            }
        }
    }
}
