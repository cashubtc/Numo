package com.electricdreams.numo.nostr

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Read-only NIP-87 mint discovery.
 *
 * It deliberately queries a small, curated relay set and verifies every event before exposing a
 * URL. Discovery is a recommendation signal only; the normal add-mint flow still validates the
 * selected mint's /v1/info endpoint before trusting it.
 */
object NostrMintDiscovery {

    data class PublicMintProfile(
        val name: String?,
        val iconBytes: ByteArray?,
    )

    data class MintRecommendation(
        val url: String,
        val name: String?,
        val reviewCount: Int,
        val averageRating: Double?,
    )

    private data class Review(
        val author: String,
        val createdAt: Long,
        val rating: Int?,
    )

    private data class MutableRecommendation(
        val url: String,
        var name: String? = null,
        var announcementCreatedAt: Long = 0,
        val reviewsByAuthor: MutableMap<String, Review> = mutableMapOf(),
    )

    private const val TAG = "NostrMintDiscovery"
    private const val MINT_INFO_KIND = 38172
    private const val RECOMMENDATION_KIND = 38000
    private const val DISCOVERY_TIMEOUT_MS = 15_000L
    private const val MAX_EVENTS_PER_FILTER = 5_000
    private const val MAX_ICON_BYTES = 2 * 1024 * 1024L
    internal const val MAX_DISCOVERY_RESULTS = 50

