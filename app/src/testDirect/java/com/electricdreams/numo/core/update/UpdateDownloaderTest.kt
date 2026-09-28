package com.electricdreams.numo.core.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val bytes = "signed APK content".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun update() = UpdateManifest(26, "1.10", 24, server.url("/app.apk").toString(),
        bytes.size.toLong(), hash, "")
    private fun downloader() = UpdateDownloader(OkHttpClient(), temporary.root)
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `partial download resumes from the persisted offset`() {
        File(temporary.root, "$hash.part").writeBytes(bytes.copyOfRange(0, 5))
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 5-${bytes.size - 1}/${bytes.size}")
            .setBody(Buffer().write(bytes.copyOfRange(5, bytes.size))))
        assertArrayEquals(bytes, downloader().download(update()).readBytes())
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `server ignoring range restarts the download without appending`() {
        File(temporary.root, "$hash.part").writeBytes(bytes.copyOfRange(0, 5))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertArrayEquals(bytes, downloader().download(update()).readBytes())
    }

    @Test
    fun `invalid range never promotes a partial file`() {
        File(temporary.root, "$hash.part").writeBytes(bytes.copyOfRange(0, 5))
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 0-4/${bytes.size}").setBody("wrong"))
        assertThrows(IOException::class.java) { downloader().download(update()) }
        assertFalse(File(temporary.root, "$hash.apk").exists())
    }

    @Test
    fun `checksum mismatch deletes a complete corrupt download`() {
        server.enqueue(MockResponse().setBody("x".repeat(bytes.size)))
        assertThrows(IOException::class.java) { downloader().download(update()) }
        assertFalse(File(temporary.root, "$hash.apk").exists())
        assertFalse(File(temporary.root, "$hash.part").exists())
    }

    @Test
    fun `complete verified download survives process recreation without network`() {
        File(temporary.root, "$hash.apk").writeBytes(bytes)
        assertArrayEquals(bytes, downloader().download(update()).readBytes())
        assertEquals(0, server.requestCount)
    }
}
