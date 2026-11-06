/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-producer / single-consumer ring of pre-allocated blocks.
 *
 * The audio thread only copies bytes here; conversion, downmix and resampling
 * happen on the worker thread. Nothing in the steady-state path allocates.
 *
 * When the ring is full the writer drops the block and increments
 * [droppedBlocks] instead of waiting. Blocking the audio thread would cause an
 * underrun, which is exactly what this spike is trying to avoid.
 */
class PcmRingBuffer(
    private val blockCount: Int = 8,
    val blockSize: Int = 64 * 1024,
) {
    private val blocks = Array(blockCount) { ByteArray(blockSize) }
    private val lengths = IntArray(blockCount)

    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    @Volatile
    var droppedBlocks: Long = 0L
        private set

    @Volatile
    var droppedBytes: Long = 0L
        private set

    @Volatile
    var writtenBytes: Long = 0L
        private set

    fun write(source: ByteBuffer) {
        while (source.hasRemaining()) {
            val current = writeIndex.get()
            val next = (current + 1) % blockCount
            if (next == readIndex.get()) {
                droppedBlocks += 1L
                droppedBytes += source.remaining().toLong()
                source.position(source.limit())
                return
            }
            val length = minOf(source.remaining(), blockSize)
            source.get(blocks[current], 0, length)
            lengths[current] = length
            writtenBytes += length.toLong()
            writeIndex.set(next)
        }
    }

    fun hasData(): Boolean = readIndex.get() != writeIndex.get()

    fun readBlock(): ByteArray = blocks[readIndex.get()]

    fun readLength(): Int = lengths[readIndex.get()]

    fun advance() {
        readIndex.set((readIndex.get() + 1) % blockCount)
    }

    fun discardPending() {
        readIndex.set(writeIndex.get())
    }

    fun resetCounters() {
        droppedBlocks = 0L
        droppedBytes = 0L
        writtenBytes = 0L
    }
}
