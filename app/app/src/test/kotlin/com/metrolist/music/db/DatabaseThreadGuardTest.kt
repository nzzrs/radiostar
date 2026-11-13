/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseThreadGuardTest {
    @Test
    fun `successful block does not report a failure`() {
        val reported = mutableListOf<Throwable>()

        guardDatabaseThread(onFailure = { reported += it }) {
            // no-op
        }

        assertTrue(reported.isEmpty())
    }

    @Test
    fun `failing block is reported and swallowed`() {
        val reported = mutableListOf<Throwable>()

        guardDatabaseThread(onFailure = { reported += it }) {
            throw IllegalStateException("boom")
        }

        assertEquals(1, reported.size)
        assertEquals("boom", reported.single().message)
    }

    @Test
    fun `rethrow keeps propagating the original failure`() {
        val reported = mutableListOf<Throwable>()

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                guardDatabaseThread(
                    rethrow = true,
                    onFailure = { reported += it },
                ) {
                    throw IllegalStateException("propagate")
                }
            }

        assertEquals("propagate", thrown.message)
        assertEquals(1, reported.size)
    }

    @Test
    fun `worker thread survives a failing task and keeps running`() {
        val failures = mutableListOf<Throwable>()
        val completed = mutableListOf<Int>()

        // Mirrors a database executor: every task is guarded, so one failure
        // must not end the worker loop.
        fun runTask(index: Int) {
            guardDatabaseThread(onFailure = { failures += it }) {
                if (index == 1) throw RuntimeException("task $index failed")
                completed += index
            }
        }

        listOf(0, 1, 2).forEach(::runTask)

        assertEquals(1, failures.size)
        assertEquals(listOf(0, 2), completed)
        assertNull(failures.single().cause)
    }
}
