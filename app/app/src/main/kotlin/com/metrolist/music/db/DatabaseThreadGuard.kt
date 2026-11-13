/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

/**
 * Runs a database executor task so an unexpected exception cannot reach the
 * thread's uncaught-exception handler and kill the process.
 *
 * [block] failures are reported to [onFailure]; pass [rethrow] = true when the
 * caller must keep propagating the failure (e.g. a suspending transaction).
 */
internal inline fun guardDatabaseThread(
    rethrow: Boolean = false,
    onFailure: (Throwable) -> Unit = {},
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: Exception) {
        onFailure(e)
        if (rethrow) throw e
    }
}
