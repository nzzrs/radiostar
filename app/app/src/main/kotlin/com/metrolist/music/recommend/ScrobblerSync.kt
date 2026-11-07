/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ScrobblerApiKeyKey
import com.metrolist.music.constants.ScrobblerBaseUrlKey
import com.metrolist.music.constants.ScrobblerLastFmUserKey
import com.metrolist.music.constants.ScrobblerLastSyncKey
import com.metrolist.music.constants.ScrobblerListenBrainzUserKey
import com.metrolist.music.constants.ScrobblerServiceKey
import com.metrolist.music.constants.ScrobblerSyncFrequencyKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class ScrobblerSyncFrequency(
    val id: String,
    val label: String,
    val intervalMs: Long,
) {
    STARTUP("startup", "At startup", 0L),
    FIFTEEN_MINUTES("15m", "Every 15 minutes", 15 * 60_000L),
    HOUR("1h", "Every hour", 60 * 60_000L),
    SIX_HOURS("6h", "Every 6 hours", 6 * 60 * 60_000L),
    DAY("1d", "Every day", 24 * 60 * 60_000L),
    WEEK("1w", "Every week", 7 * 24 * 60 * 60_000L),
    OFF("off", "Off", -1L),
}

object ScrobblerSync {
    suspend fun syncIfDue(context: Context) =
        withContext(Dispatchers.IO) {
            val prefs = context.dataStore.data.first()
            val service =
                ScrobblerService.values().firstOrNull { it.id == prefs[ScrobblerServiceKey] }
                    ?: return@withContext
            if (service == ScrobblerService.NONE) return@withContext
            val frequency =
                ScrobblerSyncFrequency.values().firstOrNull { it.id == prefs[ScrobblerSyncFrequencyKey] }
                    ?: ScrobblerSyncFrequency.SIX_HOURS
            if (frequency == ScrobblerSyncFrequency.OFF) return@withContext
            val last = prefs[ScrobblerLastSyncKey] ?: 0L
            val now = System.currentTimeMillis()
            if (frequency.intervalMs > 0L && now - last < frequency.intervalMs) return@withContext
            val config =
                ScrobblerConfig(
                    service = service,
                    lastFmUser = prefs[ScrobblerLastFmUserKey].orEmpty(),
                    listenBrainzUser = prefs[ScrobblerListenBrainzUserKey].orEmpty(),
                    baseUrl = prefs[ScrobblerBaseUrlKey].orEmpty(),
                    token = prefs[ScrobblerTokenKey].orEmpty(),
                    apiKey = prefs[ScrobblerApiKeyKey].orEmpty(),
                )
            val result = ScrobblerImporter.import(context, config)
            if (result.complete) {
                context.dataStore.edit { it[ScrobblerLastSyncKey] = now }
                RadioStarRefreshBus.request()
            }
        }
}
