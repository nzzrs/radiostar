/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale

/**
 * Platform TTS for the DJ voice.
 *
 * The phone's own engine is used instead of bundling a voice, so the APK stays
 * small and the user picks the voice in Android. A short partial wake lock
 * keeps the utterance alive while the screen is off, and the caller gets
 * `duck(true/false)` around it to lower the music.
 */
class DjAnnouncer(
    context: Context,
    private val onDuck: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentUtterance: String? = null
    private var currentOnDone: (() -> Unit)? = null
    private var pending: PendingUtterance? = null
    private var ducked = false
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
                    failed?.onDone?.invoke()
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
                    Timber.tag(TAG).i("utterance start id=%s", utteranceId)
                }

                override fun onDone(utteranceId: String?) {
                    Timber.tag(TAG).i("utterance done id=%s", utteranceId)
                    finish(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Timber.tag(TAG).w("tts error id=%s code=%d", utteranceId, errorCode)
                    finish(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
            },
        )
        ready = true
        pending?.let { queued ->
            pending = null
            speakNow(queued.text, queued.id, queued.onDone)
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

    fun announce(
        text: String,
        utteranceId: String,
        onDone: (() -> Unit)? = null,
    ): Boolean {
        if (text.isBlank()) return false
        if (!ready) {
            pending = PendingUtterance(text, utteranceId, onDone)
            prepare()
            return true
        }
        return speakNow(text, utteranceId, onDone)
    }

    private fun speakNow(
        text: String,
        utteranceId: String,
        onDone: (() -> Unit)?,
    ): Boolean {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        currentUtterance = utteranceId
        currentOnDone = onDone
        duck()
        acquireWakeLock()
        val result =
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "tts speak threw")
                null
            }
        if (result != TextToSpeech.SUCCESS) {
            finish(utteranceId)
            return false
        }
        return true
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
            // The engine may already be gone.
        }
        tts = null
        ready = false
        currentUtterance = null
        currentOnDone = null
        pending = null
        releaseWakeLock()
        unduck()
    }

    private fun finish(utteranceId: String?) {
        if (utteranceId != currentUtterance) return
        currentUtterance = null
        val callback = currentOnDone
        currentOnDone = null
        releaseWakeLock()
        unduck()
        callback?.invoke()
    }

    private fun duck() {
        if (ducked) return
        ducked = true
        onDuck(true)
    }

    private fun unduck() {
        if (!ducked) return
        ducked = false
        onDuck(false)
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
        val onDone: (() -> Unit)?,
    )

    private companion object {
        const val TAG = "RadioStarDj"
    }
}
