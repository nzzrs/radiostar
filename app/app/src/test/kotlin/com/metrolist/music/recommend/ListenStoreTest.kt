package com.metrolist.music.recommend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListenStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `clear removes only the selected service`() {
        val store = ListenStore(context)
        store.clear(ScrobblerService.LISTENBRAINZ.id)
        store.clear(ScrobblerService.LASTFM.id)
        store.append(
            listOf(
                listen(ScrobblerService.LISTENBRAINZ.id, "Song A", 1_700_000_000L),
                listen(ScrobblerService.LASTFM.id, "Song B", 1_700_000_100L),
            ),
        )

        store.clear(ScrobblerService.LISTENBRAINZ.id)

        assertEquals(0, store.count(ScrobblerService.LISTENBRAINZ.id))
        assertEquals(1, store.count(ScrobblerService.LASTFM.id))
    }

    private fun listen(service: String, title: String, timestamp: Long) =
        ImportedListen(
            service = service,
            title = title,
            artist = "Artist",
            album = null,
            listenedAt = timestamp,
        )
}
