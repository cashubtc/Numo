package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.BuildConfig
import java.net.URI

data class IssueReportConfiguration(
    val relay: String,
    val channelName: String,
    val relayTimeoutMs: Long,
    val overallTimeoutMs: Long
) {
    fun validate(): Result<IssueReportConfiguration> = runCatching {
        val parsed = runCatching { URI(relay) }.getOrNull()
        require(
            parsed != null && parsed.scheme == "wss" &&
                !parsed.host.isNullOrBlank() && parsed.userInfo == null
        ) { "Relay URL must be an absolute wss URL" }
        require(channelName.matches(Regex("^[a-z0-9][a-z0-9-]{0,63}$"))) {
            "Channel name is invalid"
        }
        require(relayTimeoutMs > 0 && overallTimeoutMs > 0) { "Timeouts must be positive" }
        this
    }

    companion object {
        fun fromBuildConfig(): IssueReportConfiguration = IssueReportConfiguration(
            relay = BuildConfig.ISSUE_REPORT_RELAY.trim(),
            channelName = BuildConfig.ISSUE_REPORT_CHANNEL.trim(),
            relayTimeoutMs = BuildConfig.ISSUE_REPORT_RELAY_TIMEOUT_MS,
            overallTimeoutMs = BuildConfig.ISSUE_REPORT_OVERALL_TIMEOUT_MS
        )
    }
}
