package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    @Test
    fun parsesRadioStarReleaseArtifact() {
        val response =
            """
            {
              "tag_name": "v1.2.3",
              "body": null,
              "published_at": "2026-09-05T12:00:00Z",
              "assets": [{
                "name": "RadioStar-v1.2.3.apk",
                "browser_download_url": "https://example.com/RadioStar-v1.2.3.apk",
                "size": 42
              }]
            }
            """.trimIndent()
        val release = Updater.parseRelease(response)

        assertEquals("1.2.3", release.versionName)
        assertEquals("", release.description)
        assertEquals("https://example.com/RadioStar-v1.2.3.apk", release.assets.single().downloadUrl)
        assertEquals("universal", release.assets.single().architecture)
        assertEquals("foss", release.assets.single().variant)
    }

    @Test
    fun ignoresForeignAndDebugAssets() {
        val response =
            """
            {
              "tag_name": "v1.2.3",
              "body": "",
              "published_at": "2026-09-05T12:00:00Z",
              "assets": [
                {
                  "name": "RadioStar-debug.apk",
                  "browser_download_url": "https://example.com/debug.apk",
                  "size": 1
                },
                {
                  "name": "Metrolist.apk",
                  "browser_download_url": "https://example.com/Metrolist.apk",
                  "size": 1
                }
              ]
            }
            """.trimIndent()
        val release = Updater.parseRelease(response)

        assertTrue(release.assets.isEmpty())
    }
}
