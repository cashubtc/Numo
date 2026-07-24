package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.NostrKeyPair
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportBuilderTest {
    private val input = IssueReportInput(
        description = "The scanner closed",
        appVersion = "1.8",
        appBuild = "23",
        platform = "Android",
        osVersion = "16",
        deviceModel = "Pixel 9"
    )

    @Test
    fun `constructed report contains payload and fresh ephemeral key`() {
        val submission = IssueReportBuilder(
            currentTimeMillis = { 1_784_547_296_123L }
        ).build(input).getOrThrow()
        val payload = Gson().fromJson(submission.payloadJson, IssueReportPayload::class.java)

        assertEquals(IssueReportPayload.SCHEMA, payload.schema)
        assertEquals(1, payload.schemaVersion)
        assertEquals("2026-07-20T11:34:56.123Z", payload.submittedAt)
        assertEquals(input.description, payload.description)
        assertEquals(input.deviceModel, payload.device.model)
        assertTrue(submission.privateKeyHex.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(
            NostrKeyPair.fromSecretHex(submission.privateKeyHex).hexSec,
            submission.privateKeyHex
        )
    }

    @Test
    fun `separately constructed reports use fresh keys`() {
        val builder = IssueReportBuilder(currentTimeMillis = { 1_784_547_296_123L })

        val first = builder.build(input).getOrThrow()
        val second = builder.build(input).getOrThrow()

        assertNotEquals(first.privateKeyHex, second.privateKeyHex)
    }
}
