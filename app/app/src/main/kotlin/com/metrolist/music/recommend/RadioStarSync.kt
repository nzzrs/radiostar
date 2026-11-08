/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_SERVER
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.constants.RadioStarSyncEnabledKey
import com.metrolist.music.constants.RadioStarSyncLastResultKey
import com.metrolist.music.constants.RadioStarSyncLastSeqKey
import com.metrolist.music.constants.RadioStarSyncLastTimeKey
import com.metrolist.music.constants.RadioStarSyncMirrorScrobblerKey
import com.metrolist.music.constants.RadioStarSyncMirrorYoutubeKey
import com.metrolist.music.constants.RadioStarSyncPassphraseKey
import com.metrolist.music.constants.RadioStarSyncReactionsPushedKey
import com.metrolist.music.constants.RadioStarSyncSaltKey
import com.metrolist.music.constants.RadioStarSyncSnapshotKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Cross-device sync for preferences and track reactions.
 *
 * The server account is the transport and the authority. Config values are
 * end-to-end encrypted when their key name looks secret; the passphrase is
 * stored only in the Android Keystore and never uploaded.
 */
@Singleton
class RadioStarSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val auth: ServerAuthStore,
        private val database: MusicDatabase,
    ) {
        private val client = SyncClient(auth)
        private val reactions = ReactionStore(context)
        private val mutex = Mutex()

        data class Result(
            val ok: Boolean,
            val message: String,
            val needsPassphrase: Boolean = false,
        )

        suspend fun hasPassphrase(): Boolean = withContext(Dispatchers.IO) { storedPassphrase() != null }

        suspend fun setPassphrase(passphrase: String): Result =
            withContext(Dispatchers.IO) {
                if (passphrase.length < 8) {
                    return@withContext Result(false, "Passphrase must be at least 8 characters")
                }
                val server = server()
                val snapshot = SyncConfigCodec.parseSnapshot(prefs()[RadioStarSyncSnapshotKey])
                var resolvedSalt = prefs()[RadioStarSyncSaltKey]?.takeIf { it.isNotBlank() }
                if (resolvedSalt == null) {
                    val remoteSalt =
                        pullAllConfig(server)
                            .firstOrNull { it.key == CRYPTO_KEY }
                            ?.payload
                            ?.optString("salt")
                            ?.takeIf { it.isNotBlank() }
                    if (remoteSalt != null) {
                        resolvedSalt = remoteSalt
                        context.dataStore.edit { it[RadioStarSyncSaltKey] = remoteSalt }
                    }
                }
                if (resolvedSalt == null) {
                    val newSalt = SyncCrypto.newSalt()
                    resolvedSalt = newSalt
                    val newKey = SyncCrypto.deriveKey(passphrase, newSalt)
                    val payload =
                        JSONObject()
                            .put("salt", newSalt)
                            .put("check", SyncCrypto.encrypt(SyncCrypto.checkValue(), newKey))
                    runCatching {
                        client.push(
                            server,
                            listOf(SyncItem("config", CRYPTO_KEY, now(), false, payload)),
                        )
                    }.onFailure {
                        return@withContext Result(false, it.message ?: "Could not create sync key")
                    }
                    context.dataStore.edit { it[RadioStarSyncSaltKey] = newSalt }
                }
                val salt = resolvedSalt ?: return@withContext Result(false, "Could not create sync key")
                val key = SyncCrypto.deriveKey(passphrase, salt)
                val current = pullAllConfig(server).firstOrNull { it.key == CRYPTO_KEY }
                val check = current?.payload?.optString("check").orEmpty()
                if (check.isNotBlank() && SyncCrypto.decrypt(check, key) != SyncCrypto.checkValue()) {
                    return@withContext Result(false, "Wrong passphrase for this account")
                }
                val stored = SecureKeyStore.encrypt(passphrase)
                    ?: return@withContext Result(false, "Could not protect the passphrase")
                context.dataStore.edit {
                    it[RadioStarSyncPassphraseKey] = stored
                    it[RadioStarSyncSaltKey] = salt
                    it[RadioStarSyncLastSeqKey] = 0L
                    it[RadioStarSyncLastTimeKey] = 0L
                    it[RadioStarSyncSnapshotKey] = SyncConfigCodec.encodeSnapshot(snapshot)
                }
                if (prefs()[RadioStarSyncEnabledKey] == true) {
                    val synced = syncNow(force = true)
                    Result(true, synced.message, synced.needsPassphrase)
                } else {
                    Result(true, "Passphrase set")
                }
            }

        suspend fun reschedule() {
            val enabled = prefs()[RadioStarSyncEnabledKey] == true
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<RadioStarSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        suspend fun clearPassphrase() {
            context.dataStore.edit {
                it.remove(RadioStarSyncPassphraseKey)
                it[RadioStarSyncLastSeqKey] = 0L
                it[RadioStarSyncLastTimeKey] = 0L
            }
        }

        /**
         * Syncs when enabled. `force` bypasses the 15-minute debounce used by
         * automatic callers.
         */
        suspend fun syncNow(force: Boolean = false): Result =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val prefs = prefs()
                    if (prefs[RadioStarSyncEnabledKey] != true) {
                        return@withContext Result(false, "Sync is off")
                    }
                    val server = server()
                    if (!auth.status(server).linked) {
                        return@withContext Result(false, "Link this device first")
                    }
                    if (!force) {
                        val last = prefs[RadioStarSyncLastTimeKey] ?: 0L
                        if (System.currentTimeMillis() - last < DEBOUNCE_MS) {
                            return@withContext Result(true, "Recently synced")
                        }
                    }
                    val outcome = runCatching { syncLocked(server) }
                    val result =
                        outcome.fold(
                            onSuccess = { it },
                            onFailure = { error ->
                                Timber.tag(TAG).w(error, "sync failed")
                                Result(false, error.message ?: "Sync failed")
                            },
                        )
                    context.dataStore.edit {
                        if (result.ok) {
                            it[RadioStarSyncLastTimeKey] = System.currentTimeMillis()
                        }
                        it[RadioStarSyncLastResultKey] = result.message
                    }
                    result
                }
            }

        private suspend fun syncLocked(server: String): Result {
            val snapshot = SyncConfigCodec.parseSnapshot(prefs()[RadioStarSyncSnapshotKey])
            val remoteNames = mutableSetOf<String>()
            val firstSync = snapshot.isEmpty() && (prefs()[RadioStarSyncLastTimeKey] ?: 0L) == 0L
            var needsPassphrase = false

            if (firstSync) {
                val pulled = pullAllConfig(server)
                pulled.forEach { item ->
                    remoteNames += item.key
                    val applied = applyConfig(item, snapshot, force = true)
                    needsPassphrase = needsPassphrase || applied == ApplyOutcome.NEEDS_PASSPHRASE
                }
                saveSnapshot(snapshot)
                // The server was empty: seed it from this device. If it had
                // values, keep them and only push local-only keys.
                val local = prefs()
                val seed = mutableListOf<PendingConfig>()
                val passphrase = storedPassphrase()
                local.asMap().forEach { (key, value) ->
                    val name = key.name
                    if (name in remoteNames || SyncConfigCodec.isExcluded(name)) return@forEach
                    val encoded = SyncConfigCodec.encodeValue(value) ?: return@forEach
                    val pending = buildPendingConfig(name, encoded, passphrase)
                    if (pending == null) {
                        needsPassphrase = true
                    } else {
                        seed += pending
                    }
                }
                pushConfigBatch(server, seed, snapshot)
            } else {
                pushLocalConfig(server, snapshot) { needsPassphrase = true }
                pushLocalReactions(server)
            }

            var since = prefs()[RadioStarSyncLastSeqKey] ?: 0L
            var rounds = 0
            while (rounds++ < MAX_PULL_ROUNDS) {
                val pulled = client.pull(server, since)
                val ownDevice = auth.status(server).deviceId
                pulled.items.forEach { item ->
                    when (item.kind) {
                        "config" -> {
                            val outcome = applyConfig(item, snapshot, force = false)
                            if (outcome == ApplyOutcome.NEEDS_PASSPHRASE) needsPassphrase = true
                        }
                        "reaction" -> {
                            if (item.deviceId != ownDevice) applyReaction(item)
                        }
                    }
                }
                saveSnapshot(snapshot)
                since = pulled.nextSince
                context.dataStore.edit { it[RadioStarSyncLastSeqKey] = since }
                if (!pulled.hasMore) break
            }

            runCatching { pullScrobblerReactions() }
                .onFailure { Timber.tag(TAG).w(it, "scrobbler reaction pull failed") }

            return if (needsPassphrase) {
                Result(true, "Non-secret settings synced. Enter the passphrase for secrets.", true)
            } else {
                Result(true, "Synced")
            }
        }

        private data class PendingConfig(
            val name: String,
            val fingerprint: String,
            val payload: JSONObject,
        )

        private suspend fun buildPendingConfig(
            name: String,
            encoded: JSONObject,
            passphrase: String?,
        ): PendingConfig? {
            val payload =
                if (SyncConfigCodec.isSecret(name)) {
                    val secret = passphrase ?: return null
                    val salt = prefs()[RadioStarSyncSaltKey] ?: return null
                    JSONObject().put(
                        "enc",
                        SyncCrypto.encrypt(
                            SyncConfigCodec.canonical(encoded),
                            SyncCrypto.deriveKey(secret, salt),
                        ),
                    )
                } else {
                    encoded
                }
            return PendingConfig(name, SyncConfigCodec.fingerprint(name, encoded), payload)
        }

        private suspend fun pushLocalConfig(
            server: String,
            snapshot: MutableMap<String, String>,
            onSecretSkipped: () -> Unit,
        ) {
            val local = prefs()
            val passphrase = storedPassphrase()
            val pending = mutableListOf<PendingConfig>()
            local.asMap().forEach { (key, value) ->
                val name = key.name
                if (SyncConfigCodec.isExcluded(name) || name == CRYPTO_KEY) return@forEach
                val encoded = SyncConfigCodec.encodeValue(value) ?: return@forEach
                val fingerprint = SyncConfigCodec.fingerprint(name, encoded)
                if (snapshot[name] == fingerprint) return@forEach
                val item = buildPendingConfig(name, encoded, passphrase)
                if (item == null) {
                    if (SyncConfigCodec.isSecret(name)) onSecretSkipped()
                } else {
                    pending += item
                }
            }
            pushConfigBatch(server, pending, snapshot)
        }

        private suspend fun pushConfigBatch(
            server: String,
            pending: List<PendingConfig>,
            snapshot: MutableMap<String, String>,
        ) {
            pending.chunked(MAX_BATCH).forEach { chunk ->
                val items =
                    chunk.map {
                        SyncItem("config", it.name, now(), false, it.payload)
                    }
                runCatching { client.push(server, items) }
                    .onSuccess { result ->
                        val accepted = result.accepted.map { it.key }.toSet()
                        chunk.filter { it.name in accepted }.forEach {
                            snapshot[it.name] = it.fingerprint
                        }
                        result.conflicts.forEach { conflict ->
                            if (conflict.kind == "config") {
                                applyConfig(conflict, snapshot, force = true)
                            }
                        }
                    }
                    .onFailure { Timber.tag(TAG).w(it, "config push failed") }
            }
        }

        private suspend fun pushLocalReactions(server: String) {
            val store = reactions
            val pushed = prefs()[RadioStarSyncReactionsPushedKey] ?: 0L
            val pending = store.pending(pushed)
            if (pending.isEmpty()) return
            pending.chunked(MAX_BATCH).forEach { batch ->
                val items =
                    batch.map { reaction ->
                        SyncItem(
                            kind = "reaction",
                            key = reaction.key,
                            updatedAtMs = reaction.updatedAtMs,
                            deleted = reaction.deleted,
                            payload =
                                JSONObject()
                                    .put("rating", reaction.rating)
                                    .put("videoId", reaction.videoId ?: JSONObject.NULL)
                                    .put("title", reaction.title)
                                    .put("artist", reaction.artist)
                                    .put("album", reaction.album ?: JSONObject.NULL)
                                    .put("duration", reaction.durationSeconds ?: JSONObject.NULL),
                        )
                    }
                runCatching { client.push(server, items) }
                    .onSuccess { result ->
                        if (result.accepted.isNotEmpty()) {
                            val last = batch.maxOf { it.localSeq }
                            context.dataStore.edit { it[RadioStarSyncReactionsPushedKey] = last }
                        }
                        result.conflicts.forEach { applyReaction(it) }
                    }
                    .onFailure { Timber.tag(TAG).w(it, "reaction push failed") }
            }
        }

        private suspend fun pullAllConfig(server: String): List<SyncItem> {
            val items = mutableListOf<SyncItem>()
            var since = 0L
            var rounds = 0
            while (rounds++ < MAX_PULL_ROUNDS) {
                val pulled = client.pull(server, since)
                items += pulled.items.filter { it.kind == "config" }
                since = pulled.nextSince
                if (!pulled.hasMore) break
            }
            return items
        }

        private suspend fun applyConfig(
            item: SyncItem,
            snapshot: MutableMap<String, String>,
            force: Boolean,
        ): ApplyOutcome {
            if (item.key == CRYPTO_KEY) {
                item.payload.optString("salt").takeIf { it.isNotBlank() }?.let { salt ->
                    context.dataStore.edit { it[RadioStarSyncSaltKey] = salt }
                }
                return ApplyOutcome.APPLIED
            }
            if (SyncConfigCodec.isExcluded(item.key)) return ApplyOutcome.SKIPPED
            if (item.deleted) return ApplyOutcome.SKIPPED
            val encoded =
                if (item.payload.has("enc")) {
                    val passphrase = storedPassphrase() ?: return ApplyOutcome.NEEDS_PASSPHRASE
                    val salt = prefs()[RadioStarSyncSaltKey] ?: return ApplyOutcome.NEEDS_PASSPHRASE
                    val plaintext =
                        SyncCrypto.decrypt(
                            item.payload.optString("enc"),
                            SyncCrypto.deriveKey(passphrase, salt),
                        ) ?: return ApplyOutcome.NEEDS_PASSPHRASE
                    runCatching { JSONObject(plaintext) }.getOrNull()
                        ?: return ApplyOutcome.SKIPPED
                } else {
                    item.payload
                }
            val value = SyncConfigCodec.decodeValue(encoded) ?: return ApplyOutcome.SKIPPED
            val current = prefs()
            val currentValue = current.asMap().entries.firstOrNull { it.key.name == item.key }?.value
            val currentEncoded = SyncConfigCodec.encodeValue(currentValue)
            val currentFingerprint =
                currentEncoded?.let { SyncConfigCodec.fingerprint(item.key, it) }
            if (!force && snapshot[item.key] != null && snapshot[item.key] != currentFingerprint) {
                // A local edit not yet pushed wins this round.
                return ApplyOutcome.SKIPPED
            }
            putPreference(item.key, value)
            snapshot[item.key] = SyncConfigCodec.fingerprint(item.key, encoded)
            return ApplyOutcome.APPLIED
        }

        private suspend fun pullScrobblerReactions() {
            val preferences = prefs()
            if (preferences[RadioStarSyncMirrorScrobblerKey] == false) return
            val store = reactions
            ScrobblerReactions.pull(context).forEach { remote ->
                val match = findSong(remote.title, remote.artist)
                val videoId = remote.videoId ?: match?.song?.id
                val key = videoId?.let { "yt:$it" } ?: remote.key
                val known = store.get(key ?: SyncConfigCodec.reactionKey(videoId, remote.title, remote.artist))
                store.applyRemote(
                    videoId = videoId,
                    title = remote.title,
                    artist = remote.artist,
                    album = remote.album,
                    durationSeconds = match?.song?.duration?.takeIf { it > 0 },
                    rating = remote.rating,
                    updatedAtMs = remote.updatedAtMs,
                    key = key,
                )
                // A love that only existed on the scrobbler becomes a local
                // pending change so the RadioStar server learns about it.
                if (known == null) {
                    store.recordLocal(
                        videoId = videoId,
                        title = remote.title,
                        artist = remote.artist,
                        album = remote.album,
                        durationSeconds = match?.song?.duration?.takeIf { it > 0 },
                        rating = remote.rating,
                    )
                }
                applyRatingToSong(videoId, remote.rating)
            }
        }

        private suspend fun findSong(
            title: String,
            artist: String,
        ): com.metrolist.music.db.entities.Song? {
            if (title.isBlank()) return null
            val normalizedTitle = SyncConfigCodec.normalize(title)
            val normalizedArtist = SyncConfigCodec.normalize(SyncConfigCodec.primaryArtist(artist))
            return runCatching {
                database
                    .searchSongs(title, 10)
                    .first()
                    .firstOrNull { song ->
                        SyncConfigCodec.normalize(song.song.title) == normalizedTitle &&
                            song.artists.any {
                                SyncConfigCodec.normalize(SyncConfigCodec.primaryArtist(it.name)) ==
                                    normalizedArtist
                            }
                    }
            }.getOrNull()
        }

        private suspend fun applyReaction(item: SyncItem) {
            val payload = item.payload
            val videoId = payload.optString("videoId").takeIf { it.isNotBlank() && it != "null" }
            val title = payload.optString("title")
            val artist = payload.optString("artist")
            val album = payload.optString("album").takeIf { it.isNotBlank() && it != "null" }
            val duration = if (payload.isNull("duration")) null else payload.optInt("duration")
            val rating = if (item.deleted) 0 else payload.optInt("rating", 0)
            val store = reactions
            store.applyRemote(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = duration,
                rating = rating,
                updatedAtMs = item.updatedAtMs,
                key = item.key,
            )
            applyRatingToSong(videoId, rating)
            mirrorRemoteReaction(videoId, title, artist, album, rating)
        }

        private suspend fun mirrorRemoteReaction(
            videoId: String?,
            title: String,
            artist: String,
            album: String?,
            rating: Int,
        ) {
            val preferences = prefs()
            if (preferences[RadioStarSyncMirrorYoutubeKey] != false && !videoId.isNullOrBlank()) {
                runCatching { YouTube.likeVideo(videoId, rating == 1) }
                    .onFailure { Timber.tag(TAG).w(it, "YouTube reaction mirror failed") }
            }
            if (preferences[RadioStarSyncMirrorScrobblerKey] != false && title.isNotBlank()) {
                runCatching { ScrobblerReactions.push(context, artist, title, album, rating) }
                    .onFailure { Timber.tag(TAG).w(it, "scrobbler reaction mirror failed") }
            }
        }

        private suspend fun applyRatingToSong(videoId: String?, rating: Int) {
            if (videoId.isNullOrBlank()) return
            val entity = database.songEntity(videoId) ?: return
            val liked = rating == 1
            if (entity.liked == liked) return
            database.withTransaction {
                update(
                    entity.copy(
                        liked = liked,
                        likedDate = if (liked) LocalDateTime.now() else null,
                    ),
                )
            }
        }

        private suspend fun putPreference(name: String, value: Any) {
            context.dataStore.edit { preferences ->
                putPreference(preferences, name, value)
            }
        }

        private fun putPreference(preferences: MutablePreferences, name: String, value: Any) {
            when (value) {
                is Boolean -> preferences[booleanPreferencesKey(name)] = value
                is Int -> preferences[intPreferencesKey(name)] = value
                is Long -> preferences[longPreferencesKey(name)] = value
                is Float -> preferences[floatPreferencesKey(name)] = value
                is String -> preferences[stringPreferencesKey(name)] = value
                is Set<*> -> preferences[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
            }
        }

        private suspend fun saveSnapshot(snapshot: Map<String, String>) {
            context.dataStore.edit {
                it[RadioStarSyncSnapshotKey] = SyncConfigCodec.encodeSnapshot(snapshot)
            }
        }

        private suspend fun prefs(): Preferences = context.dataStore.data.first()

        private suspend fun server(): String =
            prefs()[RadioStarServerKey]
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }
                ?: RADIOSTAR_DEFAULT_SERVER

        private suspend fun storedPassphrase(): String? {
            val stored = prefs()[RadioStarSyncPassphraseKey] ?: return null
            return SecureKeyStore.decrypt(stored)
        }

        // -- user actions --------------------------------------------------

        /**
         * Records a like that arrived from YouTube without echoing it back to
         * YouTube or the scrobbler. It is queued for the next server sync.
         */
        suspend fun recordImportedReaction(
            song: SongEntity,
            artist: String,
            rating: Int,
        ) = withContext(Dispatchers.IO) {
            val key = SyncConfigCodec.reactionKey(song.id, song.title, artist)
            if (reactions.get(key)?.rating == rating) return@withContext
            reactions.recordLocal(
                videoId = song.id.takeIf { !song.isLocal && !song.isUploaded },
                title = song.title,
                artist = artist,
                album = song.albumName,
                durationSeconds = song.duration.takeIf { it > 0 },
                rating = rating,
            )
        }

        suspend fun recordReaction(
            song: SongEntity,
            artist: String,
            rating: Int,
        ) = withContext(Dispatchers.IO) {
            val store = reactions
            val reaction =
                store.recordLocal(
                    videoId = song.id.takeIf { !song.isLocal && !song.isUploaded },
                    title = song.title,
                    artist = artist,
                    album = song.albumName,
                    durationSeconds = song.duration.takeIf { it > 0 },
                    rating = rating,
                )
            applyRatingToSong(song.id, rating)
            val preferences = prefs()
            // A like is already pushed by SyncUtils; dislike/clear must also
            // remove the YouTube like when that mirror is enabled.
            if (rating != 1 &&
                preferences[RadioStarSyncMirrorYoutubeKey] != false &&
                !song.isLocal &&
                !song.isUploaded
            ) {
                runCatching { YouTube.likeVideo(song.id, false) }
                    .onFailure { Timber.tag(TAG).w(it, "YouTube reaction mirror failed") }
            }
            if (preferences[RadioStarSyncMirrorScrobblerKey] != false) {
                runCatching {
                    ScrobblerReactions.push(context, artist, song.title, song.albumName, reaction.rating)
                }.onFailure { Timber.tag(TAG).w(it, "scrobbler reaction mirror failed") }
            }
            scope.launch { syncNow(force = false) }
        }

        suspend fun toggleDislike(
            song: SongEntity,
            artist: String,
        ): Int = withContext(Dispatchers.IO) {
            val store = reactions
            val key = SyncConfigCodec.reactionKey(song.id, song.title, artist)
            val current = store.get(key)?.rating ?: 0
            val next = if (current == -1) 0 else -1
            recordReaction(song, artist, next)
            next
        }

        suspend fun ratingFor(
            videoId: String,
            title: String,
            artist: String,
        ): Int =
            withContext(Dispatchers.IO) {
                reactions.get(SyncConfigCodec.reactionKey(videoId, title, artist))?.rating ?: 0
            }

        /** True when the RadioStar server reaction, not YouTube, owns this like. */
        suspend fun likedByReaction(videoId: String): Boolean =
            withContext(Dispatchers.IO) {
                reactions.get("yt:$videoId")?.rating == 1
            }

        companion object {
            private const val TAG = "RadioStarSync"
            private const val WORK_NAME = "radiostar-sync"
            private const val CRYPTO_KEY = "__crypto__"
            private const val MAX_BATCH = 500
            private const val MAX_PULL_ROUNDS = 20
            private const val DEBOUNCE_MS = 15 * 60_000L

            private enum class ApplyOutcome { APPLIED, SKIPPED, NEEDS_PASSPHRASE }

            private fun now(): Long = System.currentTimeMillis()
        }
    }
