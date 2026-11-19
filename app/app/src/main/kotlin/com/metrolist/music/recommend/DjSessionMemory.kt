/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * The lines already spoken in the active DJ session.
 *
 * Kept as a small ring buffer so a long session never grows the prompt
 * without bound; only the most recent lines are offered to the chat model so
 * it can avoid repeating itself.
 */
class DjSessionMemory(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lines = ArrayDeque<String>(capacity.coerceAtLeast(1))

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun add(line: String) {
        val clean = line.replace(WHITESPACE, " ").trim()
        if (clean.isEmpty()) return
        lines.addLast(clean)
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    fun all(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    /** One compact line suitable for a prompt; empty when nothing was said. */
    @Synchronized
    fun render(): String = lines.joinToString(SEPARATOR)

    companion object {
        const val DEFAULT_CAPACITY = 8
        private const val SEPARATOR = " · "
        private val WHITESPACE = Regex("\\s+")
    }
}
