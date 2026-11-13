package com.electricdreams.numo.payment

import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MintQuoteWebSocketTest {
    @Test
    fun `one connection routes two quote subscriptions independently`() = runTest {
        val factory = FakeQuoteSocketFactory()
        val sockets = MintQuoteWebSocket(this, factory)
        val lightning = sockets.subscribe("https://mint.test", "bolt11_mint_quote", "lightning")
        val arkoor = sockets.subscribe("https://mint.test/", "arkoor_mint_quote", "arkoor")
        assertEquals(1, factory.connections.size)
        val connection = factory.connections.single()
        connection.open()
        val requests = connection.messages.map { JsonParser.parseString(it).asJsonObject }
        assertEquals(listOf("bolt11_mint_quote", "arkoor_mint_quote"), requests.map {
            it.getAsJsonObject("params").get("kind").asString
        })
        assertEquals(2, requests.map { it.get("id").asLong }.distinct().size)
        assertEquals(listOf("lightning", "arkoor"), requests.map {
            it.getAsJsonObject("params").getAsJsonArray("filters")[0].asString
        })

        val lightningUpdate = async { lightning.awaitUpdate(1_000) }
        val arkoorUpdate = async { arkoor.awaitUpdate(1_000) }
        runCurrent()
        connection.notify(arkoor.id, "lightning", "\"state\":\"PAID\"")
        runCurrent()
        assertFalse(lightningUpdate.isCompleted)
        assertFalse(arkoorUpdate.isCompleted)
        connection.notify(arkoor.id, "arkoor", "\"amount_paid\":500,\"amount_issued\":0")
        runCurrent()
        assertEquals(500, arkoorUpdate.await()?.get("amount_paid")?.asInt)
        assertFalse(lightningUpdate.isCompleted)
        arkoor.close()
        verify(connection.socket, never()).close(any(), any())
        connection.notify(lightning.id, "lightning", "\"state\":\"PAID\"")
        runCurrent()
        assertEquals("PAID", lightningUpdate.await()?.get("state")?.asString)
        lightning.close()
        verify(connection.socket).close(1000, "Checkout monitoring finished")
        assertEquals("unsubscribe", JsonParser.parseString(connection.messages.last())
            .asJsonObject.get("method").asString)
        sockets.close()
    }

    @Test
    fun `cdk custom tuple payload is unwrapped only for its method and quote`() = runTest {
        val factory = FakeQuoteSocketFactory()
        val sockets = MintQuoteWebSocket(this, factory)
        val arkoor = sockets.subscribe("https://mint.test", "arkoor_mint_quote", "arkoor")
        val connection = factory.connections.single()
        connection.open()
        val update = async { arkoor.awaitUpdate(1_000) }
        runCurrent()
        connection.listener.onMessage(connection.socket,
            """{"method":"subscribe","params":{"subId":"${arkoor.id}","payload":["other",{"quote":"arkoor","amount_paid":1000}]}}""")
        runCurrent()
        assertFalse(update.isCompleted)
        connection.listener.onMessage(connection.socket,
            """{"method":"subscribe","params":{"subId":"${arkoor.id}","payload":["arkoor",{"quote":"other","method":"arkoor","amount_paid":1000}]}}""")
        runCurrent()
        assertFalse(update.isCompleted)
        connection.listener.onMessage(connection.socket,
            """{"method":"subscribe","params":{"subId":"${arkoor.id}","payload":["arkoor",{"quote":"arkoor","method":"arkoor","amount_paid":1000,"amount_issued":0}]}}""")
        runCurrent()
        assertEquals(1_000, update.await()?.get("amount_paid")?.asInt)
        sockets.close()
    }

    @Test
    fun `disconnect reconnects and resubscribes both saved quote ids`() = runTest {
        val factory = FakeQuoteSocketFactory()
        val sockets = MintQuoteWebSocket(this, factory, reconnectDelayMs = 100)
        val lightning = sockets.subscribe("https://mint.test", "bolt11_mint_quote", "lightning")
        val arkoor = sockets.subscribe("https://mint.test", "arkoor_mint_quote", "arkoor")
        val first = factory.connections.single()
        first.open()
        first.acknowledge(0, lightning.id)
        first.acknowledge(1, arkoor.id)
        assertTrue(lightning.isConnected)
        assertTrue(arkoor.isConnected)
        first.listener.onFailure(first.socket, IOException("offline"), null)
        assertFalse(lightning.isConnected)
        assertFalse(arkoor.isConnected)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, factory.connections.size)
        val second = factory.connections.last()
        second.open()
        val requests = second.messages.map { JsonParser.parseString(it).asJsonObject }
        assertEquals(listOf(lightning.id, arkoor.id), requests.map {
            it.getAsJsonObject("params").get("subId").asString
        })
        assertEquals(listOf("lightning", "arkoor"), requests.map {
            it.getAsJsonObject("params").getAsJsonArray("filters")[0].asString
        })
        second.notify(arkoor.id, "arkoor", "\"amount_paid\":1000")
        assertEquals(1_000, arkoor.awaitUpdate(1_000)?.get("amount_paid")?.asInt)
        assertTrue(arkoor.isConnected)
        // An old connection's callbacks cannot disturb its replacement.
        first.listener.onClosed(first.socket, 1000, "late close")
        assertTrue(arkoor.isConnected)
        sockets.close()
    }

    @Test
    fun `rejected arkoor subscription leaves lightning connection active`() = runTest {
        val factory = FakeQuoteSocketFactory()
        val sockets = MintQuoteWebSocket(this, factory)
        val lightning = sockets.subscribe("https://mint.test", "bolt11_mint_quote", "lightning")
        val arkoor = sockets.subscribe("https://mint.test", "arkoor_mint_quote", "arkoor")
        val connection = factory.connections.single()
        connection.open()
        connection.acknowledge(0, lightning.id)
        connection.listener.onMessage(connection.socket,
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"unsupported"}}""")
        assertFalse(arkoor.isConnected)
        assertTrue(lightning.isConnected)
        verify(connection.socket, never()).close(any(), any())
        arkoor.close()
        connection.notify(lightning.id, "lightning", "\"state\":\"PAID\"")
        assertEquals("PAID", lightning.awaitUpdate(1_000)?.get("state")?.asString)
        sockets.close()
    }

    @Test
    fun `different mints get separate connections and closing cancels reconnect`() = runTest {
        val factory = FakeQuoteSocketFactory()
        val sockets = MintQuoteWebSocket(this, factory)
        sockets.subscribe("https://first.test", "bolt11_mint_quote", "lightning")
        sockets.subscribe("https://second.test", "arkoor_mint_quote", "arkoor")
        assertEquals(2, factory.connections.size)
        factory.connections.first().let {
            it.listener.onFailure(it.socket, IOException("offline"), null)
        }
        sockets.close()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, factory.connections.size)
    }
}

internal class FakeQuoteSocketFactory : WebSocket.Factory {
    val connections = mutableListOf<Connection>()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val connection = Connection(request, listener)
        connections.add(connection)
        return connection.socket
    }

    class Connection(private val request: Request, val listener: WebSocketListener) {
        val messages = mutableListOf<String>()
        val socket = mock<WebSocket>().also { socket ->
            whenever(socket.send(any<String>())).thenAnswer {
                messages.add(it.getArgument(0))
                true
            }
        }

        fun open() {
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
        }

        fun acknowledge(requestId: Int, subId: String) {
            listener.onMessage(socket,
                """{"jsonrpc":"2.0","id":$requestId,"result":{"status":"OK","subId":"$subId"}}""")
        }

        fun notify(subId: String, quoteId: String, fields: String) {
            listener.onMessage(socket,
                """{"jsonrpc":"2.0","method":"subscribe","params":{"subId":"$subId","payload":{"quote":"$quoteId",$fields}}}""")
        }
    }
}
