package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.NostrKeyPair
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportPublisherTest {
    private val submission = IssueReportSubmission(
        payloadJson = "{}",
        privateKeyHex = NostrKeyPair.generate().hexSec
    )
    private val configuration = IssueReportConfiguration(
        relay = "wss://buzz.cashu.space",
        channelName = "numo-reports",
        relayTimeoutMs = 1_000,
        overallTimeoutMs = 2_000
    )

    @Test
    fun `accepted event makes publication successful`() = runTest {
        val publisher = IssueReportPublisher { relay, channel, received, _ ->
            assertEquals(configuration.relay, relay)
            assertEquals(configuration.channelName, channel)
            assertEquals(submission, received)
            true
        }

        val result = publisher.publish(submission, configuration)

        assertTrue(result.accepted)
        assertEquals(1, result.acceptedCount)
        assertEquals(1, result.attemptedCount)
    }

    @Test
    fun `rejected event makes publication fail`() = runTest {
        val publisher = IssueReportPublisher { _, _, _, _ -> false }

        val result = publisher.publish(submission, configuration)

        assertFalse(result.accepted)
        assertEquals(0, result.acceptedCount)
        assertEquals(1, result.attemptedCount)
    }
}
