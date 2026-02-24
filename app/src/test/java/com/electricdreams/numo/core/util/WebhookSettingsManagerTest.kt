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
        val result = manager.addEndpoint("example.com/hook")

        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, result)
        assertEquals(listOf("https://example.com/hook"), manager.getEndpoints())
    }

    @Test
    fun `addEndpoint rejects duplicate normalized url`() {
        manager.addEndpoint("https://Example.com/hook/")
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
        manager.addEndpoint("https://example.com/hook")

        val result = manager.updateEndpoint(
            currentEndpoint = "https://example.com/hook",
            newRawUrl = "api.example.com/events",
        )

        assertEquals(WebhookSettingsManager.SaveResult.SUCCESS, result)
        assertEquals(listOf("https://api.example.com/events"), manager.getEndpoints())
    }

    @Test
    fun `removeEndpoint removes stored endpoint`() {
        manager.addEndpoint("https://example.com/hook")

        val removed = manager.removeEndpoint("example.com/hook")

        assertTrue(removed)
        assertTrue(manager.getEndpoints().isEmpty())
    }
}
