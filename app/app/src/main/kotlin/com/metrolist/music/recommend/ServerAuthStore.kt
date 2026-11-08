/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.music.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.authDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "radiostar_auth")

/**
 * Device session for the optional embedding server.
 *
 * The refresh token is encrypted with Android Keystore before it reaches
 * DataStore; the access token stays in memory only. A server URL change makes
 * the old session unusable instead of leaking a token to another host.
 */
@Singleton
class ServerAuthStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        data class Status(
            val linked: Boolean,
            val username: String?,
            val deviceName: String?,
        )

        private val mutex = Mutex()
        private val client = ServerAuthClient()

        @Volatile private var accessToken: String? = null

        @Volatile private var accessExpiresAt: Long = 0L

        @Volatile private var accessServer: String? = null

        suspend fun status(server: String): Status =
            withContext(Dispatchers.IO) {
                val normalized = normalizeServer(server) ?: return@withContext Status(false, null, null)
                val prefs = context.authDataStore.data.first()
                val linked =
                    prefs[KEY_SERVER] == normalized && !prefs[KEY_REFRESH].isNullOrBlank()
                Status(
                    linked = linked,
                    username = prefs[KEY_USERNAME].takeIf { linked },
                    deviceName = prefs[KEY_DEVICE_NAME].takeIf { linked },
                )
            }

        suspend fun login(
            server: String,
            username: String,
            password: String,
            deviceName: String,
        ): Result<ServerAuthClient.Session> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val normalized = normalizeServer(server) ?: error("Invalid server URL")
                    val session = client.login(normalized, username, password, deviceName)
                    saveSession(normalized, session)
                    session
                }
            }

        suspend fun pair(
            server: String,
            code: String,
            deviceName: String,
        ): Result<ServerAuthClient.Session> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val normalized = normalizeServer(server) ?: error("Invalid server URL")
                    val session = client.pair(normalized, code, deviceName)
                    saveSession(normalized, session)
                    session
                }
            }

        suspend fun accessToken(server: String): String? =
            withContext(Dispatchers.IO) {
                val normalized = normalizeServer(server) ?: return@withContext null
                val now = System.currentTimeMillis()
                val current = accessToken
                if (current != null && accessServer == normalized && accessExpiresAt - now > 60_000L) {
                    return@withContext current
                }
                mutex.withLock {
                    val refreshedNow = System.currentTimeMillis()
                    val refreshed = accessToken
                    if (
                        refreshed != null &&
                        accessServer == normalized &&
                        accessExpiresAt - refreshedNow > 60_000L
                    ) {
                        refreshed
                    } else {
                        refreshLocked(normalized)
                    }
                }
            }

        /** Called after a 401; the next accessToken() will refresh once. */
        fun invalidateAccess() {
            accessToken = null
            accessExpiresAt = 0L
            accessServer = null
        }

        suspend fun logout(server: String) {
            withContext(Dispatchers.IO) {
                val normalized = normalizeServer(server)
                val token = normalized?.let { accessToken(it) }
                if (normalized != null && token != null) {
                    runCatching { client.logout(normalized, token) }
                }
                clearLocal()
            }
        }

        suspend fun createPairingCode(server: String): Result<ServerAuthClient.PairingCode> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val normalized = normalizeServer(server) ?: error("Invalid server URL")
                    val token = accessToken(normalized) ?: error("Not linked")
                    client.createPairingCode(normalized, token)
                }
            }

        suspend fun clearLocal() {
            withContext(Dispatchers.IO) {
                accessToken = null
                accessExpiresAt = 0L
                accessServer = null
                context.authDataStore.edit { it.clear() }
            }
        }

        private suspend fun refreshLocked(normalized: String): String? {
            val prefs = context.authDataStore.data.first()
            if (prefs[KEY_SERVER] != normalized) return null
            val cipher = prefs[KEY_REFRESH] ?: return null
            val refreshToken = SecureKeyStore.decrypt(cipher) ?: run {
                clearLocal()
                return null
            }
            return try {
                val session = client.refresh(normalized, refreshToken)
                saveSession(normalized, session)
                session.accessToken
            } catch (error: ServerAuthClient.AuthException) {
                if (error.unauthorized) clearLocal()
                null
            }
        }

        private suspend fun saveSession(
            server: String,
            session: ServerAuthClient.Session,
        ) {
            val cipher = SecureKeyStore.encrypt(session.refreshToken)
                ?: error("Could not protect the session token")
            context.authDataStore.edit {
                it[KEY_SERVER] = server
                it[KEY_REFRESH] = cipher
                it[KEY_USERNAME] = session.username
                it[KEY_DEVICE_ID] = session.deviceId
                it[KEY_DEVICE_NAME] = session.deviceName
            }
            accessToken = session.accessToken
            accessExpiresAt = System.currentTimeMillis() + session.expiresIn * 1000L
            accessServer = server
        }

        companion object {
            private val KEY_SERVER = stringPreferencesKey("server")
            private val KEY_REFRESH = stringPreferencesKey("refreshCipher")
            private val KEY_USERNAME = stringPreferencesKey("username")
            private val KEY_DEVICE_ID = stringPreferencesKey("deviceId")
            private val KEY_DEVICE_NAME = stringPreferencesKey("deviceName")

            /**
             * Normalizes a server URL and rejects anything that is not a plain
             * HTTP(S) origin. Release builds require HTTPS.
             */
            fun normalizeServer(raw: String): String? {
                val trimmed = raw.trim().trimEnd('/')
                if (trimmed.isEmpty()) return null
                return runCatching {
                    val uri = URI(trimmed)
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != "https" && scheme != "http") return null
                    if (!BuildConfig.DEBUG && scheme != "https") return null
                    if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
                    if (uri.query != null || uri.fragment != null) return null
                    if (uri.path?.isNotEmpty() == true) return null
                    val port = if (uri.port == -1) "" else ":${uri.port}"
                    "$scheme://${uri.host.lowercase()}$port"
                }.getOrNull()
            }
        }
    }
