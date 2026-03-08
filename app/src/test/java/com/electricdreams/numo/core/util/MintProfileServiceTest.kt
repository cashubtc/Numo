package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class MintProfileServiceTest {

    private lateinit var context: Context
    private lateinit var mintManager: MintManager
    private lateinit var mintProfileService: MintProfileService
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        context.getSharedPreferences("MintPreferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        resetSingleton(MintManager.Companion, "instance")
        resetSingleton(MintProfileService.Companion, "instance")

        mintManager = MintManager.getInstance(context)
        mintProfileService = MintProfileService.getInstance(context)

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `validateMintUrl returns success for healthy v1 info endpoint`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"name\":\"Coinos\"}"),
        )

        val mintUrl = server.url("/").toString().removeSuffix("/")
        val result = mintProfileService.validateMintUrl(mintUrl)

        assertTrue(result.isValid)
        assertEquals(mintUrl, result.normalizedUrl)
        assertNull(result.errorType)
    }

    @Test
    fun `fetchAndStoreMintProfile stores metadata and icon`() = runBlocking {
        val mintUrl = server.url("/").toString().removeSuffix("/")
        val iconUrl = "$mintUrl/icon.png"

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"name\":\"Test Mint\",\"icon_url\":\"$iconUrl\"}"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(iconBytes())),
        )

        val result = mintProfileService.fetchAndStoreMintProfile(mintUrl)

        assertTrue(result.success)
        assertEquals("Test Mint", result.displayName)
        assertTrue(result.iconCached)
        assertEquals("Test Mint", mintManager.getMintDisplayName(mintUrl))
        assertNotNull(MintIconCache.getCachedIconFile(mintUrl))
    }

    @Test
    fun `validateMintUrl rejects malformed URL`() = runBlocking {
        val result = mintProfileService.validateMintUrl("not a valid url")

        assertFalse(result.isValid)
        assertNull(result.normalizedUrl)
        assertEquals(MintProfileService.ErrorType.INVALID_URL, result.errorType)
    }

    @Test
    fun `validateMintUrl reports network failure when host unreachable`() = runBlocking {
        val result = mintProfileService.validateMintUrl("http://127.0.0.1:1")

        assertFalse(result.isValid)
        assertEquals(MintProfileService.ErrorType.NETWORK, result.errorType)
    }

    @Test
    fun `fetchAndStoreMintProfile handles missing name and icon`() = runBlocking {
        val mintUrl = server.url("/").toString().removeSuffix("/")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}"),
        )

        val result = mintProfileService.fetchAndStoreMintProfile(mintUrl)

        assertTrue(result.success)
        assertNull(result.displayName)
        assertFalse(result.iconCached)
    }

    private fun resetSingleton(target: Any, fieldName: String) {
        val clazz = target::class.java
        var current: Class<*>? = clazz
        var field: Field? = null
        while (current != null && field == null) {
            try {
                field = current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }

        field?.let {
            it.isAccessible = true
            it.set(target, null)
        }
    }

    private fun iconBytes(): ByteArray {
        val base64Png =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7Vf+kAAAAASUVORK5CYII="
        return Base64.getDecoder().decode(base64Png)
    }
}
