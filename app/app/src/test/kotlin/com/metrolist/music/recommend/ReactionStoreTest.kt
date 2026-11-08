package com.metrolist.music.recommend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReactionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `local reactions are pending and deduplicated`() {
        val store = ReactionStore(context).apply { clear() }
        store.recordLocal("video", "Song", "Artist", "Album", 180, 1)
        store.recordLocal("video", "Song", "Artist", "Album", 180, -1)

        val current = store.load()
        assertEquals(1, current.size)
        assertEquals(-1, current.first().rating)
        assertEquals(1, store.pending(0).size)
        assertTrue(store.pending(2).isEmpty())
    }

    @Test
    fun `remote reactions do not become pending`() {
        val store = ReactionStore(context).apply { clear() }
        store.applyRemote("video", "Song", "Artist", null, 180, 1, 1_000L)
        assertEquals(1, store.load().first().rating)
        assertTrue(store.pending(0).isEmpty())
    }

    @Test
    fun `an older remote reaction does not overwrite a local one`() {
        val store = ReactionStore(context).apply { clear() }
        store.recordLocal("video", "Song", "Artist", null, 180, -1)
        store.applyRemote("video", "Song", "Artist", null, 180, 1, 1L)
        assertEquals(-1, store.get("yt:video")?.rating)
    }
}
