package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.Nip59
import com.electricdreams.numo.nostr.NostrEvent
import com.electricdreams.numo.nostr.NostrKeyPair
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class IssueReportBuilderTest {
    private val gson = Gson()
    private val receiver = NostrKeyPair.generate()
    private val input = IssueReportInput(
        description = "The scanner closed",
        appVersion = "1.8",
        appBuild = "23",
        platform = "Android",
        osVersion = "16",
        deviceModel = "Pixel 9"
    )

    @Test
    fun `constructed report uses nip17 layers and decrypts for recipient`() {
        val nowMillis = 1_784_547_296_123L
        val submission = IssueReportBuilder(
            secureRandom = SecureRandom(byteArrayOf(1, 2, 3)),
            currentTimeMillis = { nowMillis }
        ).build(input, receiver.hexPub).getOrThrow()

        val giftWrap = gson.fromJson(submission.serializedEvent, NostrEvent::class.java)
        val unwrapped = Nip59.unwrapGiftWrappedDm(giftWrap, receiver.secretKeyBytes)
        val payload = gson.fromJson(unwrapped.rumor.content, IssueReportPayload::class.java)

        assertEquals(IssueReportBuilder.KIND_GIFT_WRAP, giftWrap.kind)
        assertEquals(IssueReportBuilder.KIND_SEAL, unwrapped.seal.kind)
        assertEquals(IssueReportBuilder.KIND_RUMOR, unwrapped.rumor.kind)
        assertEquals(submission.eventId, giftWrap.id)
        assertTrue(giftWrap.verify())
        assertTrue(unwrapped.seal.verify())
        assertNull(unwrapped.rumor.sig)
        assertEquals(receiver.hexPub, giftWrap.tags.single()[1])
        assertEquals(receiver.hexPub, unwrapped.rumor.tags.single()[1])
        assertEquals(unwrapped.seal.pubkey, unwrapped.rumor.pubkey)
        assertNotEquals(giftWrap.pubkey, unwrapped.seal.pubkey)
        assertEquals(IssueReportPayload.SCHEMA, payload.schema)
        assertEquals(1, payload.schemaVersion)
        assertEquals("2026-07-20T11:34:56.123Z", payload.submittedAt)
        assertEquals(input.description, payload.description)
        assertEquals(input.deviceModel, payload.device.model)
        assertFalse(submission.serializedEvent.contains(input.description))
        assertTrue(
            submission.serializedEvent.toByteArray(StandardCharsets.UTF_8).size <=
                IssueReportValidator.OUTER_EVENT_MAX_BYTES
        )
    }

    @Test
    fun `separately constructed reports use fresh sender and wrapper keys`() {
        val builder = IssueReportBuilder(currentTimeMillis = { 1_784_547_296_123L })

        val first = gson.fromJson(
            builder.build(input, receiver.hexPub).getOrThrow().serializedEvent,
            NostrEvent::class.java
        )
        val second = gson.fromJson(
            builder.build(input, receiver.hexPub).getOrThrow().serializedEvent,
            NostrEvent::class.java
        )
        val firstInner = Nip59.unwrapGiftWrappedDm(first, receiver.secretKeyBytes)
        val secondInner = Nip59.unwrapGiftWrappedDm(second, receiver.secretKeyBytes)

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.pubkey, second.pubkey)
        assertNotEquals(firstInner.seal.pubkey, secondInner.seal.pubkey)
    }
}
