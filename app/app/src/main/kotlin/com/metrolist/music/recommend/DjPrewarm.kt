/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Warms the pieces the DJ needs when a session starts, while the panel is
 * already open: the TTS provider config and endpoint, and the local context
 * cache. It never synthesizes speech, so nothing is billed or held in memory.
 */
object DjPrewarm {
    suspend fun warm(context: Context) =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = AiProviderResolver.resolve(context, AiFeature.TTS)
                AiTtsClient.prewarm(config)
            }.onFailure { Timber.tag(TAG).d(it, "tts prewarm skipped") }
            runCatching { DjContextProvider.summary(context) }
                .onFailure { Timber.tag(TAG).d(it, "context prewarm skipped") }
        }

    private const val TAG = "RadioStarDj"
}
