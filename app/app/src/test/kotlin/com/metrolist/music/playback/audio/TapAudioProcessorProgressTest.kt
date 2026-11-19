package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class TapAudioProcessorProgressTest {
    @Suppress("DEPRECATION")
    @Test
    fun `processed bytes grow with every buffer`() {
        val processor = TapAudioProcessor(PcmRingBuffer())
        processor.configure(INPUT_FORMAT)
        processor.flush()

        processor.queueInput(buffer(8))
        val first = processor.bytesProcessed
        processor.queueInput(buffer(16))
        val second = processor.bytesProcessed

        assertEquals(8L, first)
        assertEquals(24L, second)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `an empty buffer does not advance the counter`() {
        val processor = TapAudioProcessor(PcmRingBuffer())
        processor.configure(INPUT_FORMAT)
        processor.flush()

        processor.queueInput(ByteBuffer.allocateDirect(0))

        assertEquals(0L, processor.bytesProcessed)
    }

    private fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(size / Short.SIZE_BYTES) { putShort(it.toShort()) }
                flip()
            }

    private companion object {
        val INPUT_FORMAT = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
    }
}
