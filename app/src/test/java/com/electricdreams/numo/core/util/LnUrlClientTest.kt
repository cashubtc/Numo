package com.electricdreams.numo.core.util

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LnUrlClientTest {
    @Test
    fun `incomplete hostname returns no details instead of throwing`() {
        assertNull(LnUrlClient.fetchLnUrlDetails("user@.com"))
    }

    @Test
    fun `malformed addresses never start a network request`() {
        val client: OkHttpClient = mock()
        val invalidAddresses = listOf(
            "", "user", "@example.com", "user@@example.com", "user@com",
            "user@.com", "user@x.", "user@a..b", "user name@example.com",
            "user@exa mple.com", "user@exa:mple.com", "user@exa\\mple.com",
            "user@example.com/path", "user@example.com?query", "user@example.com#fragment",
        )
        for (address in invalidAddresses) {
            assertNull(address, LnUrlClient.fetchLnUrlDetails(address, client))
        }
        verifyNoInteractions(client)
    }

    @Test
    fun `URL conversion preserves usernames and supports internationalized domains`() {
        assertEquals(
            "https://example.com/.well-known/lnurlp/Alice+shop",
            LnUrlClient.convertAddressToUrl("  Alice+shop@EXAMPLE.com  "),
        )
        assertEquals(
            "https://xn--bcher-kva.example/.well-known/lnurlp/alice",
            LnUrlClient.convertAddressToUrl("alice@bücher.example"),
        )
        assertEquals(
            "https://example.com/.well-known/lnurlp/alice%2Fshop",
            LnUrlClient.convertAddressToUrl("alice/shop@example.com"),
        )
    }

    @Test
    fun `valid address requests LNURL pay details at the well known path`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"callback":"https://example.com/callback","minSendable":1000,
                 "maxSendable":1000000,"metadata":"[]","tag":"payRequest"}
            """.trimIndent()))
            val details = LnUrlClient.fetchLnUrlDetails("alice@example.com", clientFor(server))
            assertEquals(1000L, details?.minSendable)
            assertEquals("payRequest", details?.tag)
            assertEquals("/.well-known/lnurlp/alice", server.takeRequest().path)
        }
    }

    @Test
    fun `HTTP errors and malformed responses return no details`() {
        MockWebServer().use { server ->
            val client = clientFor(server)
            server.enqueue(MockResponse().setResponseCode(500))
            assertNull(LnUrlClient.fetchLnUrlDetails("alice@example.com", client))
            server.enqueue(MockResponse().setBody("{broken"))
            assertNull(LnUrlClient.fetchLnUrlDetails("alice@example.com", client))
        }
    }

    @Test
    fun `transport failure returns no details`() {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("Offline") }.build()
        assertNull(LnUrlClient.fetchLnUrlDetails("alice@example.com", client))
    }

    @Test
    fun `cancellation is not swallowed as a lookup failure`() {
        val client = OkHttpClient.Builder().addInterceptor {
            throw CancellationException("Cancelled")
        }.build()
        assertThrows(CancellationException::class.java) {
            LnUrlClient.fetchLnUrlDetails("alice@example.com", client)
        }
    }

    private fun clientFor(server: MockWebServer): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .url(server.url(chain.request().url.encodedPath))
                .build()
            chain.proceed(request)
        }.build()
}
