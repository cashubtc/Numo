package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.Nip59
import com.electricdreams.numo.nostr.NostrEvent
import com.electricdreams.numo.nostr.NostrKeyPair
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticSevereErrorPayloadTest {
    private val gson = Gson()

    @Test
    fun `automatic payload excludes local token and encrypts for receiver`() {
        val pending = PendingSevereErrorReport(
            localToken = "must-never-leave-device",
            occurredAtEpochMillis = 1_784_547_296_123L,
            exceptionTypes = listOf("IllegalStateException"),
            appFrames = listOf(
                SanitizedStackFrame("com.electricdreams.numo.Screen", "render", 10)
            )
        )
        val payload = AutomaticSevereErrorPayload.from(
            pending = pending,
            nowMillis = 1_784_547_300_000L
        )
        val payloadJson = gson.toJson(payload)
        val receiver = NostrKeyPair.generate()

        val submission = IssueReportBuilder(currentTimeMillis = { 1_784_547_300_000L })
            .buildPayload(payloadJson, receiver.hexPub)
            .getOrThrow()
        val giftWrap = gson.fromJson(submission.serializedEvent, NostrEvent::class.java)
        val unwrapped = Nip59.unwrapGiftWrappedDm(giftWrap, receiver.secretKeyBytes)

        assertEquals(payloadJson, unwrapped.rumor.content)
        assertEquals("automatic-severe-error", payload.reportType)
        assertFalse(payloadJson.contains(pending.localToken))
        assertFalse(submission.serializedEvent.contains("IllegalStateException"))
    }
}
