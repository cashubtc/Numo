package com.electricdreams.numo.feature.reporting

import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportConfigurationTest {
    @Test
    fun `valid configuration requires secure relay and channel name`() {
        val configuration = configuration()

        assertTrue(configuration.validate().isSuccess)
    }

    @Test
    fun `insecure relay is rejected`() {
        assertTrue(configuration(relay = "ws://relay.example").validate().isFailure)
    }

    @Test
    fun `invalid channel name is rejected`() {
        assertTrue(configuration(channelName = "Numo Reports").validate().isFailure)
    }

    private fun configuration(
        relay: String = "wss://buzz.cashu.space",
        channelName: String = "numo-reports"
    ) = IssueReportConfiguration(
        relay = relay,
        channelName = channelName,
        relayTimeoutMs = 1_000,
        overallTimeoutMs = 2_000
    )
}
