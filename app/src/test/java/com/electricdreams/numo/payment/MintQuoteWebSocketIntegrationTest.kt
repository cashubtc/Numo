package com.electricdreams.numo.payment

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in test against integration-tests/arkoor's real mint. Creates unpaid quotes only. */
@RunWith(RobolectricTestRunner::class)
class MintQuoteWebSocketIntegrationTest {
    @Test
    fun `real mint delivers arkoor and bolt11 snapshots through one websocket`() = runBlocking {
        val mintUrl = System.getenv("NUMO_ARKOOR_MINT_URL")
        assumeTrue("Run integration-tests/arkoor/websocket-smoke.sh to enable", mintUrl != null)
        val base = checkNotNull(mintUrl).trimEnd('/')
        val client = OkHttpClient()
        fun createQuote(method: String): JsonObject {
            val body = """{"amount":2,"unit":"sat"}"""
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$base/v1/mint/quote/$method").post(body).build()
            return client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Quote creation failed: ${response.code}" }
                JsonParser.parseString(checkNotNull(response.body).string()).asJsonObject
            }
        }
        val arkoorQuote = createQuote("arkoor")
        val lightningQuote = createQuote("bolt11")
        assertTrue(arkoorQuote.get("request").asString.startsWith("ark1"))
        assertTrue(lightningQuote.get("request").asString.startsWith("lnbc"))
        val connections = AtomicInteger()
        val factory = WebSocket.Factory { request, listener ->
            connections.incrementAndGet()
            client.newWebSocket(request, listener)
        }
        val sockets = MintQuoteWebSocket(this, factory)
        try {
            val arkoorId = arkoorQuote.get("quote").asString
            val lightningId = lightningQuote.get("quote").asString
            val arkoor = sockets.subscribe(base, "arkoor_mint_quote", arkoorId)
            val lightning = sockets.subscribe(base, "bolt11_mint_quote", lightningId)
            suspend fun snapshot(subscription: MintQuoteWebSocket.Subscription): JsonObject {
                return withTimeout(10_000) {
                    var payload = subscription.awaitUpdate(1_000)
                    while (payload == null) payload = subscription.awaitUpdate(1_000)
                    payload
                }
            }
            val arkSnapshot = snapshot(arkoor)
            val lnSnapshot = snapshot(lightning)
            assertEquals(arkoorId, arkSnapshot.get("quote").asString)
            assertEquals("arkoor", arkSnapshot.get("method").asString)
            assertEquals(0, arkSnapshot.get("amount_paid").asInt)
            assertEquals(0, arkSnapshot.get("amount_issued").asInt)
            assertEquals(lightningId, lnSnapshot.get("quote").asString)
            assertEquals("UNPAID", lnSnapshot.get("state").asString)
            assertEquals(1, connections.get())
            arkoor.close()
            assertTrue(lightning.isConnected)
            val resumed = sockets.subscribe(base, "arkoor_mint_quote", arkoorId)
            assertEquals(arkoorId, snapshot(resumed).get("quote").asString)
            assertEquals(1, connections.get())
        } finally {
            sockets.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
