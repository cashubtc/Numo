package com.electricdreams.numo.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrMintDiscoveryTest {

    @Test
    fun `aggregates announcements and keeps latest review per author`() {
        val events = listOf(
            event(
                kind = 38172,
                author = "mint",
                createdAt = 10,
                content = """{"name":"Example Mint"}""",
                tags = listOf(listOf("u", "https://MINT.example/")),
            ),
            event(
                kind = 38000,
                author = "alice",
                createdAt = 11,
                content = "[2/5] old rating",
                tags = recommendationTags("https://mint.example"),
            ),
            event(
                kind = 38000,
                author = "alice",
                createdAt = 12,
                content = "[4 / 5] updated rating",
                tags = recommendationTags("https://mint.example/"),
            ),
            event(
                kind = 38000,
                author = "bob",
                createdAt = 12,
                content = "[5/5] reliable",
                tags = recommendationTags("https://mint.example"),
            ),
        )

        val result = NostrMintDiscovery.aggregate(events, verifyEvents = false)

        assertEquals(1, result.size)
        assertEquals("https://mint.example", result.single().url)
        assertEquals("Example Mint", result.single().name)
        assertEquals(2, result.single().reviewCount)
        assertEquals(4.5, result.single().averageRating ?: 0.0, 0.001)
    }

    @Test
    fun `ignores non cashu recommendations and unsafe URLs`() {
        val events = listOf(
            event(
                kind = 38000,
                author = "alice",
                tags = listOf(
                    listOf("k", "38173"),
                    listOf("u", "https://fedimint.example"),
                ),
            ),
            event(
                kind = 38172,
                author = "mint",
                tags = listOf(listOf("u", "javascript:alert(1)")),
            ),
            event(
                kind = 38172,
                author = "mint",
                tags = listOf(listOf("u", "https://user:pass@mint.example")),
            ),
        )

        assertEquals(
            emptyList<NostrMintDiscovery.MintRecommendation>(),
            NostrMintDiscovery.aggregate(events, verifyEvents = false),
        )
    }

    @Test
    fun `recommendation without rating is still counted`() {
        val result = NostrMintDiscovery.aggregate(
            listOf(
                event(
                    kind = 38000,
                    author = "alice",
                    content = "I use this mint",
                    tags = recommendationTags("https://mint.example"),
                ),
            ),
            verifyEvents = false,
        ).single()

        assertEquals(1, result.reviewCount)
        assertNull(result.averageRating)
    }

    private fun recommendationTags(url: String) = listOf(
        listOf("k", "38172"),
        listOf("u", url, "cashu"),
    )

    private fun event(
        kind: Int,
        author: String,
        createdAt: Long = 1,
        content: String = "",
        tags: List<List<String>>,
    ) = NostrEvent().apply {
        this.kind = kind
        pubkey = author
        created_at = createdAt
        this.content = content
        this.tags = tags
        id = "$kind-$author-$createdAt"
    }
}
