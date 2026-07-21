package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.NostrKeyPair
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportConfigurationTest {
    @Test
    fun `valid configuration requires lowercase key and secure unique relays`() {
        val configuration = IssueReportConfiguration(
            recipientPublicKey = NostrKeyPair.generate().hexPub,
            relays = listOf("wss://relay.example"),
            relayTimeoutMs = 1_000,
            overallTimeoutMs = 2_000
        )

        assertTrue(configuration.validate().isSuccess)
    }

    @Test
    fun `insecure relay is rejected`() {
        val configuration = IssueReportConfiguration(
            recipientPublicKey = NostrKeyPair.generate().hexPub,
            relays = listOf("ws://relay.example"),
            relayTimeoutMs = 1_000,
            overallTimeoutMs = 2_000
        )

        assertTrue(configuration.validate().isFailure)
    }

    @Test
    fun `missing recipient key is rejected`() {
        val configuration = IssueReportConfiguration(
            recipientPublicKey = "",
            relays = listOf("wss://relay.example"),
            relayTimeoutMs = 1_000,
            overallTimeoutMs = 2_000
        )

        assertTrue(configuration.validate().isFailure)
    }

    @Test
    fun `hex value that is not a curve point is rejected`() {
        val configuration = IssueReportConfiguration(
            recipientPublicKey = "f".repeat(64),
            relays = listOf("wss://relay.example"),
            relayTimeoutMs = 1_000,
            overallTimeoutMs = 2_000
        )

        assertTrue(configuration.validate().isFailure)
    }
}
