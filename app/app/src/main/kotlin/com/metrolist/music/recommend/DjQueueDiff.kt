/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * One queue slot copied out of the player, so the comparison stays pure and
 * unit-tested instead of reaching into the media3 timeline.
 */
data class DjQueueItem(
    val id: String,
    val title: String = "",
    val artist: String = "",
)

/** What the listener added while the DJ owned the queue order. */
data class DjQueueChange(
    val appended: List<DjQueueItem> = emptyList(),
    val insertedNext: List<DjQueueItem> = emptyList(),
    val replaced: Boolean = false,
) {
    val isUserRequest: Boolean get() = appended.isNotEmpty() || insertedNext.isNotEmpty()

    /** The added item the DJ can acknowledge soonest. */
    val nextRequest: DjQueueItem? get() = (insertedNext + appended).firstOrNull()

    companion object {
        val EMPTY = DjQueueChange()
    }
}

/**
 * Compares the queue before and after a user edit. Playback itself never
 * removes or moves items, so only counts per media id are compared: removals
 * and reorders produce no request, while a repeated id only counts when the
 * user added one more occurrence than before.
 */
object DjQueueDiff {
    fun diff(
        baseline: List<DjQueueItem>?,
        queue: List<DjQueueItem>,
        currentIndex: Int,
    ): DjQueueChange {
        // No baseline means the session just started and owns the whole queue.
        if (baseline == null || baseline.isEmpty()) return DjQueueChange.EMPTY
        if (queue.isEmpty()) return DjQueueChange.EMPTY

        val previousCounts = baseline.groupingBy { it.id }.eachCount()
        val seen = HashMap<String, Int>(queue.size)
        val added = mutableListOf<Pair<Int, DjQueueItem>>()
        val isNew = BooleanArray(queue.size)
        queue.forEachIndexed { index, item ->
            val count = (seen[item.id] ?: 0) + 1
            seen[item.id] = count
            if (count > (previousCounts[item.id] ?: 0)) {
                added += index to item
                isNew[index] = true
            }
        }
        if (added.isEmpty()) return DjQueueChange.EMPTY

        if (added.size == queue.size) {
            // Nothing from the previous queue survived: a new session replaced
            // it, which is not a listener request.
            return DjQueueChange(replaced = true)
        }

        // Items added before the next surviving track were pushed right after
        // playback (play next); anything past it is a later append.
        var boundary = queue.size
        for (index in (currentIndex + 1) until queue.size) {
            if (!isNew[index]) {
                boundary = index
                break
            }
        }
        val nextBlock = (currentIndex + 1) until boundary
        val insertedNext = added.filter { it.first in nextBlock }.map { it.second }
        val appended = added.filter { it.first !in nextBlock }.map { it.second }
        return DjQueueChange(appended = appended, insertedNext = insertedNext)
    }
}
