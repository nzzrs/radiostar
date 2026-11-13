/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.metrolist.music.recommend.AiFeature
import com.metrolist.music.recommend.AiProviderResolver
import com.metrolist.music.recommend.AiTtsClient
import com.metrolist.music.recommend.DjDuckGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * Platform TTS for the DJ voice.
 *
 * The phone's own engine is used instead of bundling a voice, so the APK stays
 * small and the user picks the voice in Android. A short partial wake lock
 * keeps the utterance alive while the screen is off.
 *
 * The music is only ducked once the voice is actually audible: a synthesized
 * clip ducks on [MediaPlayer.start], the phone engine on its `onStart`
 * callback. A failed attempt leaves the current song untouched.
 */
class DjAnnouncer(
    context: Context,
    private val onDuck: (Boolean) -> Unit,
) {
    /** Callbacks for one spoken link; all run on the main thread. */
    data class Callbacks(
        val onReady: () -> Unit = {},
        val onStart: () -> Unit = {},
        val onDone: () -> Unit = {},
        val onError: () -> Unit = {},
    )

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val duckGate = DjDuckGate()

    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentUtterance: String? = null
    private var currentCallbacks: Callbacks? = null
    private var pending: PendingUtterance? = null
    private var remoteJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var remoteFile: File? = null
    private var lastDurationMs: Long = 0L
    private var pitch = 0.95f
    private var rate = 0.98f

    @Volatile
    private var ready = false

    fun prepare() {
        if (tts != null) return
        tts =
            TextToSpeech(appContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Timber.tag(TAG).w("tts init failed status=%d", status)
                    val failed = pending
                    pending = null
                    failed?.callbacks?.onError?.invoke()
                    return@TextToSpeech
                }
                configure()
            }
    }

    private fun configure() {
        val engine = tts ?: return
        val language = engine.setLanguage(Locale.getDefault())
        if (language == TextToSpeech.LANG_MISSING_DATA ||
            language == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.setLanguage(Locale.US)
        }
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        engine.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != currentUtterance) return
                    Timber.tag(TAG).i("utterance start id=%s", utteranceId)
                    // First audible frame: only now may the music drop.
                    duck()
                    currentCallbacks?.onStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Timber.tag(TAG).i("utterance done id=%s", utteranceId)
                    finish(utteranceId, error = false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId, error = true)

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Timber.tag(TAG).w("tts error id=%s code=%d", utteranceId, errorCode)
                    finish(utteranceId, error = true)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) =
                    finish(utteranceId, error = true)
            },
        )
        ready = true
        pending?.let { queued ->
            pending = null
            speakNow(queued.text, queued.id, queued.callbacks)
        }
    }

    fun setVoice(pitch: Float, rate: Float) {
        this.pitch = pitch.coerceIn(0.5f, 1.5f)
        this.rate = rate.coerceIn(0.5f, 1.5f)
        if (ready) {
            tts?.setPitch(this.pitch)
            tts?.setSpeechRate(this.rate)
        }
    }

    /**
     * Speaks [text] with an already synthesized [audio] clip, or hands the line
     * to the phone engine when no clip is available.
     */
    fun announce(
        text: String,
        utteranceId: String,
        audio: ByteArray?,
        callbacks: Callbacks = Callbacks(),
    ): Boolean {
        if (text.isBlank() && audio == null) return false
        remoteJob?.cancel()
        stopRemotePlayback()
        lastDurationMs = 0L
        currentUtterance = utteranceId
        currentCallbacks = callbacks
        acquireWakeLock()
        if (audio != null && audio.isNotEmpty()) {
            remoteJob = scope.launch { playRemote(audio, text, utteranceId) }
            return true
        }
        return speakPhone(text, utteranceId)
    }

    private fun speakPhone(
        text: String,
        utteranceId: String,
    ): Boolean {
        if (text.isBlank()) {
            finish(utteranceId, error = true)
            return false
        }
        if (!ready) {
            pending = PendingUtterance(text, utteranceId, currentCallbacks ?: Callbacks())
            prepare()
            return true
        }
        return speakNow(text, utteranceId, currentCallbacks)
    }

    /** Plays the synthesized clip, or hands the same line to the phone engine. */
    private suspend fun playRemote(
        audio: ByteArray,
        text: String,
        utteranceId: String,
    ) {
        val file =
            withContext(Dispatchers.IO) {
                runCatching {
                    File.createTempFile("dj-tts", ".mp3", appContext.cacheDir)
                        .apply { writeBytes(audio) }
                }.getOrNull()
            }
        if (file == null || currentUtterance != utteranceId) {
            file?.delete()
            if (currentUtterance == utteranceId) speakPhone(text, utteranceId)
            return
        }
        val player = MediaPlayer()
        mediaPlayer = player
        remoteFile = file
        runCatching {
            player.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                stopRemotePlayback()
                finish(utteranceId, error = false)
            }
            player.setOnErrorListener { _, _, _ ->
                Timber.tag(TAG).w("remote tts playback failed")
                stopRemotePlayback()
                speakPhone(text, utteranceId)
                true
            }
            player.prepare()
            if (currentUtterance != utteranceId) {
                stopRemotePlayback()
                return@runCatching
            }
            lastDurationMs = player.duration.toLong().coerceAtLeast(0L)
            currentCallbacks?.onReady?.invoke()
            player.start()
            // First audible frame: only now may the music drop.
            duck()
            currentCallbacks?.onStart?.invoke()
        }.onFailure {
            Timber.tag(TAG).w(it, "remote tts playback threw")
            stopRemotePlayback()
            speakPhone(text, utteranceId)
        }
    }

    private fun speakNow(
        text: String,
        utteranceId: String,
        callbacks: Callbacks?,
    ): Boolean {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        currentUtterance = utteranceId
        currentCallbacks = callbacks
        acquireWakeLock()
        val result =
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "tts speak threw")
                null
            }
        if (result != TextToSpeech.SUCCESS) {
            finish(utteranceId, error = true)
            return false
        }
        // The engine accepted it; onStart will duck once it is audible.
        callbacks?.onReady?.invoke()
        return true
    }

    fun shutdown() {
        remoteJob?.cancel()
        remoteJob = null
        stopRemotePlayback()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
            // The engine may already be gone.
        }
        tts = null
        ready = false
        currentUtterance = null
        currentCallbacks = null
        pending = null
        releaseWakeLock()
        if (duckGate.onPlaybackStopped()) onDuck(false)
        duckGate.reset()
    }

    /** Duration of the clip that is currently playing, or 0 when unknown. */
    fun currentClipDurationMs(): Long = lastDurationMs

    private fun stopRemotePlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        try {
            player?.stop()
            player?.release()
        } catch (_: Throwable) {
            // Best effort.
        }
        remoteFile?.delete()
        remoteFile = null
    }

    private fun finish(
        utteranceId: String?,
        error: Boolean,
    ) {
        if (utteranceId != currentUtterance) return
        currentUtterance = null
        val callbacks = currentCallbacks
        currentCallbacks = null
        releaseWakeLock()
        val restore = duckGate.onPlaybackStopped()
        if (restore) onDuck(false)
        if (error) callbacks?.onError?.invoke() else callbacks?.onDone?.invoke()
    }

    private fun duck() {
        if (duckGate.onPlaybackStarted()) onDuck(true)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock =
            wakeLock
                ?: powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioStar:Dj")
                    .apply { setReferenceCounted(false) }
                    .also { wakeLock = it }
        try {
            lock.acquire(60_000L)
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "wake lock failed")
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Throwable) {
            // Best effort.
        }
    }

    private data class PendingUtterance(
        val text: String,
        val id: String,
        val callbacks: Callbacks,
    )

    companion object {
        private const val TAG = "RadioStarDj"

        /** Duration of synthesized speech, probed before scheduling it. */
        fun measureClipDurationMs(
            context: Context,
            audio: ByteArray,
        ): Long {
            if (audio.isEmpty()) return 0L
            val file =
                runCatching {
                    File.createTempFile("dj-duration", ".mp3", context.applicationContext.cacheDir)
                        .apply { writeBytes(audio) }
                }.getOrNull() ?: return 0L
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
            } catch (error: Throwable) {
                Timber.tag(TAG).w(error, "clip duration probe failed")
                0L
            } finally {
                runCatching { retriever.release() }
                file.delete()
            }
        }

        /** Resolves the TTS provider, or null when speech synthesis is unavailable. */
        suspend fun resolveTtsConfig(context: Context) =
            runCatching { AiProviderResolver.resolve(context, AiFeature.TTS) }.getOrNull()

        suspend fun synthesize(
            context: Context,
            text: String,
        ): ByteArray? {
            val config = resolveTtsConfig(context) ?: return null
            if (!config.canSpeak) return null
            return AiTtsClient.synthesize(config, text)
        }
    }
}
