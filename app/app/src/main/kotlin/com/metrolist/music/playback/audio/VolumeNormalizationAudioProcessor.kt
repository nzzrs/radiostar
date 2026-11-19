package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var sampleRate = 0

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    private data class GainState(val targetGainMb: Int, val linearGain: Double)

    @Volatile
    private var currentGain = GainState(0, 1.0)

    // Audio-thread-only ramp state; setTargetGain may run on the main thread.
    private val gainRamp = GainRamp()
    private var rampTarget = 1.0

    @Synchronized
    fun setTargetGain(gainMb: Int) {
        if (currentGain.targetGainMb != gainMb) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain)")
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }

        Timber.tag(TAG).d(
            "Configured: sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, encoding=$encoding",
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val gain = currentGain
        val applyGain = enabled && gain.targetGainMb != 0
        if (applyGain) {
            if (gain.linearGain != rampTarget) {
                rampTarget = gain.linearGain
                gainRamp.setTarget(rampTarget, rampSamples())
            }
        } else if (rampTarget != 1.0) {
            // Bypassed audio must not leave the next enable ramping from a stale level.
            rampTarget = 1.0
            gainRamp.snapTo(1.0)
        }
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * bytesPerSample)

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getShort()
                    output.putShort(
                        if (applyGain) {
                            (sample * gainRamp.next())
                                .coerceIn(-32768.0, 32767.0)
                                .toInt()
                                .toShort()
                        } else {
                            sample
                        },
                    )
                }

            C.ENCODING_PCM_24BIT ->
                repeat(sampleCount) {
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt()
                    val sample = (b2 shl 16) or (b1 shl 8) or b0
                    val processed =
                        if (applyGain) {
                            (sample * gainRamp.next())
                                .coerceIn(-8388608.0, 8388607.0)
                                .toInt()
                        } else {
                            sample
                        }
                    output.put((processed and 0xFF).toByte())
                    output.put(((processed shr 8) and 0xFF).toByte())
                    output.put(((processed shr 16) and 0xFF).toByte())
                }

            C.ENCODING_PCM_32BIT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getInt()
                    output.putInt(
                        if (applyGain) {
                            (sample * gainRamp.next())
                                .coerceIn(-2147483648.0, 2147483647.0)
                                .toLong()
                                .toInt()
                        } else {
                            sample
                        },
                    )
                }

            C.ENCODING_PCM_FLOAT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getFloat()
                    output.putFloat(
                        if (applyGain) {
                            (sample * gainRamp.next().toFloat()).coerceIn(-1.0f, 1.0f)
                        } else {
                            sample
                        },
                    )
                }
        }

        output.flip()
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        sampleRate = 0
        rampTarget = 1.0
        gainRamp.snapTo(1.0)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // A flush means a new stream or a seek: start from the already-known
        // target instead of carrying the previous track's ramp level.
        val target = if (enabled && currentGain.targetGainMb != 0) currentGain.linearGain else 1.0
        rampTarget = target
        gainRamp.snapTo(target)
    }

    private fun rampSamples(): Int =
        if (sampleRate <= 0) 0 else (sampleRate.toLong() * GAIN_RAMP_MS / 1_000L).toInt()

    private companion object {
        const val TAG = "VolumeNormalizationProcessor"
        const val GAIN_RAMP_MS = 300L
    }
}
