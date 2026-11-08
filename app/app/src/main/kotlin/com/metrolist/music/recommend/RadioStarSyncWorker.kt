/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RadioStarSyncEntryPoint {
    fun radioStarSync(): RadioStarSync
}

class RadioStarSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync =
            EntryPointAccessors.fromApplication(
                applicationContext,
                RadioStarSyncEntryPoint::class.java,
            ).radioStarSync()
        sync.syncNow(force = true)
        return Result.success()
    }
}
