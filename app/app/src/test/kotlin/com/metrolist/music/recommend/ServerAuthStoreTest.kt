package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAuthStoreTest {
    @Test
    fun normalizesOrigin() {
        assertEquals(
            "https://embed.example.com",
            ServerAuthStore.normalizeServer("  https://Embed.Example.com/  "),
        )
        assertEquals(
            "https://embed.example.com:8443",
            ServerAuthStore.normalizeServer("https://embed.example.com:8443"),
        )
    }

    @Test
    fun rejectsPathsQueriesAndCredentials() {
        assertNull(ServerAuthStore.normalizeServer("https://embed.example.com/api"))
        assertNull(ServerAuthStore.normalizeServer("https://embed.example.com?token=x"))
        assertNull(ServerAuthStore.normalizeServer("https://user:pass@embed.example.com"))
        assertNull(ServerAuthStore.normalizeServer("ftp://embed.example.com"))
        assertNull(ServerAuthStore.normalizeServer(""))
    }
}
