package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.NostrEvent
import com.electricdreams.numo.nostr.NostrKeyPair
import com.google.gson.Gson
import com.google.gson.JsonArray
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OkHttpIssueReportRelayTransportTest {
    private lateinit var server: MockWebServer
    private val gson = Gson()
    private val channelId = "123e4567-e89b-42d3-a456-426614174000"
    private val submission = IssueReportSubmission(
        payloadJson = """{"description":"scanner closed"}""",
        privateKeyHex = NostrKeyPair.generate().hexSec
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
    fun `authenticates discovers channel and publishes signed kind 9 event`() = runBlocking {
        var published: NostrEvent? = null
        server.enqueue(protocolResponse { event ->
            published = event
            true
        })
        server.start()

        val accepted = transport().publish(
            relayUrl = relayUrl(),
            channelName = "numo-reports",
            submission = submission,
            timeoutMs = 2_000
        )

        assertTrue(accepted)
        val event = requireNotNull(published)
        assertEquals(OkHttpIssueReportRelayTransport.KIND_CHAT_MESSAGE, event.kind)
        assertEquals(submission.payloadJson, event.content)
        assertEquals(listOf("h", channelId), event.tags.single())
        assertEquals(
            NostrKeyPair.fromSecretHex(submission.privateKeyHex).hexPub,
            event.pubkey
        )
        assertTrue(event.verify())
    }

    @Test
    fun `matching negative report acknowledgement is rejected`() = runBlocking {
        server.enqueue(protocolResponse { false })
        server.start()

        val accepted = transport().publish(
            relayUrl = relayUrl(),
            channelName = "numo-reports",
            submission = submission,
            timeoutMs = 2_000
        )

        assertFalse(accepted)
    }

    @Test
    fun `missing channel is rejected without publishing`() = runBlocking {
        server.enqueue(protocolResponse(channelName = "another-channel") { true })
        server.start()

        val accepted = transport().publish(
            relayUrl = relayUrl(),
            channelName = "numo-reports",
            submission = submission,
            timeoutMs = 2_000
        )

        assertFalse(accepted)
    }

    private fun transport() = OkHttpIssueReportRelayTransport(
        currentTimeMillis = { 1_784_547_300_000L }
    )

    private fun relayUrl(): String = server.url("/").toString().replaceFirst("http", "ws")

    private fun protocolResponse(
        channelName: String = "numo-reports",
        acceptReport: (NostrEvent) -> Boolean
    ): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""["AUTH","relay-challenge"]""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = gson.fromJson(text, JsonArray::class.java)
            when (message[0].asString) {
                "AUTH" -> {
                    val event = gson.fromJson(message[1], NostrEvent::class.java)
                    assertEquals(OkHttpIssueReportRelayTransport.KIND_RELAY_AUTH, event.kind)
                    assertTrue(event.verify())
                    assertTrue(event.tags.contains(listOf("challenge", "relay-challenge")))
                    webSocket.send("""["OK","${event.id}",true,""]""")
                }

                "REQ" -> {
                    val subscriptionId = message[1].asString
                    val metadata = NostrEvent().apply {
                        id = "d".repeat(64)
                        pubkey = "e".repeat(64)
                        created_at = 1
                        kind = OkHttpIssueReportRelayTransport.KIND_GROUP_METADATA
                        tags = mutableListOf(
                            mutableListOf("d", channelId),
                            mutableListOf("name", channelName),
                            mutableListOf("closed")
                        )
                        content = ""
                        sig = "f".repeat(128)
                    }
                    webSocket.send("""["EVENT","$subscriptionId",${gson.toJson(metadata)}]""")
                    webSocket.send("""["EOSE","$subscriptionId"]""")
                }

                "EVENT" -> {
                    val event = gson.fromJson(message[1], NostrEvent::class.java)
                    val accepted = acceptReport(event)
                    webSocket.send("""["OK","${event.id}",$accepted,""]""")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Client result drives the assertion.
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    })
}
