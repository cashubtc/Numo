package com.electricdreams.numo.core.util.ur

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [UrBytesEncoder] against golden vectors produced by the reference JS
 * implementation `@gandlaf21/bc-ur` 1.1.12 (used by cashu.me), so frames emitted by
 * Numo can be reassembled by other wallets.
 */
class UrBytesEncoderTest {

    private data class VectorCase(
        val message: String,
        val maxFragmentLength: Int,
        val parts: List<String>,
    )

    private fun loadVectors(): Map<String, VectorCase> {
        val stream = javaClass.classLoader?.getResourceAsStream("ur_bytes_vectors.json")
            ?: error("ur_bytes_vectors.json not found on the test classpath")
        val json = stream.bufferedReader().use { it.readText() }
        val type = object : TypeToken<Map<String, VectorCase>>() {}.type
        return Gson().fromJson(json, type)
    }

    @Test
    fun `parts match the reference JS implementation`() {
        for ((name, vector) in loadVectors()) {
            val encoder = UrBytesEncoder(
                vector.message.toByteArray(Charsets.UTF_8),
                vector.maxFragmentLength,
            )
            assertEquals("fragment count mismatch for $name", vector.parts.size, encoder.fragmentCount)
            vector.parts.forEachIndexed { index, expected ->
                // The reference emits lowercase; we uppercase for QR alphanumeric mode.
                assertEquals("part ${index + 1} mismatch for $name", expected.uppercase(), encoder.nextPart())
            }
        }
    }

    @Test
    fun `cycling wraps around to the first fragment`() {
        val encoder = UrBytesEncoder(ByteArray(1000) { (it % 251).toByte() })
        assertTrue(encoder.fragmentCount > 1)
        val firstCycle = (1..encoder.fragmentCount).map { encoder.nextPart() }
        repeat(encoder.fragmentCount) { i ->
            assertEquals(firstCycle[i], encoder.nextPart())
        }
    }

    @Test
    fun `frame strings have the expected ur bytes format`() {
        val encoder = UrBytesEncoder("cashuBtesttoken".toByteArray(Charsets.UTF_8), maxFragmentLength = 10)
        assertTrue(encoder.fragmentCount > 1)
        val part = encoder.nextPart()
        assertTrue(part.startsWith("UR:BYTES/1-"))
        val seqComponent = part.removePrefix("UR:BYTES/").substringBefore('/')
        val (seqNum, seqLength) = seqComponent.split('-')
        assertEquals("1", seqNum)
        assertEquals(encoder.fragmentCount, seqLength.toInt())
        assertEquals(1, encoder.currentSeqNum)
    }

    @Test
    fun `single fragment message produces a single part ur`() {
        val encoder = UrBytesEncoder(ByteArray(5) { 1 })
        assertTrue(encoder.isSinglePart)
        val part = encoder.nextPart()
        assertTrue(part.startsWith("UR:BYTES/"))
        assertFalse(part.removePrefix("UR:BYTES/").contains('/'))
    }

    @Test
    fun `crc32 matches the standard check value`() {
        // CRC32 of "123456789" is the well-known check value 0xCBF43926
        assertEquals(0xCBF43926L, Bytewords.crc32("123456789".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `nominal fragment length matches the reference algorithm`() {
        assertEquals(143, UrBytesEncoder.findNominalFragmentLength(1000, 10, 150))
        assertEquals(150, UrBytesEncoder.findNominalFragmentLength(150, 10, 150))
        assertEquals(1, UrBytesEncoder.findNominalFragmentLength(1, 10, 150))
    }
}
