package com.electricdreams.numo.payment.card

import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class StripeLocationsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: StripeLocationsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StripeLocationsClient(baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `lists the account's locations with the restricted key`() {
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","data":[
                    {"id":"tml_shop","display_name":"Numo test shop","address":{"city":"Washington","country":"US"}},
                    {"id":"tml_stall","display_name":"","address":{"city":null,"country":"DE"}},
                    {"id":"not_a_location","display_name":"Ignored"}
                ]}"""
            )
        )

        val locations = client.listLocations(" rk_test_abc123 ")

        assertEquals(
            listOf(
                StripeLocation("tml_shop", "Numo test shop", "Washington", "US"),
                StripeLocation("tml_stall", "tml_stall", null, "DE"),
            ),
            locations,
        )
        assertEquals("Washington, US", locations[0].place)
        assertEquals("DE", locations[1].place)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/terminal/locations?limit=100", request.path)
        assertEquals("Bearer rk_test_abc123", request.getHeader("Authorization"))
    }

    @Test
    fun `all pages are listed using the last location as the cursor`() {
        server.enqueue(MockResponse().setBody("""{"has_more":true,"data":[{"id":"tml_first"},{"id":"tml_second"}]}"""))
        server.enqueue(MockResponse().setBody("""{"has_more":false,"data":[{"id":"tml_last"}]}"""))

        assertEquals(listOf("tml_first", "tml_second", "tml_last"),
            client.listLocations("rk_test_key").map { it.id })
        server.takeRequest()
        assertEquals("tml_second", server.takeRequest().requestUrl?.queryParameter("starting_after"))
    }

    @Test
    fun `a page without the promised cursor fails without looping`() {
        server.enqueue(MockResponse().setBody("""{"has_more":true,"data":[]}"""))
        assertThrows(IOException::class.java) { client.listLocations("rk_test_key") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `looks up one location by its ID`() {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"tml_shop","display_name":"Numo test shop","address":{"city":"Washington","country":"US"}}"""
            )
        )

        val location = client.getLocation("rk_test_abc123", " tml_shop ")

        assertEquals(StripeLocation("tml_shop", "Numo test shop", "Washington", "US"), location)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/terminal/locations/tml_shop", request.path)
        assertEquals("Bearer rk_test_abc123", request.getHeader("Authorization"))
    }

    @Test
    fun `a key without the locations permission surfaces as a 403`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"message":"The provided key does not have the required permissions","type":"invalid_request_error"}}"""
            )
        )

        val error = assertThrows(StripeConnectionTokenClient.StripeApiException::class.java) {
            client.listLocations("rk_test_abc123")
        }

        assertEquals(403, error.httpStatus)
    }

    @Test
    fun `creates a location and leaves out empty optional fields`() {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"tml_new","display_name":"Corner shop","address":{"city":"Berlin","country":"DE"}}"""
            )
        )

        val location = client.createLocation(
            restrictedKey = "rk_test_abc123",
            displayName = " Corner shop ",
            address = StripeLocationAddress(
                line1 = "Hauptstr. 1",
                line2 = " ",
                city = "Berlin",
                state = null,
                postalCode = "10115",
                country = "de",
            ),
            idempotencyKey = "create-shop",
        )

        assertEquals(StripeLocation("tml_new", "Corner shop", "Berlin", "DE"), location)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("create-shop", request.getHeader("Idempotency-Key"))
        assertEquals("/v1/terminal/locations", request.path)
        assertEquals(
            "display_name=Corner%20shop&address%5Bline1%5D=Hauptstr.%201&address%5Bcity%5D=Berlin" +
                "&address%5Bpostal_code%5D=10115&address%5Bcountry%5D=DE",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `Stripe's complaint about an address field names the field`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"postal_code_invalid","message":"Invalid postal code","param":"address[postal_code]"}}"""
            )
        )

        val error = assertThrows(StripeConnectionTokenClient.StripeApiException::class.java) {
            client.createLocation(
                "rk_test_abc123",
                "Shop",
                StripeLocationAddress("1 Main St", null, "Springfield", "IL", "x", "US"),
                "create-shop",
            )
        }

        assertEquals("Invalid postal code", error.message)
        assertEquals("address[postal_code]", error.param)
    }
}
