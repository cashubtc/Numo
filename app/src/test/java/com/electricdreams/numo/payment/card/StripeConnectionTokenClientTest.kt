package com.electricdreams.numo.payment.card

import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class StripeConnectionTokenClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: StripeConnectionTokenClient
    private val config = StripeCardConfig(
        restrictedKey = "rk_test_abc123",
        locationId = "tml_location1",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StripeConnectionTokenClient(baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creates a connection token with the restricted key and location`() {
        server.enqueue(
            MockResponse().setBody("""{"object":"terminal.connection_token","secret":"pst_test_secret"}""")
        )

        val secret = client.createConnectionToken(config)

        assertEquals("pst_test_secret", secret)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/terminal/connection_tokens", request.path)
        assertEquals("Bearer rk_test_abc123", request.getHeader("Authorization"))
        assertEquals("location=tml_location1", request.body.readUtf8())
    }

    @Test
    fun `surfaces Stripe's error message for a key without permission`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody(
                    """{"error":{"code":"secret_key_required","message":"The provided key does not have the required permissions","type":"invalid_request_error"}}"""
                )
        )

        val error = assertThrows(StripeConnectionTokenClient.StripeApiException::class.java) {
            client.createConnectionToken(config)
        }

        assertEquals("The provided key does not have the required permissions", error.message)
        assertEquals(403, error.httpStatus)
        assertEquals("secret_key_required", error.stripeCode)
    }

    @Test
    fun `reports the HTTP status when the error body is not JSON`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad gateway"))

        val error = assertThrows(StripeConnectionTokenClient.StripeApiException::class.java) {
            client.createConnectionToken(config)
        }

        assertEquals("Stripe returned HTTP 502", error.message)
    }

    @Test
    fun `rejects a success response without a secret`() {
        server.enqueue(MockResponse().setBody("""{"object":"terminal.connection_token"}"""))

        assertThrows(IOException::class.java) {
            client.createConnectionToken(config)
        }
    }
}
