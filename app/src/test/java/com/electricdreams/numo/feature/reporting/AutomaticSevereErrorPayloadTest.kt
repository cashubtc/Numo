package com.electricdreams.numo.feature.reporting

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticSevereErrorPayloadTest {
    private val gson = Gson()

    @Test
    fun `automatic payload excludes local token and uses an ephemeral key`() {
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
        val submission = IssueReportBuilder(currentTimeMillis = { 1_784_547_300_000L })
            .buildPayload(payloadJson)
            .getOrThrow()

        assertEquals(payloadJson, submission.payloadJson)
        assertEquals("automatic-severe-error", payload.reportType)
        assertFalse(payloadJson.contains(pending.localToken))
        assertTrue(submission.privateKeyHex.matches(Regex("^[0-9a-f]{64}$")))
    }
}
