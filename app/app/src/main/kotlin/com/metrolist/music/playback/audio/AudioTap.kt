/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Worker that turns tapped PCM into 10 s mono windows at 48 kHz.
 *
 * The audio thread only copies bytes into [ring]. This class drains it, downmixes
 * to mono, and resamples once per window. The embeddings themselves are computed
 * by the caller through [listener], outside the audio pipeline.
 *
 * PCM is never written to disk, logged, or sent anywhere: the window lives in
 * memory, is handed to the listener, and is dropped when the listener returns.
 */
@UnstableApi
class AudioTap(
    private val windowSeconds: Int = 10,
) {
    companion object {
        private const val TAG = "AudioTap"
    }

    val ring = PcmRingBuffer()

    private val flushGeneration = AtomicInteger(0)

    val processor = TapAudioProcessor(ring) { flushGeneration.incrementAndGet() }

    @Volatile
    var listener: ((FloatArray, Int) -> Unit)? = null

    @Volatile
    var windowsEmitted: Long = 0L
        private set

    @Volatile
    var lastWindowRms: Float = 0f
        private set

    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            processor.recording = value
            flushGeneration.incrementAndGet()
            if (value) startWorker()
        }

    private var accumulator = FloatArray(0)
    private var accumulatorSize = 0
    private var currentRate = 0
    private var worker: Thread? = null

    private fun startWorker() {
        synchronized(this) {
            if (worker != null) return
            worker = Thread({ loop() }, "radiostar-audio-tap").apply {
                isDaemon = true
                priority = Process.THREAD_PRIORITY_BACKGROUND
                start()
            }
        }
    }

    private fun loop() {
        while (true) {
            try {
                drain()
            } catch (interrupted: InterruptedException) {
                return
            } catch (error: Throwable) {
                Log.e(TAG, "audio tap worker failed", error)
                accumulatorSize = 0
            }
            try {
                Thread.sleep(10L)
            } catch (interrupted: InterruptedException) {
                return
            }
        }
    }

    private fun drain() {
        val sampleRate = processor.sampleRate
        val channels = processor.channelCount
        if (sampleRate <= 0 || channels <= 0) {
            ring.discardPending()
            return
        }
        ensureAccumulator(sampleRate)

        val generation = flushGeneration.get()
        while (ring.hasData()) {
            val block = ring.readBlock()
            val length = ring.readLength()
            ring.advance()

            if (!enabled || generation != flushGeneration.get()) {
                accumulatorSize = 0
                return
            }
            appendMono(block, length, channels)
        }
    }

    private fun ensureAccumulator(sampleRate: Int) {
        if (sampleRate == currentRate && accumulator.isNotEmpty()) return
        currentRate = sampleRate
        accumulator = FloatArray(windowSeconds * sampleRate)
        accumulatorSize = 0
    }

    private fun appendMono(block: ByteArray, length: Int, channels: Int) {
        val frameBytes = channels * 2
        val frames = length / frameBytes
        val capacity = accumulator.size

        for (frame in 0 until frames) {
            val base = frame * frameBytes
            var sum = 0
            for (channel in 0 until channels) {
                val offset = base + channel * 2
                val low = block[offset].toInt() and 0xFF
                val high = block[offset + 1].toInt()
                sum += (high shl 8) or low
            }
            if (accumulatorSize < capacity) {
                accumulator[accumulatorSize] = (sum / channels) / 32768f
                accumulatorSize += 1
            }
        }

        if (accumulatorSize >= capacity) emit()
    }

    private fun emit() {
        // The window is handed over at the source rate. Resampling is the
        // consumer's job: doing it here stalled the drain loop for ~300 ms per
        // window, which showed up as a steady 3-4% of audio dropped.
        val window = FloatArray(accumulatorSize)
        System.arraycopy(accumulator, 0, window, 0, accumulatorSize)
        accumulatorSize = 0

        var energy = 0f
        for (sample in window) energy += sample * sample
        lastWindowRms = sqrt(energy / window.size)
        windowsEmitted += 1L

        listener?.invoke(window, currentRate)
    }
}
