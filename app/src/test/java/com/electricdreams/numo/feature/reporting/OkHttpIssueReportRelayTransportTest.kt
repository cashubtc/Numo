package com.electricdreams.numo.feature.reporting

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpIssueReportRelayTransportTest {
    private lateinit var server: MockWebServer
    private val eventId = "a".repeat(64)
    private val submission = IssueReportSubmission(
        serializedEvent = """
            {"id":"$eventId","pubkey":"${"b".repeat(64)}","created_at":1,
            "kind":1059,"tags":[],"content":"ciphertext","sig":"${"c".repeat(128)}"}
        """.trimIndent(),
        eventId = eventId
    )

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `matching positive OK is accepted`() = runBlocking {
        server.enqueue(webSocketResponse("[\"OK\",\"$eventId\",true,\"\"]"))
        server.start()

        val accepted = OkHttpIssueReportRelayTransport().publish(
            relayUrl = server.url("/").toString().replaceFirst("http", "ws"),
            submission = submission,
            timeoutMs = 2_000
        )

        assertTrue(accepted)
    }

    @Test
    fun `matching negative OK is rejected`() = runBlocking {
        server.enqueue(
            webSocketResponse("[\"OK\",\"$eventId\",false,\"blocked: rejected\"]")
        )
        server.start()

        val accepted = OkHttpIssueReportRelayTransport().publish(
            relayUrl = server.url("/").toString().replaceFirst("http", "ws"),
            submission = submission,
            timeoutMs = 2_000
        )

        assertFalse(accepted)
    }

    private fun webSocketResponse(reply: String): MockResponse = MockResponse()
        .withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(reply)
                webSocket.close(1000, "complete")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // The assertion is driven by the client result.
            }
        })
}
