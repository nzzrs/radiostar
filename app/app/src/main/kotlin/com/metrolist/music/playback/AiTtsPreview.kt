/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.metrolist.music.R
import com.metrolist.music.recommend.AiFeature
import com.metrolist.music.recommend.AiProviderResolver
import com.metrolist.music.recommend.AiTtsClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** One-shot AI voice sample for the settings screen. */
object AiTtsPreview {
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    /** Returns null when playback started, otherwise a user-facing error. */
    suspend fun speak(context: Context, text: String): String? {
        val appContext = context.applicationContext
        val config = AiProviderResolver.resolve(appContext, AiFeature.TTS)
        if (!config.canSpeak) return appContext.getString(R.string.ai_voice_test_failed)
        val audio = AiTtsClient.synthesize(config, text)
        if (audio == null) {
            Timber.tag("RadioStarTts").w("voice preview synthesis failed")
            return appContext.getString(R.string.ai_voice_test_failed)
        }
        val file =
            withContext(Dispatchers.IO) {
                runCatching {
                    File.createTempFile("ai-voice-test", ".audio", appContext.cacheDir)
                        .apply { writeBytes(audio) }
                }.getOrNull()
            } ?: return appContext.getString(R.string.ai_voice_test_failed)
        return withContext(Dispatchers.Main) {
            stop()
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            tempFile = file
            runCatching {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.setOnCompletionListener { stop() }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                mediaPlayer.prepare()
                mediaPlayer.start()
                null
            }.getOrElse {
                Timber.tag("RadioStarTts").w(it, "voice preview playback failed")
                stop()
                appContext.getString(R.string.ai_voice_test_failed)
            }
        }
    }

    fun stop() {
        val mediaPlayer = player
        player = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Throwable) {
            // The player may already be released.
        }
        tempFile?.delete()
        tempFile = null
    }
}
