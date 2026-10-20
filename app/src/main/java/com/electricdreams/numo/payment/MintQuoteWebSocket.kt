package com.electricdreams.numo.payment

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Multiplexes checkout quote subscriptions on one NUT-17 connection per mint. */
class MintQuoteWebSocket(
    private val scope: CoroutineScope,
    private val socketFactory: WebSocket.Factory = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    private val reconnectDelayMs: Long = 2_000,
) : Closeable {
    private val lock = Any()
    private val gson = Gson()
    private val connections = mutableMapOf<String, Connection>()
    private var closed = false

    fun subscribe(mintUrl: String, kind: String, quoteId: String): Subscription = synchronized(lock) {
        check(!closed) { "Quote WebSocket is closed" }
        val url = buildWsUrl(mintUrl)
        val connection = connections.getOrPut(url) { Connection(url) }
        val subscription = Subscription(quoteId, kind) { remove(connection, it) }
        connection.subscriptions[subscription.id] = subscription
        if (connection.open) {
            connection.sendSubscribe(subscription)
        } else if (connection.socket == null && connection.reconnectJob == null) {
            connection.connect()
        }
        subscription
    }

    override fun close() = synchronized(lock) {
        closed = true
        connections.values.forEach { connection ->
            connection.subscriptions.values.forEach { it.finish() }
            connection.stop()
        }
        connections.clear()
    }

    private fun remove(connection: Connection, subscription: Subscription) = synchronized(lock) {
        if (connection.subscriptions.remove(subscription.id) == null) return@synchronized
        if (connection.open && !subscription.rejected) {
            connection.send("unsubscribe", mapOf("subId" to subscription.id))
        }
        subscription.finish()
        if (connection.subscriptions.isEmpty()) {
            connection.stop()
            connections.remove(connection.url)
        }
    }

    class Subscription internal constructor(
        val quoteId: String,
        internal val kind: String,
        private val onClose: (Subscription) -> Unit,
    ) : Closeable {
        internal val id = UUID.randomUUID().toString()
        private val updates = Channel<JsonObject?>(Channel.CONFLATED)
        @Volatile var isConnected = false
            internal set
        @Volatile internal var rejected = false

        /** Null also wakes the receiver when connection availability changes. */
        suspend fun awaitUpdate(timeoutMs: Long): JsonObject? = withTimeoutOrNull(timeoutMs) {
            updates.receive()
        }

        internal fun signal(payload: JsonObject? = null) {
            updates.trySend(payload)
        }

        internal fun finish() {
            isConnected = false
            updates.close()
        }

        override fun close() = onClose(this)
    }

    private inner class Connection(val url: String) : WebSocketListener() {
        val subscriptions = mutableMapOf<String, Subscription>()
        private val requests = mutableMapOf<Long, Subscription>()
        var socket: WebSocket? = null
        var open = false
        var reconnectJob: Job? = null
        private var nextRequestId = 0L

        fun connect() {
            socket = socketFactory.newWebSocket(Request.Builder().url(url).build(), this)
        }

        fun send(method: String, params: Map<String, Any>): Long {
            val id = nextRequestId++
            socket?.send(gson.toJson(mapOf(
                "jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params,
            )))
            return id
        }

        fun sendSubscribe(subscription: Subscription) {
            if (subscription.rejected) return
            val id = send("subscribe", mapOf(
                "kind" to subscription.kind,
                "subId" to subscription.id,
                "filters" to listOf(subscription.quoteId),
            ))
            requests[id] = subscription
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (socket !== webSocket) return@synchronized
            open = true
            subscriptions.values.forEach { sendSubscribe(it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (socket !== webSocket) return@synchronized
            try {
                val message = gson.fromJson(text, JsonObject::class.java) ?: return@synchronized
                val requestId = message.get("id")?.takeUnless { it.isJsonNull }?.asLong
                if (message.has("error")) {
                    val subscription = requests.remove(requestId) ?: return@synchronized
                    Log.w(TAG, "Mint rejected ${subscription.kind}: ${message.get("error")}")
                    subscription.rejected = true
                    subscription.isConnected = false
                    subscription.signal()
                } else if (message.has("result")) {
                    val subscription = requests.remove(requestId) ?: return@synchronized
                    if (subscriptions[subscription.id] !== subscription) return@synchronized
                    subscription.isConnected = true
                    subscription.signal()
                } else if (message.get("method")?.asString == "subscribe") {
                    val params = message.getAsJsonObject("params") ?: return@synchronized
                    val subscription = subscriptions[params.get("subId")?.asString]
                        ?: return@synchronized
                    val payload = quotePayload(subscription, params.get("payload"))
                        ?: return@synchronized
                    // The shared connection must never route one quote's payment to another.
                    if (payload.get("quote")?.asString != subscription.quoteId) return@synchronized
                    subscription.isConnected = true
                    subscription.signal(payload)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Invalid mint quote WebSocket message", error)
                disconnected(webSocket)
                webSocket.cancel()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        private fun quotePayload(subscription: Subscription, raw: JsonElement?): JsonObject? {
            val method = subscription.kind.removeSuffix("_mint_quote")
            // CDK 0.18 serializes custom notifications as [method, quote]. Also
            // accept the plain quote object used by the HTTP API and other mints.
            val value = if (raw?.isJsonArray == true) {
                val wrapper = raw.asJsonArray
                if (wrapper.size() != 2 || wrapper[0].asString != method) return null
                wrapper[1]
            } else raw
            if (value?.isJsonObject != true) return null
            val payload = value.asJsonObject
            val payloadMethod = payload.get("method")?.takeUnless { it.isJsonNull }?.asString
            if (payloadMethod != null && payloadMethod != method) return null
            return payload
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Mint quote WebSocket disconnected: $url", t)
            disconnected(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            disconnected(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnected(webSocket)
        }

        private fun disconnected(webSocket: WebSocket) = synchronized(lock) {
            if (socket !== webSocket) return@synchronized
            socket = null
            open = false
            requests.clear()
            subscriptions.values.forEach {
                it.isConnected = false
                it.signal()
            }
            if (subscriptions.values.any { !it.rejected }) {
                reconnectJob = scope.launch {
                    delay(reconnectDelayMs)
                    synchronized(lock) {
                        reconnectJob = null
                        if (!closed && connections[url] === this@Connection) connect()
                    }
                }
            }
        }

        fun stop() {
            reconnectJob?.cancel()
            reconnectJob = null
            val oldSocket = socket
            socket = null
            open = false
            requests.clear()
            oldSocket?.close(1000, "Checkout monitoring finished")
        }
    }

    companion object {
        private const val TAG = "MintQuoteWebSocket"

        internal fun buildWsUrl(mintUrl: String): String {
            val base = mintUrl.trimEnd('/')
            val wsBase = when {
                base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
                base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
                base.startsWith("wss://", ignoreCase = true) ||
                    base.startsWith("ws://", ignoreCase = true) -> base
                else -> "wss://$base"
            }
            return "$wsBase/v1/ws"
        }
    }
}