    val DEFAULT_RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.mom",
    )

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val publicHttpClient = OkHttpClient.Builder()
        .dns(publicAddressDns())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun discover(
        relays: List<String> = DEFAULT_RELAYS,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    ): List<MintRecommendation> = discoverFlow(relays, timeoutMs).lastOrNull().orEmpty()

    suspend fun fetchPublicMintProfile(url: String): PublicMintProfile? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$url/v1/info").get().build()
            publicHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val info = parsePublicMintInfo(response.body?.string(), url)
                val iconBytes = info.iconUrl?.let { fetchPublicIcon(it) }
                PublicMintProfile(info.name, iconBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public mint profile fetch failed for $url", e)
            null
        }
    }

    /**
     * Emits a fresh aggregate whenever a verified mint event arrives. Relays commonly return
     * results at different speeds, so callers can render useful results without waiting for EOSE
     * from every connection.
     */
    fun discoverFlow(
        relays: List<String> = DEFAULT_RELAYS,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    ): Flow<List<MintRecommendation>> = channelFlow {
        val verifiedEvents = linkedMapOf<String, NostrEvent>()
        val resolveHost = memoizingResolver(::resolveHost)
        val lock = Any()

        val fetchJob = launch(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                fetchEvents(relays.distinct()) event@{ event ->
                    if (!event.verify()) return@event
                    val snapshot = synchronized(lock) {
                        val id = event.id ?: return@synchronized null
                        if (verifiedEvents.putIfAbsent(id, event) != null) {
                            null
                        } else {
                            verifiedEvents.values.toList()
                        }
                    }
                    if (snapshot != null) {
                        trySend(
                            aggregate(
                                snapshot,
                                verifyEvents = false,
                                resolveHost = resolveHost,
                            ),
                        )
                    }
                }
            }
            close()
        }

        awaitClose { fetchJob.cancel() }
    }

    internal fun aggregate(
        events: Collection<NostrEvent>,
        verifyEvents: Boolean = true,
        resolveHost: (String) -> List<InetAddress> = ::resolveHost,
    ): List<MintRecommendation> {
        val mints = linkedMapOf<String, MutableRecommendation>()

        events.asSequence()
            .filter { it.kind == MINT_INFO_KIND || it.kind == RECOMMENDATION_KIND }
            .filter { !verifyEvents || it.verify() }
            .forEach { event ->
                when (event.kind) {
                    MINT_INFO_KIND -> handleMintInfo(event, mints, resolveHost)
                    RECOMMENDATION_KIND -> handleRecommendation(event, mints, resolveHost)
                }
            }

        return mints.values.map { mint ->
            val ratings = mint.reviewsByAuthor.values.mapNotNull { it.rating }
            MintRecommendation(
                url = mint.url,
                name = mint.name,
                reviewCount = mint.reviewsByAuthor.size,
                averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
            )
        }.sortedWith(
            compareByDescending<MintRecommendation> { it.reviewCount }
                .thenByDescending { it.averageRating ?: 0.0 }
                .thenBy { it.name?.lowercase(Locale.ROOT) ?: it.url },
        ).take(MAX_DISCOVERY_RESULTS)
    }

    private fun handleMintInfo(
        event: NostrEvent,
        mints: MutableMap<String, MutableRecommendation>,
        resolveHost: (String) -> List<InetAddress>,
    ) {
        val url = event.cashuUrls(resolveHost).firstOrNull() ?: return
        val mint = mints.getOrPut(url) { MutableRecommendation(url) }
        if (event.created_at < mint.announcementCreatedAt) return

        mint.announcementCreatedAt = event.created_at
        mint.name = parseName(event.content)
    }

    private fun handleRecommendation(
        event: NostrEvent,
        mints: MutableMap<String, MutableRecommendation>,
        resolveHost: (String) -> List<InetAddress>,
    ) {
        val isCashuRecommendation = event.tags.any {
            it.size >= 2 && it[0] == "k" && it[1] == MINT_INFO_KIND.toString()
        }
        if (!isCashuRecommendation) return

        val rating = parseRating(event.content)
        event.cashuUrls(resolveHost).forEach { url ->
            val mint = mints.getOrPut(url) { MutableRecommendation(url) }
            val previous = mint.reviewsByAuthor[event.pubkey]
            if (previous == null || event.created_at > previous.createdAt) {
                mint.reviewsByAuthor[event.pubkey] = Review(
                    author = event.pubkey,
                    createdAt = event.created_at,
                    rating = rating,
                )
            }
        }
    }

    private fun NostrEvent.cashuUrls(
        resolveHost: (String) -> List<InetAddress>,
    ): List<String> = tags.asSequence()
        .filter { it.size >= 2 && it[0] == "u" }
        .filter { it.size < 3 || it[2].isBlank() || it[2].equals("cashu", ignoreCase = true) }
        .mapNotNull { normalizePublicUrl(it[1], resolveHost) }
        .distinct()
        .toList()

    private fun normalizePublicUrl(
        rawUrl: String,
        resolveHost: (String) -> List<InetAddress>,
    ): String? {
        return try {
            val uri = URI(rawUrl.trim())
            if (uri.scheme != "https" && uri.scheme != "http") return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (uri.userInfo != null || uri.fragment != null) return null
            val addresses = resolveHost(host)
            if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().trimEnd('/')
            "${uri.scheme}://$host$port$path"
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveHost(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList()

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> isPublicIpv6(bytes)
            else -> false
        }
    }

    internal fun publicAddressDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
                throw UnknownHostException(
                    "Host does not resolve exclusively to public addresses",
                )
            }
            return addresses
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 -> false
            first == 100 && second in 64..127 -> false
            first == 192 && second == 0 && third == 0 -> false
            first == 192 && second == 0 && third == 2 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            first >= 240 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return when {
            first and 0xfe == 0xfc -> false // Unique-local fc00::/7.
            first == 0x20 && second == 0x01 && bytes[2].toInt() == 0x0d &&
                bytes[3].toInt() and 0xff == 0xb8 -> false // Documentation 2001:db8::/32.
            else -> true
        }
    }

    private fun parseName(content: String?): String? {
        if (content.isNullOrBlank()) return null
        return try {
            gson.fromJson(content, JsonObject::class.java)
                ?.get("name")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private data class PublicMintInfo(
        val name: String?,
        val iconUrl: String?,
    )

    private fun parsePublicMintInfo(content: String?, mintUrl: String): PublicMintInfo {
        if (content.isNullOrBlank()) return PublicMintInfo(null, null)
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val name = json?.get("name")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val rawIconUrl = sequenceOf("icon_url", "iconUrl")
                .mapNotNull { key ->
                    json?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                }
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
            val iconUrl = rawIconUrl?.let { URI("$mintUrl/").resolve(it).toString() }
                ?.takeIf {
                    val scheme = URI(it).scheme
                    scheme == "https" || scheme == "http"
                }
            PublicMintInfo(name, iconUrl)
        } catch (_: Exception) {
            PublicMintInfo(null, null)
        }
    }

    private fun fetchPublicIcon(iconUrl: String): ByteArray? {
        return try {
            val request = Request.Builder().url(iconUrl).get().build()
            publicHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_ICON_BYTES) return null
                val source = body.source()
                source.request(MAX_ICON_BYTES + 1)
                if (source.buffer.size > MAX_ICON_BYTES) return null
                source.readByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public mint icon fetch failed for $iconUrl", e)
            null
        }
    }

    internal fun memoizingResolver(
        delegate: (String) -> List<InetAddress>,
    ): (String) -> List<InetAddress> {
        val cache = ConcurrentHashMap<String, List<InetAddress>>()
        return { host -> cache.computeIfAbsent(host, delegate) }
    }

    private fun parseRating(content: String?): Int? {
        val match = RATING_REGEX.find(content.orEmpty()) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..5 }
    }

    private suspend fun fetchEvents(
        relays: List<String>,
        onEvent: (NostrEvent) -> Unit,
    ): List<NostrEvent> =
        suspendCancellableCoroutine { continuation ->
            if (relays.isEmpty()) {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            val subscriptionId = UUID.randomUUID().toString().take(8)
            val eventsById = linkedMapOf<String, NostrEvent>()
            val sockets = mutableListOf<WebSocket>()
            val completedRelays = mutableSetOf<String>()
            val completed = AtomicBoolean(false)
            val lock = Any()

            fun finishRelay(relay: String) {
                val result = synchronized(lock) {
                    completedRelays.add(relay)
                    if (
                        completedRelays.size == relays.size &&
                        continuation.isActive &&
                        completed.compareAndSet(false, true)
                    ) {
                        eventsById.values.toList()
                    } else {
                        null
                    }
                }
                if (result != null) continuation.resume(result)
            }

            relays.forEach { relay ->
                val request = try {
                    Request.Builder().url(relay).build()
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid discovery relay: $relay", e)
                    finishRelay(relay)
                    return@forEach
                }

                val socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(discoveryRequest(subscriptionId))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleRelayMessage(text, subscriptionId, eventsById, lock, onEvent) {
                            webSocket.send(gson.toJson(JsonArray().apply {
                                add("CLOSE")
                                add(subscriptionId)
                            }))
                            webSocket.close(1000, "Discovery complete")
                            finishRelay(relay)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        onMessage(webSocket, bytes.utf8())
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "Mint discovery relay failed: $relay", t)
                        finishRelay(relay)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        finishRelay(relay)
                    }
                })
                sockets.add(socket)
            }

            continuation.invokeOnCancellation {
                sockets.forEach { it.cancel() }
            }
        }

    private fun handleRelayMessage(
        text: String,
        subscriptionId: String,
        eventsById: MutableMap<String, NostrEvent>,
        lock: Any,
        onEvent: (NostrEvent) -> Unit,
        onEose: () -> Unit,
    ) {
        try {
            val message = gson.fromJson(text, JsonArray::class.java)
            if (message.size() < 2) return
            when (message[0].asString) {
                "EVENT" -> {
                    if (message.size() < 3 || message[1].asString != subscriptionId) return
                    val event = gson.fromJson(message[2], NostrEvent::class.java) ?: return
                    val id = event.id ?: return
                    val isNew = synchronized(lock) { eventsById.putIfAbsent(id, event) == null }
                    if (isNew) onEvent(event)
                }
                "EOSE", "CLOSED" -> {
                    if (message[1].asString == subscriptionId) onEose()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Ignoring malformed relay message", e)
        }
    }

    internal fun discoveryRequest(subscriptionId: String): String = gson.toJson(
        JsonArray().apply {
            add("REQ")
            add(subscriptionId)
            add(JsonObject().apply {
                add("kinds", JsonArray().apply { add(MINT_INFO_KIND) })
                addProperty("limit", MAX_EVENTS_PER_FILTER)
            })
            add(JsonObject().apply {
                add("kinds", JsonArray().apply { add(RECOMMENDATION_KIND) })
                add("#k", JsonArray().apply { add(MINT_INFO_KIND.toString()) })
                addProperty("limit", MAX_EVENTS_PER_FILTER)
            })
        },
    )

    private val RATING_REGEX = Regex("""\[(\d)\s*/\s*5]""")
}
