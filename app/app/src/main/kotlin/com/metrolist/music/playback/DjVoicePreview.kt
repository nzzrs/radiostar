/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.recommend.AiFeature
import com.metrolist.music.recommend.AiProviderResolver
import com.metrolist.music.recommend.AiTtsClient
import com.metrolist.music.recommend.DjPersona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One-shot DJ voice sample for the persona editor. It goes through
 * [DjAnnouncer], the same engine as a session, so the sample uses the
 * configured AI speech service or the phone voice and the persona pitch/rate.
 */
object DjVoicePreview {
    private var announcer: DjAnnouncer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Returns null when the line was queued, otherwise a user-facing error. */
    fun speak(
        context: Context,
        persona: DjPersona,
        text: String,
        onFinished: () -> Unit,
    ): String? {
        val appContext = context.applicationContext
        return runCatching {
            val engine =
                announcer
                    ?: DjAnnouncer(appContext) {}
                        .also { announcer = it }
            engine.setVoice(persona.pitch, persona.rate)
            engine.prepare()
            val utteranceId = "dj:preview:${System.nanoTime()}"
            scope.launch {
                val audio = synthesizeClip(appContext, text)
                val queued =
                    engine.announce(
                        text,
                        utteranceId,
                        audio,
                        DjAnnouncer.Callbacks(onDone = onFinished, onError = onFinished),
                    )
                if (!queued) onFinished()
            }
            null
        }.getOrElse {
            Timber.tag("RadioStarTts").w(it, "dj voice preview failed")
            onFinished()
            appContext.getString(R.string.ai_voice_test_failed)
        }
    }

    private suspend fun synthesizeClip(
        context: Context,
        text: String,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = AiProviderResolver.resolve(context, AiFeature.TTS)
                if (config.canSpeak) AiTtsClient.synthesize(config, text) else null
            }.getOrElse {
                Timber.tag("RadioStarTts").w(it, "dj voice preview synthesis failed")
                null
            }
        }

    fun stop() {
        announcer?.shutdown()
        announcer = null
    }
}
