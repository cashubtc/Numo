package com.electricdreams.numo.feature.reporting

import com.google.gson.Gson
import com.google.gson.JsonArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
        val outcomes = withTimeoutOrNull(configuration.overallTimeoutMs) {
            coroutineScope {
                configuration.relays.map { relay ->
                    async {
                        runCatching {
                            transport.publish(
                                relayUrl = relay,
                                submission = submission,
                                timeoutMs = configuration.relayTimeoutMs
                            )
                        }.getOrDefault(false)
                    }
                }.awaitAll()
            }
        }
        return IssueReportPublishResult(
            acceptedCount = outcomes?.count { it } ?: 0,
            attemptedCount = configuration.relays.size
        )
    }
}

class OkHttpIssueReportRelayTransport(
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) : IssueReportRelayTransport {
    override suspend fun publish(
        relayUrl: String,
        submission: IssueReportSubmission,
        timeoutMs: Long
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        publishAwaitingAcknowledgement(relayUrl, submission)
    } ?: false

    private suspend fun publishAwaitingAcknowledgement(
        relayUrl: String,
        submission: IssueReportSubmission
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        var socket: WebSocket? = null

        fun finish(accepted: Boolean) {
            if (completed.compareAndSet(false, true)) {
                socket?.close(1000, "complete")
                if (continuation.isActive) {
                    continuation.resume(accepted)
                }
            }
        }

        val request = runCatching { Request.Builder().url(relayUrl).build() }
            .getOrElse {
                finish(false)
                return@suspendCancellableCoroutine
            }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val message = JsonArray().apply {
                    add("EVENT")
                    add(gson.fromJson(submission.serializedEvent, com.google.gson.JsonElement::class.java))
                }
                if (!webSocket.send(message.toString())) {
                    finish(false)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val accepted = runCatching {
                    val message = gson.fromJson(text, JsonArray::class.java)
                    message.size() >= 4 &&
                        message[0].asString == "OK" &&
                        message[1].asString == submission.eventId &&
                        message[2].asBoolean
                }.getOrDefault(false)
                if (accepted) {
                    finish(true)
                } else {
                    val isMatchingNegative = runCatching {
                        val message = gson.fromJson(text, JsonArray::class.java)
                        message.size() >= 3 &&
                            message[0].asString == "OK" &&
                            message[1].asString == submission.eventId &&
                            !message[2].asBoolean
                    }.getOrDefault(false)
                    if (isMatchingNegative) {
                        finish(false)
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
            socket?.cancel()
        }
    }
}
