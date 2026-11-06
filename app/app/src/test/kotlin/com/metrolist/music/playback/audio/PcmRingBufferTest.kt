/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun `blocks are delivered in write order`() {
        val ring = PcmRingBuffer(blockCount = 4, blockSize = 8)
        ring.write(bytesOf(0, 1, 2, 3, 4, 5, 6, 7))
        ring.write(bytesOf(10, 11, 12, 13, 14, 15, 16, 17))

        assertTrue(ring.hasData())
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), read(ring))
        assertArrayEquals(byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17), read(ring))
        assertFalse(ring.hasData())
    }

    @Test
    fun `oversized write is split across blocks`() {
        val ring = PcmRingBuffer(blockCount = 4, blockSize = 8)
        ring.write(bytesOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))

        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), read(ring))
        assertArrayEquals(byteArrayOf(8, 9), read(ring))
    }

    @Test
    fun `full ring drops instead of blocking`() {
        val ring = PcmRingBuffer(blockCount = 4, blockSize = 4)
        repeat(3) { value -> ring.write(bytesOf(value)) }

        ring.write(bytesOf(99))

        assertEquals(1L, ring.droppedBlocks)
        assertEquals(1L, ring.droppedBytes)
        assertArrayEquals(byteArrayOf(0), read(ring))
        assertArrayEquals(byteArrayOf(1), read(ring))
        assertArrayEquals(byteArrayOf(2), read(ring))
        assertFalse(ring.hasData())
    }

    @Test
    fun `discardPending empties the ring`() {
        val ring = PcmRingBuffer(blockCount = 4, blockSize = 4)
        ring.write(bytesOf(1, 2))

        ring.discardPending()

        assertFalse(ring.hasData())
    }

    private fun read(ring: PcmRingBuffer): ByteArray {
        val block = ring.readBlock()
        val length = ring.readLength()
        ring.advance()
        return block.copyOf(length)
    }

    private fun bytesOf(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size).apply {
            values.forEach { put(it.toByte()) }
            flip()
        }
}
