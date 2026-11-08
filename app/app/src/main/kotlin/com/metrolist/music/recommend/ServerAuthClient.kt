/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Small HTTP client for the RadioStar auth endpoints. Tokens never appear in a
 * URL, a log line or a query string.
 */
class ServerAuthClient(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
) {
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val deviceId: String,
        val deviceName: String,
        val username: String,
    )

    data class PairingCode(
        val code: String,
        val expiresIn: Long,
    )

    class AuthException(
        message: String,
        val unauthorized: Boolean = false,
        val network: Boolean = false,
    ) : Exception(message)

    fun login(
        server: String,
        username: String,
        password: String,
        deviceName: String,
    ): Session {
        val body =
            JSONObject()
                .put("username", username)
                .put("password", password)
                .put("device_name", deviceName)
        return parseSession(post("$server/auth/login", body))
    }

    fun pair(
        server: String,
        code: String,
        deviceName: String,
    ): Session {
        val body = JSONObject().put("code", code).put("device_name", deviceName)
        return parseSession(post("$server/auth/pair", body))
    }

    fun refresh(
        server: String,
        refreshToken: String,
    ): Session {
        val body = JSONObject().put("refresh_token", refreshToken)
        return parseSession(post("$server/auth/refresh", body))
    }

    fun logout(server: String, accessToken: String) {
        val request =
            Request.Builder()
                .url("$server/auth/logout")
                .header("Authorization", "Bearer $accessToken")
                .post(JSONObject().toString().toRequestBody(JSON))
                .build()
        execute(request)
    }

    fun createPairingCode(server: String, accessToken: String): PairingCode {
        val request =
            Request.Builder()
                .url("$server/auth/pairing-code")
                .header("Authorization", "Bearer $accessToken")
                .post(JSONObject().toString().toRequestBody(JSON))
                .build()
        val json = execute(request)
        return PairingCode(
            code = json.optString("code"),
            expiresIn = json.optLong("expires_in", 0L),
        )
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val request =
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON))
                .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val message =
                        runCatching { JSONObject(text).optString("error") }
                            .getOrDefault("")
                            .ifBlank { "HTTP ${response.code}" }
                    throw AuthException(
                        message = message,
                        unauthorized = response.code == 401,
                    )
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (error: AuthException) {
            throw error
        } catch (error: IOException) {
            throw AuthException("network error", network = true)
        } catch (error: Exception) {
            throw AuthException("invalid auth response")
        }
    }

    private fun parseSession(json: JSONObject): Session {
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) {
            throw AuthException("invalid auth response")
        }
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresIn = json.optLong("expires_in", 900L),
            deviceId = json.optString("device_id"),
            deviceName = json.optString("device_name"),
            username = json.optString("username"),
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
