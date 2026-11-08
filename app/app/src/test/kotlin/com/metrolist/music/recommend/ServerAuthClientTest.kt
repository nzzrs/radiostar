package com.metrolist.music.recommend

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerAuthClientTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun loginParsesSession() {
        var seenBody = ""
        server.createContext("/auth/login") { exchange ->
            seenBody = exchange.requestBody.bufferedReader().use { it.readText() }
            respond(
                exchange,
                200,
                """
                {"access_token":"access","refresh_token":"refresh","expires_in":900,
                 "device_id":"device","device_name":"Phone","username":"owner"}
                """.trimIndent(),
            )
        }
        val session = ServerAuthClient().login(baseUrl, "owner", "secret", "Phone")
        assertEquals("access", session.accessToken)
        assertEquals("refresh", session.refreshToken)
        assertEquals("owner", session.username)
        assertTrue(seenBody.contains("\"device_name\":\"Phone\""))
    }

    @Test
    fun unauthorizedIsMarked() {
        server.createContext("/auth/refresh") { exchange ->
            respond(exchange, 401, """{"error":"invalid credentials"}""")
        }
        try {
            ServerAuthClient().refresh(baseUrl, "old")
            throw AssertionError("expected AuthException")
        } catch (error: ServerAuthClient.AuthException) {
            assertTrue(error.unauthorized)
            assertEquals("invalid credentials", error.message)
        }
    }

    @Test
    fun pairingCodeSendsBearer() {
        var authorization = ""
        server.createContext("/auth/pairing-code") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            respond(exchange, 200, """{"code":"ABCDE-FGHIJ","expires_in":300}""")
        }
        val code = ServerAuthClient().createPairingCode(baseUrl, "access")
        assertEquals("ABCDE-FGHIJ", code.code)
        assertEquals("Bearer access", authorization)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
