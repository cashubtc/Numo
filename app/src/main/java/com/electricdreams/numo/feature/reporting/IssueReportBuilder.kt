package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.NostrKeyPair
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class IssueReportBuilder(
    private val gson: Gson = Gson(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    fun build(input: IssueReportInput): Result<IssueReportSubmission> = runCatching {
        val validated = when (val result = IssueReportValidator.validate(input)) {
            is IssueReportValidationResult.Valid -> result.input
            is IssueReportValidationResult.Invalid -> {
                throw IllegalArgumentException("Invalid report field: ${result.field}")
            }
        }
        val nowMillis = currentTimeMillis()
        val payload = IssueReportPayload(
            submittedAt = formatUtc(nowMillis),
            description = validated.description,
            app = IssueReportPayload.AppContext(
                version = validated.appVersion,
                build = validated.appBuild
            ),
            device = IssueReportPayload.DeviceContext(
                platform = validated.platform,
                osVersion = validated.osVersion,
                model = validated.deviceModel
            )
        )
        val payloadJson = gson.toJson(payload)
        buildPayload(payloadJson).getOrThrow()
    }

    fun buildPayload(payloadJson: String): Result<IssueReportSubmission> = runCatching {
        require(IssueReportValidator.utf8Size(payloadJson) <= IssueReportValidator.PAYLOAD_MAX_BYTES) {
            "Report payload is too large"
        }
        val sender = NostrKeyPair.generate()
        val senderSecret = sender.secretKeyBytes
        try {
            IssueReportSubmission(
                payloadJson = payloadJson,
                privateKeyHex = sender.hexSec
            )
        } finally {
            senderSecret.fill(0)
        }
    }

    private fun formatUtc(timestamp: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))
}
