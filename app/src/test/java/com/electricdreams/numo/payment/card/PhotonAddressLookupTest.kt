package com.electricdreams.numo.payment.card

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonAddressLookupTest {
    @Test
    fun `structured lookup filters the query and parses address suggestions`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"features":[
                  {"properties":{
                    "type":"street","name":"Main Street","housenumber":"12","postcode":"10115",
                    "city":"Berlin","state":"Berlin","countrycode":"de"
                  }},
                  {"properties":{"name":"Not a street address"}}
                ]}
                """.trimIndent()
            )
        )
        server.start()
        try {
            val lookup = PhotonAddressLookup(server.url("/"))
            val suggestions = lookup.search(
                AddressQuery(
                    street = "Main Street",
                    postalCode = "10115",
                    city = "Berlin",
                    countryCode = "DE",
                )
            )

            val request = server.takeRequest()
            assertEquals("/structured", request.path?.substringBefore('?'))
            assertEquals("Main Street", request.requestUrl?.queryParameter("street"))
            assertEquals("10115", request.requestUrl?.queryParameter("postcode"))
            assertEquals("Berlin", request.requestUrl?.queryParameter("city"))
            assertEquals("DE", request.requestUrl?.queryParameter("countrycode"))
            assertTrue(request.getHeader("User-Agent")?.startsWith("Numo/") == true)
            assertEquals(1, suggestions.size)
            assertEquals(
                AddressSuggestion("Main Street 12", "10115", "Berlin", "Berlin", "DE"),
                suggestions.single(),
            )
            assertEquals("Main Street 12, 10115 Berlin", suggestions.single().toString())
            assertEquals(
                "Main Street 12",
                AddressSuggestion("Main Street 12", null, null, null, null).toString(),
            )
        } finally {
            server.shutdown()
        }
    }
}
