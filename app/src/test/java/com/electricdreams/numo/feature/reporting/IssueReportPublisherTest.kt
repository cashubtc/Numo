package com.electricdreams.numo.feature.reporting

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class IssueReportPublisherTest {
    private val submission = IssueReportSubmission(
        serializedEvent = "{}",
        eventId = "a".repeat(64)
    )
    private val configuration = IssueReportConfiguration(
        recipientPublicKey = "b".repeat(64),
        relays = listOf("wss://one.example", "wss://two.example"),
        relayTimeoutMs = 1_000,
        overallTimeoutMs = 2_000
    )

    @Test
    fun `one accepted relay makes publication successful and attempts all relays`() = runTest {
        val attempted = ConcurrentHashMap.newKeySet<String>()
        val publisher = IssueReportPublisher { relay, received, _ ->
            attempted.add(relay)
            assertEquals(submission, received)
            relay.contains("two")
        }

        val result = publisher.publish(submission, configuration)

        assertTrue(result.accepted)
        assertEquals(1, result.acceptedCount)
        assertEquals(configuration.relays.toSet(), attempted)
    }

    @Test
    fun `no accepted relays makes publication fail`() = runTest {
        val publisher = IssueReportPublisher { _, _, _ -> false }

        val result = publisher.publish(submission, configuration)

        assertFalse(result.accepted)
        assertEquals(0, result.acceptedCount)
        assertEquals(2, result.attemptedCount)
    }
}
