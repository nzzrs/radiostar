/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.bench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug-only latency and memory benchmark for the CLAP audio tower.
 *
 * ```
 * adb shell am start -n link.nzrs.radiostar.dev/com.metrolist.music.bench.ClapBenchActivity
 * adb logcat -s ClapBench
 * ```
 *
 * The model is read from `src/debug/assets` and copied to `filesDir` once, so
 * the session loads from a path instead of a 30 MB byte array.
 */
class ClapBenchActivity : Activity() {
    companion object {
        private const val TAG = "ClapBench"
        private const val ASSET = "clap_tiny_int8.onnx"
        private const val INPUT_NAME = "waveform"
        private const val OUTPUT_NAME = "embedding"
        private const val SAMPLES = 480_000
        private const val WARMUP = 3
        private const val RUNS = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            runCatching { benchmark() }.onFailure { Log.e(TAG, "benchmark failed", it) }
            runOnUiThread { finish() }
        }, "clap-bench").start()
    }

    private fun benchmark() {
        val model = File(filesDir, ASSET)
        if (!model.exists()) {
            assets.open(ASSET).use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        Log.i(TAG, "model bytes=${model.length()}")

        val environment = OrtEnvironment.getEnvironment()
        val threads = intent.getIntExtra("threads", 2)
        val xnnpack = intent.getBooleanExtra("xnnpack", false)
        val options =
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (xnnpack) addXnnpack(emptyMap())
            }
        Log.i(TAG, "config threads=$threads xnnpack=$xnnpack")

        val loadStart = System.nanoTime()
        val session = environment.createSession(model.absolutePath, options)
        Log.i(TAG, "load ms=${(System.nanoTime() - loadStart) / 1_000_000}")

        try {
            runBenchmark(environment, session, threads, xnnpack)
        } finally {
            session.close()
        }
    }

    private fun runBenchmark(
        environment: OrtEnvironment,
        session: OrtSession,
        threads: Int,
        xnnpack: Boolean,
    ) {
        val runs = intent.getIntExtra("runs", RUNS)
        val cooldown = intent.getIntExtra("cooldownMs", 0)
        if (cooldown > 0) Thread.sleep(cooldown.toLong())

        val signal =
            FloatArray(SAMPLES) { index ->
                (0.2 * sin(2.0 * PI * 220.0 * index / 48_000.0)).toFloat()
            }
        val shape = longArrayOf(1, SAMPLES.toLong())

        repeat(WARMUP) { runOnce(environment, session, signal, shape) }

        val pssBefore = Debug.getPss()
        val times = LongArray(runs)
        repeat(runs) { run ->
            val start = System.nanoTime()
            runOnce(environment, session, signal, shape)
            times[run] = (System.nanoTime() - start) / 1_000_000
        }
        val pssAfter = Debug.getPss()

        times.sort()
        Log.i(
            TAG,
            "runs=$runs threads=$threads xnnpack=$xnnpack p50=${times[runs / 2]}ms " +
                "p95=${times[(runs * 95) / 100]}ms max=${times.last()}ms",
        )
        Log.i(TAG, "pss before=${pssBefore}kB after=${pssAfter}kB")
    }

    private fun runOnce(
        environment: OrtEnvironment,
        session: OrtSession,
        signal: FloatArray,
        shape: LongArray,
    ) {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(signal), shape).use { input ->
            session.run(mapOf(INPUT_NAME to input), setOf(OUTPUT_NAME)).use { result ->
                val embedding = (result.get(0).value as Array<*>)[0] as FloatArray
                check(embedding.size == 512) { "unexpected output size ${embedding.size}" }
            }
        }
    }
}
