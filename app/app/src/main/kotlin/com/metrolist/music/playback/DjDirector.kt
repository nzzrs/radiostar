/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.extensions.metadata
import com.metrolist.music.recommend.AiTtsClient
import com.metrolist.music.recommend.DjAiCommentator
import com.metrolist.music.recommend.DjPersona
import com.metrolist.music.recommend.DjPlaybackPolicy
import com.metrolist.music.recommend.DjSchedulePolicy
import com.metrolist.music.recommend.DjScript
import com.metrolist.music.recommend.DjSpeechDecision
import com.metrolist.music.recommend.DjSpeechPolicy
import com.metrolist.music.recommend.TelemetryBias
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale

/**
 * Runs the DJ while a session is active: picks the next link, prefetches its
 * text and audio, starts it so the clip ends at the track boundary, and
 * ducks the music only when the voice is actually audible.
 *
 * Session ownership (starting and ending the queue, the active flag) stays in
 * [MusicService]; this class only talks to it through [Host].
 */
class DjDirector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val host: Host,
    private val windowMs: Long = DjSchedulePolicy.DEFAULT_WINDOW_MS,
) {
    interface Host {
        val sessionActive: Boolean
        val sessionId: Int
        val persona: DjPersona?
        val aiEnabled: Boolean

        fun player(): ExoPlayer?

        fun isCrossfading(): Boolean

        fun effectiveVolume(): Float

        fun applyVolume(volume: Float)

        /** Called once the intro finishes and the first track may start. */
        fun startPlaybackAfterIntro()

        fun onSpeakingChanged(speaking: Boolean)
    }

    private val ramp = DjVolumeRamp(handler) { volume -> host.applyVolume(volume) }
    private var announcer: DjAnnouncer? = null
    private var tickerStarted = false
    private var announcedPair: String? = null
    private var holdAtTrackEnd = false
    private var baseVolume = 1f
    private var bias: TelemetryBias = TelemetryBias.EMPTY
    private var lastSpeechIndex: Int = -1
    private var lastSpeechAtMs: Long = 0L
    private var sessionStartedAtMs: Long = 0L
    private var linksSpoken: Int = 0
    private var prepared: PreparedLink? = null
    private var prepareJob: Job? = null
    private var ducked = false

    /** True while the ramp has lowered the track for the voice. */
    val isDucking: Boolean get() = ducked

    fun startTicker() {
        if (tickerStarted) return
        tickerStarted = true
        handler.post(tick)
    }

    private val tick =
        object : Runnable {
            override fun run() {
                maybeAnnounce()
                handler.postDelayed(this, TICK_MS)
            }
        }

    /** Speaks the intro, then starts the first track once the voice is done. */
    fun startSession(
        persona: DjPersona,
        firstTitle: String,
        firstArtist: String,
    ) {
        resetRuntime()
        val token = host.sessionId
        val engine = ensureAnnouncer()
        engine.setVoice(persona.pitch, persona.rate)
        var introFinished = false
        val finishIntro = {
            if (!introFinished) {
                introFinished = true
                handler.post {
                    if (host.sessionActive && host.sessionId == token) {
                        linksSpoken = 1
                        lastSpeechIndex = -1
                        lastSpeechAtMs = SystemClock.elapsedRealtime()
                        host.startPlaybackAfterIntro()
                        onTrackStarted()
                    }
                }
            }
        }
        val speak: (String, ByteArray?) -> Unit = { line, audio ->
            if (host.sessionActive && host.sessionId == token) {
                val spoken =
                    engine.announce(
                        line,
                        "dj:intro:${System.nanoTime()}",
                        audio,
                        DjAnnouncer.Callbacks(
                            onDone = { finishIntro() },
                            onError = { finishIntro() },
                        ),
                    )
                if (spoken) {
                    // Safety net for an engine that never reports back.
                    handler.postDelayed({ finishIntro() }, INTRO_WATCHDOG_MS)
                } else {
                    finishIntro()
                }
            }
        }
        val fallback =
            DjScript.intro(
                persona = persona,
                firstTitle = firstTitle,
                firstArtist = firstArtist,
                language = Locale.getDefault().language,
            )
        scope.launch {
            val line =
                if (host.aiEnabled) {
                    kotlinx.coroutines.withTimeoutOrNull(INTRO_AI_TIMEOUT_MS) {
                        DjAiCommentator.intro(context, persona, firstTitle, firstArtist)
                    } ?: fallback
                } else {
                    fallback
                }
            val audio = withContext(Dispatchers.IO) { DjAnnouncer.synthesize(context, line) }
            handler.post { speak(line, audio) }
        }
    }

    /** Called when the queue or track changed; the next pair is re-planned. */
    fun onTrackStarted() {
        if (!host.sessionActive) return
        val plan = planPair() ?: return
        if (plan.pair == prepared?.pair) return
        prepareJob?.cancel()
        prepareJob = null
        prepared = null
        if (!plan.decision.speak) {
            announcedPair = plan.pair
            Timber.tag(TAG).i("quiet pair=%s tracks=%d", plan.pair, plan.tracksSince)
            return
        }
        prefetch(plan)
    }

    /** Drops the cached clip, e.g. when the queue is replaced. */
    fun onQueueChanged() {
        prepareJob?.cancel()
        prepareJob = null
        prepared = null
        announcedPair = null
    }

    fun updateBias(loaded: TelemetryBias) {
        handler.post {
            if (host.sessionActive) bias = loaded
        }
    }

    /** Cancels the volume ramp so a crossfade owns the mix. */
    fun onCrossfadeStarted() {
        ramp.cancel()
    }

    fun endSession() {
        prepareJob?.cancel()
        prepareJob = null
        prepared = null
        announcedPair = null
        announcer?.shutdown()
        announcer = null
        ramp.cancel()
        if (holdAtTrackEnd) {
            holdAtTrackEnd = false
            host.player()?.setPauseAtEndOfMediaItems(false)
        }
        bias = TelemetryBias.EMPTY
        lastSpeechIndex = -1
        lastSpeechAtMs = 0L
        sessionStartedAtMs = 0L
        linksSpoken = 0
        if (!host.isCrossfading()) host.applyVolume(host.effectiveVolume())
        Timber.tag(TAG).i("end session")
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        endSession()
    }

    private fun resetRuntime() {
        bias = TelemetryBias.EMPTY
        lastSpeechIndex = -1
        lastSpeechAtMs = SystemClock.elapsedRealtime()
        sessionStartedAtMs = lastSpeechAtMs
        linksSpoken = 0
        announcedPair = null
        prepared = null
        prepareJob?.cancel()
        prepareJob = null
        holdAtTrackEnd = false
        ramp.cancel()
    }

    private fun ensureAnnouncer(): DjAnnouncer =
        announcer
            ?: DjAnnouncer(context) { ducked ->
                handler.post { applyDuck(ducked) }
            }.also {
                announcer = it
                it.prepare()
            }

    private fun maybeAnnounce() {
        if (!host.sessionActive) return
        val player = host.player() ?: return
        if (!player.playWhenReady || player.playbackState != Player.STATE_READY) return
        val duration = player.duration
        if (duration <= 0L) return
        val plan = planPair() ?: return
        if (plan.pair == announcedPair) return
        val link = prepared?.takeIf { it.pair == plan.pair }
        val remaining = duration - player.currentPosition
        val decision =
            DjSchedulePolicy.decide(
                remainingMs = remaining,
                clipDurationMs = link?.clipDurationMs ?: 0L,
                windowMs = windowMs,
                fadeMs = DjVolumeRamp.DURATION_MS,
                clipReady = link?.ready == true,
                tickMs = TICK_MS,
            )
        if (!decision.start) {
            if (remaining <= DjSchedulePolicy.MIN_LEAD_MS && link?.ready != true) {
                // The window closed before the clip was ready: skip this link
                // instead of ducking late or holding the next track.
                announcedPair = plan.pair
                Timber.tag(TAG).i(
                    "skip pair=%s reason=%s remainingMs=%d",
                    plan.pair,
                    decision.reason,
                    remaining,
                )
            }
            return
        }
        val preparedLink = link ?: return
        if (!plan.decision.speak) {
            announcedPair = plan.pair
            return
        }
        speak(plan, preparedLink, SystemClock.elapsedRealtime())
    }

    private fun speak(
        plan: DjPairPlan,
        link: PreparedLink,
        scheduledAt: Long,
    ) {
        val engine = ensureAnnouncer()
        announcedPair = plan.pair
        val token = host.sessionId
        val announcedAt = SystemClock.elapsedRealtime()
        val callbacks =
            DjAnnouncer.Callbacks(
                onStart = {
                    handler.post {
                        if (host.sessionId == token) {
                            val now = SystemClock.elapsedRealtime()
                            Timber.tag(TAG).i(
                                "timing pair=%s clipMs=%d queueMs=%d chatMs=%d ttsMs=%d playbackStartMs=%d totalMs=%d",
                                plan.pair,
                                engine.currentClipDurationMs().takeIf { it > 0L } ?: link.clipDurationMs,
                                announcedAt - scheduledAt,
                                link.chatMs,
                                link.ttsMs,
                                now - announcedAt,
                                now - link.startedAtMs,
                            )
                        }
                    }
                },
                onDone = {
                    handler.post {
                        if (host.sessionActive && host.sessionId == token) {
                            lastSpeechIndex = plan.index
                            lastSpeechAtMs = SystemClock.elapsedRealtime()
                            linksSpoken += 1
                            Timber.tag(TAG).i(
                                "announce reason=%s fact=%s prepared=%s pair=%s text=%s",
                                plan.decision.reason,
                                plan.decision.useFact,
                                link.ready,
                                plan.pair,
                                link.text,
                            )
                        }
                        releaseHold()
                    }
                },
                onError = {
                    handler.post {
                        releaseHold()
                        Timber.tag(TAG).w("link failed pair=%s", plan.pair)
                    }
                },
            )
        val started =
            engine.announce(
                text = link.text,
                utteranceId = "dj:${plan.pair}",
                audio = link.audio,
                callbacks = callbacks,
            )
        if (!started) {
            releaseHold()
            Timber.tag(TAG).w("link not started pair=%s", plan.pair)
        }
    }

    private fun prefetch(plan: DjPairPlan) {
        val token = host.sessionId
        val startedAt = SystemClock.elapsedRealtime()
        prepared =
            PreparedLink(
                pair = plan.pair,
                text = plan.fallback,
                audio = null,
                clipDurationMs = 0L,
                ready = false,
                chatMs = 0L,
                ttsMs = 0L,
                startedAtMs = startedAt,
            )
        val previous =
            listOfNotNull(
                plan.current.mediaMetadata.title?.toString(),
                plan.current.mediaMetadata.artist?.toString(),
            ).joinToString(" - ")
        prepareJob?.cancel()
        prepareJob =
            scope.launch {
                val chatStart = SystemClock.elapsedRealtime()
                val ai =
                    if (host.aiEnabled) {
                        withTimeoutOrNull(CHAT_TIMEOUT_MS) {
                            DjAiCommentator.comment(
                                context = context,
                                persona = host.persona ?: DjPersona.CHERRY,
                                previous = previous,
                                nextTitle = plan.nextTitle,
                                nextArtist = plan.nextArtist,
                                fact = plan.fact,
                            )
                        }
                    } else {
                        null
                    }
                val chatMs = SystemClock.elapsedRealtime() - chatStart
                val text = ai?.takeIf { it.isNotBlank() } ?: plan.fallback
                val ttsStart = SystemClock.elapsedRealtime()
                val audio = prepareAudio(text)
                val ttsMs = SystemClock.elapsedRealtime() - ttsStart
                val link =
                    when (audio) {
                        is AudioPreparation.Clip ->
                            PreparedLink(
                                pair = plan.pair,
                                text = text,
                                audio = audio.audio,
                                clipDurationMs = audio.durationMs,
                                ready = true,
                                chatMs = chatMs,
                                ttsMs = ttsMs,
                                startedAtMs = startedAt,
                            )
                        is AudioPreparation.PhoneTts ->
                            PreparedLink(
                                pair = plan.pair,
                                text = text,
                                audio = null,
                                clipDurationMs = audio.durationMs,
                                ready = true,
                                chatMs = chatMs,
                                ttsMs = ttsMs,
                                startedAtMs = startedAt,
                            )
                        AudioPreparation.Failed ->
                            PreparedLink(
                                pair = plan.pair,
                                text = text,
                                audio = null,
                                clipDurationMs = 0L,
                                ready = false,
                                chatMs = chatMs,
                                ttsMs = ttsMs,
                                startedAtMs = startedAt,
                            )
                    }
                handler.post {
                    if (!host.sessionActive || host.sessionId != token) return@post
                    if (prepared?.pair != plan.pair || announcedPair == plan.pair) return@post
                    prepared = link
                    Timber.tag(TAG).i(
                        "prepared pair=%s ready=%s clipMs=%d chatMs=%d ttsMs=%d phoneTts=%s",
                        plan.pair,
                        link.ready,
                        link.clipDurationMs,
                        link.chatMs,
                        link.ttsMs,
                        link.audio == null,
                    )
                }
            }
    }

    private suspend fun prepareAudio(text: String): AudioPreparation {
        val config = withContext(Dispatchers.IO) { DjAnnouncer.resolveTtsConfig(context) }
        if (config?.canSpeak != true) {
            return AudioPreparation.PhoneTts(DjSchedulePolicy.estimateSpeechDurationMs(text))
        }
        val audio = AiTtsClient.synthesize(config, text)
        if (audio == null || audio.isEmpty()) return AudioPreparation.Failed
        val duration =
            withContext(Dispatchers.IO) { DjAnnouncer.measureClipDurationMs(context, audio) }
                .takeIf { it > 0L }
                ?: DjSchedulePolicy.estimateSpeechDurationMs(text)
        return AudioPreparation.Clip(audio, duration)
    }

    private fun applyDuck(ducked: Boolean) {
        if (ducked) {
            if (!host.sessionActive || host.isCrossfading()) return
            val player = host.player() ?: return
            this.ducked = true
            baseVolume = player.volume
            val target = DjPlaybackPolicy.duckedVolume(baseVolume)
            host.onSpeakingChanged(true)
            beginHold()
            ramp.rampTo(baseVolume, target)
            Timber.tag(TAG).i("duck on volume=%.2f", target)
        } else {
            this.ducked = false
            val current = host.player()?.volume ?: baseVolume
            if (host.isCrossfading()) {
                ramp.cancel()
            } else {
                ramp.rampTo(current, host.effectiveVolume())
            }
            host.onSpeakingChanged(false)
            releaseHold()
            Timber.tag(TAG).i("duck off volume=%.2f", host.effectiveVolume())
        }
    }

    private fun beginHold() {
        if (holdAtTrackEnd) return
        val player = host.player() ?: return
        if (!player.playWhenReady) return
        holdAtTrackEnd = true
        player.setPauseAtEndOfMediaItems(true)
    }

    private fun releaseHold() {
        if (!holdAtTrackEnd) return
        holdAtTrackEnd = false
        val player = host.player() ?: return
        player.setPauseAtEndOfMediaItems(false)
        if (!host.sessionActive || player.isPlaying) return
        if (DjPlaybackPolicy.isAtTrackEnd(player.currentPosition, player.duration)) {
            player.play()
        }
    }

    private fun planPair(): DjPairPlan? {
        val player = host.player() ?: return null
        val index = player.currentMediaItemIndex
        if (index < 0 || index + 1 >= player.mediaItemCount) return null
        val current = player.currentMediaItem ?: return null
        if (current.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE) return null
        val next = player.getMediaItemAt(index + 1)
        val nextTitle = next.mediaMetadata.title?.toString().orEmpty()
        val nextArtist = next.mediaMetadata.artist?.toString().orEmpty()
        val currentArtist = current.mediaMetadata.artist?.toString().orEmpty()
        val tracksSince = (index - lastSpeechIndex).coerceAtLeast(1)
        val roll = (Math.floorMod(host.sessionId * 37 + index * 17, 100)).toDouble() / 100.0
        val nextBias =
            bias.score(
                songId = next.mediaId,
                artists =
                    next.metadata?.artists?.map { it.name }
                        ?: listOfNotNull(nextArtist.takeIf { it.isNotBlank() }),
            )
        val decision =
            DjSpeechPolicy.decide(
                tracksSinceLastSpeech = tracksSince,
                elapsedSinceLastSpeechMs = SystemClock.elapsedRealtime() - lastSpeechAtMs,
                nextTrackBias = nextBias,
                nextArtistChanged = !nextArtist.equals(currentArtist, ignoreCase = true),
                linksSpoken = linksSpoken,
                roll = roll,
            )
        val text =
            DjScript.announcement(
                title = nextTitle,
                artist = nextArtist,
                language = Locale.getDefault().language,
            )
        if (text.isEmpty()) return null
        val fact =
            if (decision.useFact) {
                val artists =
                    (0..minOf(index + 1, player.mediaItemCount - 1))
                        .mapNotNull { position ->
                            player.getMediaItemAt(position).mediaMetadata.artist?.toString()
                                ?.takeIf { it.isNotBlank() }
                        }.toSet().size
                DjScript.observation(
                    persona = host.persona ?: DjPersona.CHERRY,
                    trackNumber = index + 1,
                    elapsedMinutes = (SystemClock.elapsedRealtime() - sessionStartedAtMs) / 60_000L,
                    distinctArtists = artists,
                    language = Locale.getDefault().language,
                )
            } else {
                null
            }
        val fallback = if (fact.isNullOrBlank()) text else "$text $fact".take(260)
        return DjPairPlan(
            index = index,
            current = current,
            next = next,
            pair = "${current.mediaId}>${next.mediaId}",
            nextTitle = nextTitle,
            nextArtist = nextArtist,
            tracksSince = tracksSince,
            decision = decision,
            fact = fact,
            fallback = fallback,
        )
    }

    private data class DjPairPlan(
        val index: Int,
        val current: MediaItem,
        val next: MediaItem,
        val pair: String,
        val nextTitle: String,
        val nextArtist: String,
        val tracksSince: Int,
        val decision: DjSpeechDecision,
        val fact: String?,
        val fallback: String,
    )

    private data class PreparedLink(
        val pair: String,
        val text: String,
        val audio: ByteArray?,
        val clipDurationMs: Long,
        val ready: Boolean,
        val chatMs: Long,
        val ttsMs: Long,
        val startedAtMs: Long,
    )

    private sealed interface AudioPreparation {
        data class Clip(
            val audio: ByteArray,
            val durationMs: Long,
        ) : AudioPreparation

        data class PhoneTts(
            val durationMs: Long,
        ) : AudioPreparation

        data object Failed : AudioPreparation
    }

    private companion object {
        const val TAG = "RadioStarDj"
        const val TICK_MS = 2_000L
        const val CHAT_TIMEOUT_MS = 25_000L
        const val INTRO_AI_TIMEOUT_MS = 5_000L
        const val INTRO_WATCHDOG_MS = 20_000L
    }
}
