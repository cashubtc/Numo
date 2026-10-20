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
import java.util.concurrent.TimeUnit

/** Opt-in tests against integration-tests/arkoor's Docker regtest mint. */
@RunWith(RobolectricTestRunner::class)
class MintQuoteWebSocketIntegrationTest {
    @Test
    fun `real mint delivers arkoor and bolt11 snapshots through one websocket`() = runBlocking {
        val mintUrl = System.getenv("NUMO_ARKOOR_MINT_URL")
        assumeTrue("Run integration-tests/arkoor/websocket-smoke.sh to enable", mintUrl != null)
        val base = checkNotNull(mintUrl).trimEnd('/')
        val client = OkHttpClient()
        val arkoorQuote = createQuote(client, base, "arkoor")
        val lightningQuote = createQuote(client, base, "bolt11")
        val mainnet = System.getenv("NUMO_ARKOOR_NETWORK") == "mainnet"
        val arkPrefix = if (mainnet) "ark1" else "tark1"
        val lightningPrefix = if (mainnet) "lnbc" else "lnbcrt"
        assertTrue(arkoorQuote.get("request").asString.startsWith(arkPrefix))
        assertTrue(lightningQuote.get("request").asString.startsWith(lightningPrefix))
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

    @Test
    fun `real arkoor payment updates its subscription while lightning remains unpaid`() = runBlocking {
        val mintUrl = System.getenv("NUMO_ARKOOR_MINT_URL")
        val payScript = System.getenv("NUMO_ARKOOR_PAY_SCRIPT")
        assumeTrue("Provision the Docker regtest payer to enable", mintUrl != null && payScript != null)
        val base = checkNotNull(mintUrl).trimEnd('/')
        val client = OkHttpClient()
        val arkoorQuote = createQuote(client, base, "arkoor", 330)
        val lightningQuote = createQuote(client, base, "bolt11", 330)
        val address = arkoorQuote.get("request").asString
        assertTrue(address.startsWith("tark1"))
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
            assertEquals(0, snapshot(arkoor).get("amount_paid").asInt)
            assertEquals("UNPAID", snapshot(lightning).get("state").asString)
            val process = ProcessBuilder(
                "python3", checkNotNull(payScript), "--address", address, "--amount", "330"
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Regtest payment timed out" }
                assertEquals("Regtest payer failed", 0, process.exitValue())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val paid = withTimeout(45_000) {
                var update = arkoor.awaitUpdate(1_000)
                while (update?.get("amount_paid")?.asInt != 330) {
                    update = arkoor.awaitUpdate(1_000)
                }
                checkNotNull(update)
            }
            assertEquals(arkoorId, paid.get("quote").asString)
            assertEquals(0, paid.get("amount_issued").asInt)
            arkoor.close()
            val resumed = sockets.subscribe(base, "arkoor_mint_quote", arkoorId)
            assertEquals(330, snapshot(resumed).get("amount_paid").asInt)
            val lnStatus = requestJson(client, Request.Builder()
                .url("$base/v1/mint/quote/bolt11/$lightningId").build())
            assertEquals("UNPAID", lnStatus.get("state").asString)
            assertEquals(1, connections.get())
        } finally {
            sockets.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun createQuote(
        client: OkHttpClient, base: String, method: String, amount: Int = 330
    ): JsonObject {
        val body = """{"amount":$amount,"unit":"sat"}"""
            .toRequestBody("application/json".toMediaType())
        return requestJson(client, Request.Builder()
            .url("$base/v1/mint/quote/$method").post(body).build())
    }

    private fun requestJson(client: OkHttpClient, request: Request): JsonObject {
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Quote request failed: ${response.code}" }
            JsonParser.parseString(checkNotNull(response.body).string()).asJsonObject
        }
    }

    private suspend fun snapshot(subscription: MintQuoteWebSocket.Subscription): JsonObject {
        return withTimeout(10_000) {
            var payload = subscription.awaitUpdate(1_000)
            while (payload == null) payload = subscription.awaitUpdate(1_000)
            payload
        }
    }
}
