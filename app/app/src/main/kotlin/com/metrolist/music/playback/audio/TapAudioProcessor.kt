/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Pass-through processor that copies decoded PCM into a [PcmRingBuffer].
 *
 * Nothing here may allocate, lock, log or block: `queueInput` runs on the
 * playback thread, and a single missed deadline is an audible underrun. The
 * buffer is always passed through unchanged, whatever [recording] is set to.
 */
@UnstableApi
class TapAudioProcessor(
    private val ring: PcmRingBuffer,
    private val onFlush: () -> Unit = {},
) : BaseAudioProcessor() {
    @Volatile
    var recording: Boolean = false

    @Volatile
    var channelCount: Int = 0
        private set

    @Volatile
    var sampleRate: Int = 0
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        if (recording) {
            val position = inputBuffer.position()
            ring.write(inputBuffer)
            inputBuffer.position(position)
        }

        replaceOutputBuffer(inputBuffer.remaining()).apply {
            put(inputBuffer)
            flip()
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        onFlush.invoke()
    }

    override fun onReset() {
        channelCount = 0
        sampleRate = 0
    }
}
