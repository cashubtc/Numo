package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.NostrEvent
import com.electricdreams.numo.nostr.NostrEventSigner
import com.electricdreams.numo.nostr.NostrKeyPair
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class IssueReportPublishResult(
    val acceptedCount: Int,
    val attemptedCount: Int
) {
    val accepted: Boolean = acceptedCount > 0
}

fun interface IssueReportRelayTransport {
    suspend fun publish(
        relayUrl: String,
        channelName: String,
        submission: IssueReportSubmission,
        timeoutMs: Long
    ): Boolean
}

class IssueReportPublisher(
    private val transport: IssueReportRelayTransport
) {
    suspend fun publish(
        submission: IssueReportSubmission,
        configuration: IssueReportConfiguration
    ): IssueReportPublishResult {
        val accepted = withTimeoutOrNull(configuration.overallTimeoutMs) {
            runCatching {
                transport.publish(
                    relayUrl = configuration.relay,
                    channelName = configuration.channelName,
                    submission = submission,
                    timeoutMs = configuration.relayTimeoutMs
                )
            }.getOrDefault(false)
        } ?: false
        return IssueReportPublishResult(
            acceptedCount = if (accepted) 1 else 0,
            attemptedCount = 1
        )
    }
}

class OkHttpIssueReportRelayTransport(
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : IssueReportRelayTransport {
    override suspend fun publish(
        relayUrl: String,
        channelName: String,
        submission: IssueReportSubmission,
        timeoutMs: Long
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        publishAuthenticated(relayUrl, channelName, submission)
    } ?: false

    private suspend fun publishAuthenticated(
        relayUrl: String,
        channelName: String,
        submission: IssueReportSubmission
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val keyPair = runCatching {
            NostrKeyPair.fromSecretHex(submission.privateKeyHex)
        }.getOrElse {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val privateKey = keyPair.secretKeyBytes
        val subscriptionId = "numo-report-${UUID.randomUUID()}"
        var socket: WebSocket? = null
        var authEventId: String? = null
        var reportEventId: String? = null
        var channelId: String? = null

        fun finish(accepted: Boolean) {
            if (completed.compareAndSet(false, true)) {
                privateKey.fill(0)
                socket?.close(1000, "complete")
                if (continuation.isActive) {
                    continuation.resume(accepted)
                }
            }
        }

        fun send(webSocket: WebSocket, type: String, event: NostrEvent): Boolean {
            val message = JsonArray().apply {
                add(type)
                add(gson.toJsonTree(event))
            }
            return webSocket.send(message.toString())
        }

        fun publishReport(webSocket: WebSocket, resolvedChannelId: String) {
            val event = NostrEvent().apply {
                pubkey = keyPair.hexPub
                created_at = currentTimeMillis() / 1_000L
                kind = KIND_CHAT_MESSAGE
                tags = mutableListOf(mutableListOf("h", resolvedChannelId))
                content = submission.payloadJson
            }
            NostrEventSigner.sign(event, privateKey)
            reportEventId = event.id
            if (!send(webSocket, "EVENT", event)) {
                finish(false)
            }
        }

        val request = runCatching { Request.Builder().url(relayUrl).build() }
            .getOrElse {
                finish(false)
                return@suspendCancellableCoroutine
            }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { gson.fromJson(text, JsonArray::class.java) }
                    .getOrNull() ?: return
                when (message.firstString()) {
                    "AUTH" -> {
                        val challenge = message.stringAt(1) ?: return
                        val authEvent = NostrEvent().apply {
                            pubkey = keyPair.hexPub
                            created_at = currentTimeMillis() / 1_000L
                            kind = KIND_RELAY_AUTH
                            tags = mutableListOf(
                                mutableListOf("relay", relayUrl),
                                mutableListOf("challenge", challenge)
                            )
                            content = ""
                        }
                        NostrEventSigner.sign(authEvent, privateKey)
                        authEventId = authEvent.id
                        if (!send(webSocket, "AUTH", authEvent)) {
                            finish(false)
                        }
                    }

                    "OK" -> {
                        val eventId = message.stringAt(1) ?: return
                        val accepted = message.booleanAt(2) ?: return
                        when (eventId) {
                            authEventId -> {
                                if (!accepted) {
                                    finish(false)
                                    return
                                }
                                val filter = JsonObject().apply {
                                    add("kinds", JsonArray().apply { add(KIND_GROUP_METADATA) })
                                    addProperty("limit", MAX_CHANNELS)
                                }
                                val requestMessage = JsonArray().apply {
                                    add("REQ")
                                    add(subscriptionId)
                                    add(filter)
                                }
                                if (!webSocket.send(requestMessage.toString())) {
                                    finish(false)
                                }
                            }

                            reportEventId -> finish(accepted)
                        }
                    }

                    "EVENT" -> {
                        if (message.stringAt(1) != subscriptionId || message.size() < 3) return
                        val event = runCatching {
                            gson.fromJson(message[2], NostrEvent::class.java)
                        }.getOrNull() ?: return
                        if (event.kind != KIND_GROUP_METADATA) return
                        val name = event.tagValue("name")
                        val candidateId = event.tagValue("d")
                        if (name == channelName && candidateId?.matches(CHANNEL_ID) == true) {
                            channelId = candidateId
                        }
                    }

                    "EOSE" -> {
                        if (message.stringAt(1) != subscriptionId) return
                        channelId?.let { publishReport(webSocket, it) } ?: finish(false)
                    }

                    "CLOSED" -> {
                        if (message.stringAt(1) == subscriptionId) {
                            finish(false)
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(false)
            }
        })
        continuation.invokeOnCancellation {
            completed.set(true)
            privateKey.fill(0)
            socket?.cancel()
        }
    }

    private fun JsonArray.firstString(): String? = stringAt(0)

    private fun JsonArray.stringAt(index: Int): String? =
        getOrNull(index)?.takeUnless(JsonElement::isJsonNull)?.runCatching { asString }?.getOrNull()

    private fun JsonArray.booleanAt(index: Int): Boolean? =
        getOrNull(index)?.takeUnless(JsonElement::isJsonNull)?.runCatching { asBoolean }?.getOrNull()

    private fun JsonArray.getOrNull(index: Int): JsonElement? =
        if (index in 0 until size()) get(index) else null

    private fun NostrEvent.tagValue(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    companion object {
        const val KIND_CHAT_MESSAGE = 9
        const val KIND_RELAY_AUTH = 22_242
        const val KIND_GROUP_METADATA = 39_000
        private const val MAX_CHANNELS = 10_000
        private val CHANNEL_ID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
    }
}
