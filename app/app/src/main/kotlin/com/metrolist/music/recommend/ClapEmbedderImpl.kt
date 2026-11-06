/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Process
import android.util.Log
import com.metrolist.music.playback.audio.ClapEmbedder
import com.metrolist.music.playback.audio.SincResampler
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * CLAP audio tower on ONNX Runtime. The model is a 34.7 MB INT8 asset that
 * ships with every build, because a release without it cannot recommend.
 *
 * One window in flight at most: if the previous embedding has not finished the
 * new window is dropped and counted. The tap must never wait for inference.
 */
class ClapEmbedderImpl(context: Context) : ClapEmbedder {
    companion object {
        private const val TAG = "ClapEmbed"
        private const val ASSET = "clap_tiny_int8.onnx"
        private const val TARGET_RATE = 48_000
        private const val SAMPLES = 480_000
    }

    private val appContext = context.applicationContext

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clap-embed").apply {
                isDaemon = true
                priority = Process.THREAD_PRIORITY_BACKGROUND
            }
        }

    private val inFlight = AtomicInteger(0)

    @Volatile
    private var dropped: Long = 0L

    override val droppedWindows: Long
        get() = dropped

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null

    override fun submit(window: FloatArray, sampleRate: Int, onResult: (FloatArray) -> Unit) {
        if (inFlight.get() >= 1) {
            dropped += 1L
            return
        }
        inFlight.incrementAndGet()
        executor.execute {
            try {
                onResult(embed(window, sampleRate))
            } catch (error: Throwable) {
                Log.e(TAG, "embedding failed", error)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun embed(window: FloatArray, sampleRate: Int): FloatArray {
        val started = System.nanoTime()

        val resampled = SincResampler.resample(window, sampleRate, TARGET_RATE)
        val pcm = FloatArray(SAMPLES)
        System.arraycopy(resampled, 0, pcm, 0, minOf(resampled.size, SAMPLES))

        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val ort = session ?: createSession(env).also { session = it }

        OnnxTensor
            .createTensor(env, FloatBuffer.wrap(pcm), longArrayOf(1, SAMPLES.toLong()))
            .use { input ->
                ort.run(mapOf("waveform" to input), setOf("embedding")).use { result ->
                    val vector = (result.get(0).value as Array<*>)[0] as FloatArray
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    Log.i(TAG, "embed ms=$elapsed dim=${vector.size} norm=${norm(vector)}")
                    return vector
                }
            }
    }

    private fun createSession(env: OrtEnvironment): OrtSession {
        val model = File(appContext.filesDir, ASSET)
        if (!model.exists()) {
            appContext.assets.open(ASSET).use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val options =
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                addXnnpack(emptyMap())
            }

        val started = System.nanoTime()
        val created = env.createSession(model.absolutePath, options)
        Log.i(TAG, "session load ms=${(System.nanoTime() - started) / 1_000_000} bytes=${model.length()}")
        return created
    }

    private fun norm(vector: FloatArray): Float {
        var energy = 0f
        for (value in vector) energy += value * value
        return sqrt(energy)
    }

    override fun close() {
        session?.close()
        session = null
        executor.shutdownNow()
    }
}
