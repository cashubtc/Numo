package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.nostr.NostrKeyPair
import java.net.URI

data class IssueReportConfiguration(
    val recipientPublicKey: String,
    val relays: List<String>,
    val relayTimeoutMs: Long,
    val overallTimeoutMs: Long
) {
    fun validate(): Result<IssueReportConfiguration> = runCatching {
        require(NostrKeyPair.isValidPublicKeyHex(recipientPublicKey)) {
            "Recipient public key is unavailable"
        }
        require(relays.isNotEmpty() && relays.size <= MAX_RELAYS) {
            "Relay count must be between 1 and $MAX_RELAYS"
        }
        require(relays.distinct().size == relays.size) { "Relay URLs must be unique" }
        relays.forEach { relay ->
            val parsed = runCatching { URI(relay) }.getOrNull()
            require(
                parsed != null && parsed.scheme == "wss" &&
                    !parsed.host.isNullOrBlank() && parsed.userInfo == null
            ) { "Relay URL must be an absolute wss URL" }
        }
        require(relayTimeoutMs > 0 && overallTimeoutMs > 0) { "Timeouts must be positive" }
        this
    }

    companion object {
        private const val MAX_RELAYS = 3

        fun fromBuildConfig(): IssueReportConfiguration = IssueReportConfiguration(
            recipientPublicKey = BuildConfig.ISSUE_REPORT_RECIPIENT_PUBKEY.trim(),
            relays = BuildConfig.ISSUE_REPORT_RELAYS.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty),
            relayTimeoutMs = BuildConfig.ISSUE_REPORT_RELAY_TIMEOUT_MS,
            overallTimeoutMs = BuildConfig.ISSUE_REPORT_OVERALL_TIMEOUT_MS
        )
    }
}
