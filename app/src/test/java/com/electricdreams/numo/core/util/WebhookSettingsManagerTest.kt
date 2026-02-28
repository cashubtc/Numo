package com.electricdreams.numo.core.util

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WebhookSettingsManagerTest {

    private lateinit var context: Context
    private lateinit var manager: WebhookSettingsManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("WebhookSettings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        val instanceField = WebhookSettingsManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)

        manager = WebhookSettingsManager.getInstance(context)
    }

    @Test
    fun `addEndpoint normalizes and stores endpoint`() {
        val result = manager.addEndpoint("example.com/hook", "Bearer secret")

        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, result)
        assertEquals(
            listOf(
                WebhookSettingsManager.WebhookEndpointConfig(
                    url = "https://example.com/hook",
                    authKey = "Bearer secret",
                ),
            ),
            manager.getEndpoints(),
        )
    }

    @Test
    fun `addEndpoint rejects duplicate normalized url`() {
        manager.addEndpoint("https://Example.com/hook/", "Key One")
        val result = manager.addEndpoint("example.com/hook")

        assertEquals(WebhookSettingsManager.SaveResult.DUPLICATE, result)
        assertEquals(1, manager.getEndpoints().size)
    }

    @Test
    fun `addEndpoint rejects invalid scheme`() {
        val result = manager.addEndpoint("ftp://example.com/hook")

        assertEquals(WebhookSettingsManager.SaveResult.INVALID_URL, result)
        assertTrue(manager.getEndpoints().isEmpty())
    }

    @Test
    fun `updateEndpoint updates an existing endpoint`() {
        manager.addEndpoint("https://example.com/hook", "Bearer old")

        val result = manager.updateEndpoint(
            currentEndpoint = "https://example.com/hook",
            newRawUrl = "api.example.com/events",
            newRawAuthKey = "Bearer new",
        )

        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, result)
        assertEquals(
            listOf(
                WebhookSettingsManager.WebhookEndpointConfig(
                    url = "https://api.example.com/events",
                    authKey = "Bearer new",
                ),
            ),
            manager.getEndpoints(),
        )
    }

    @Test
    fun `removeEndpoint removes stored endpoint`() {
        manager.addEndpoint("https://example.com/hook", "Bearer secret")

        val removed = manager.removeEndpoint("example.com/hook")

        assertTrue(removed)
        assertTrue(manager.getEndpoints().isEmpty())
    }

    @Test
    fun `addEndpoint preserves complex URL components`() {
        val complexUrl = "http://user:pass@example.com:8080/path/to/resource?query=1#fragment"
        val result = manager.addEndpoint(complexUrl)

        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, result)
        assertEquals(
            listOf(
                WebhookSettingsManager.WebhookEndpointConfig(
                    url = complexUrl,
                    authKey = null,
                ),
            ),
            manager.getEndpoints(),
        )
    }

    @Test
    fun `addEndpoint strips trailing slash`() {
        manager.addEndpoint("https://example.com/")
        assertEquals(
            listOf(
                WebhookSettingsManager.WebhookEndpointConfig(
                    url = "https://example.com",
                    authKey = null,
                ),
            ),
            manager.getEndpoints(),
        )
    }

    @Test
    fun `addEndpoint lowercases host and scheme but preserves path casing`() {
        manager.addEndpoint("HTTP://EXAMPLE.COM/Path/To/Resource")
        assertEquals(
            listOf(
                WebhookSettingsManager.WebhookEndpointConfig(
                    url = "http://example.com/Path/To/Resource",
                    authKey = null,
                ),
            ),
            manager.getEndpoints(),
        )
    }

    @Test
    fun `getEndpoints returns empty list when JSON is malformed`() {
        context.getSharedPreferences("WebhookSettings", Context.MODE_PRIVATE)
            .edit()
            .putString("endpoints", "{ invalid_json: ]")
            .apply()

        assertTrue(manager.getEndpoints().isEmpty())
    }

    @Test
    fun `getEndpoints ignores blank entries in JSON`() {
        context.getSharedPreferences("WebhookSettings", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "endpoints",
                """[{"url":"https://example.com"},{"url":""},{"url":"   "}]""",
            )
            .apply()

        assertEquals(
            listOf(
                WebhookSettingsManager.WebhookEndpointConfig(
                    url = "https://example.com",
                    authKey = null,
                ),
            ),
            manager.getEndpoints(),
        )
    }

    @Test
    fun `isValidEndpoint correctly identifies valid and invalid URLs`() {
        assertTrue(manager.isValidEndpoint("example.com"))
        assertTrue(manager.isValidEndpoint("https://example.com/hook"))
        assertTrue(manager.isValidEndpoint("http://example.com:8080"))
        
        // Invalid
        assertTrue(!manager.isValidEndpoint("ftp://example.com"))
        assertTrue(!manager.isValidEndpoint(""))
        assertTrue(!manager.isValidEndpoint("   "))
    }

    @Test
    fun `updateEndpoint returns NOT_FOUND if old endpoint does not exist`() {
        manager.addEndpoint("https://example.com")
        val result = manager.updateEndpoint("nonexistent.com", "https://new.com")
        assertEquals(WebhookSettingsManager.SaveResult.NOT_FOUND, result)
    }

    @Test
    fun `updateEndpoint returns SUCCESS without changing if old and new are the same`() {
        manager.addEndpoint("https://example.com", "Bearer secret")
        val result = manager.updateEndpoint("example.com", "https://example.com/")
        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, result)
        assertEquals(
            listOf(
                WebhookSettingsManager.WebhookEndpointConfig(
                    url = "https://example.com",
                    authKey = "Bearer secret",
                ),
            ),
            manager.getEndpoints(),
        )
    }

    @Test
    fun `updateEndpoint returns DUPLICATE if new endpoint already exists elsewhere`() {
        manager.addEndpoint("https://example.com")
        manager.addEndpoint("https://other.com")
        val result = manager.updateEndpoint("example.com", "other.com")
        assertEquals(WebhookSettingsManager.SaveResult.DUPLICATE, result)
    }

    @Test
    fun `endpoints are serialized correctly to JSON in SharedPreferences`() {
        manager.addEndpoint("https://example.com", "Bearer abc")
        manager.addEndpoint("https://another.com")

        val json = context.getSharedPreferences("WebhookSettings", Context.MODE_PRIVATE)
            .getString("endpoints", null)

        assertEquals(
            """[{"url":"https://example.com","authKey":"Bearer abc"},{"url":"https://another.com"}]""",
            json,
        )
    }

    @Test
    fun `updateAuthKey updates and clears auth key`() {
        manager.addEndpoint("https://example.com/hook", "Bearer secret")

        val updateResult = manager.updateAuthKey("example.com/hook", "ApiKey 123")
        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, updateResult)
        assertEquals("ApiKey 123", manager.getEndpoints().first().authKey)

        val clearResult = manager.updateAuthKey("https://example.com/hook", "  ")
        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, clearResult)
        assertEquals(null, manager.getEndpoints().first().authKey)
    }
}
