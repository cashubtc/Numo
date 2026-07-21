package com.electricdreams.numo.feature.reporting

import com.electricdreams.numo.nostr.Nip44
import com.electricdreams.numo.nostr.NostrEvent
import com.electricdreams.numo.nostr.NostrEventSigner
import com.electricdreams.numo.nostr.NostrKeyPair
import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class IssueReportBuilder(
    private val gson: Gson = Gson(),
    private val secureRandom: SecureRandom = SecureRandom(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    fun build(
        input: IssueReportInput,
        recipientPublicKey: String
    ): Result<IssueReportSubmission> = runCatching {
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
        buildPayload(payloadJson, recipientPublicKey).getOrThrow()
    }

    fun buildPayload(
        payloadJson: String,
        recipientPublicKey: String
    ): Result<IssueReportSubmission> = runCatching {
        require(IssueReportValidator.utf8Size(payloadJson) <= IssueReportValidator.PAYLOAD_MAX_BYTES) {
            "Report payload is too large"
        }
        val recipientBytes = decodePublicKey(recipientPublicKey)
        val nowSeconds = currentTimeMillis() / 1_000L

        val sender = NostrKeyPair.generate()
        val wrapper = NostrKeyPair.generate()
        val senderSecret = sender.secretKeyBytes
        val wrapperSecret = wrapper.secretKeyBytes
        try {
            val rumor = NostrEvent().apply {
                pubkey = sender.hexPub
                created_at = nowSeconds
                kind = KIND_RUMOR
                tags = mutableListOf(mutableListOf("p", recipientPublicKey))
                content = payloadJson
                id = computeId()
            }

            val senderConversationKey = Nip44.getConversationKey(senderSecret, recipientBytes)
            val encryptedRumor = try {
                Nip44.encrypt(gson.toJson(rumor), senderConversationKey)
            } finally {
                senderConversationKey.fill(0)
            }
            val seal = NostrEvent().apply {
                pubkey = sender.hexPub
                created_at = randomizedPastTimestamp(nowSeconds)
                kind = KIND_SEAL
                tags = mutableListOf()
                content = encryptedRumor
            }
            NostrEventSigner.sign(seal, senderSecret)

            val wrapperConversationKey = Nip44.getConversationKey(wrapperSecret, recipientBytes)
            val encryptedSeal = try {
                Nip44.encrypt(gson.toJson(seal), wrapperConversationKey)
            } finally {
                wrapperConversationKey.fill(0)
            }
            val giftWrap = NostrEvent().apply {
                pubkey = wrapper.hexPub
                created_at = randomizedPastTimestamp(nowSeconds)
                kind = KIND_GIFT_WRAP
                tags = mutableListOf(mutableListOf("p", recipientPublicKey))
                content = encryptedSeal
            }
            NostrEventSigner.sign(giftWrap, wrapperSecret)
            val serialized = gson.toJson(giftWrap)
            require(
                serialized.toByteArray(StandardCharsets.UTF_8).size <=
                    IssueReportValidator.OUTER_EVENT_MAX_BYTES
            ) { "Encrypted report event is too large" }
            IssueReportSubmission(
                serializedEvent = serialized,
                eventId = giftWrap.id
            )
        } finally {
            senderSecret.fill(0)
            wrapperSecret.fill(0)
            recipientBytes.fill(0)
        }
    }

    private fun randomizedPastTimestamp(nowSeconds: Long): Long =
        nowSeconds - secureRandom.nextInt(TIMESTAMP_WINDOW_SECONDS + 1)

    private fun decodePublicKey(value: String): ByteArray {
        require(value.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid recipient public key" }
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun formatUtc(timestamp: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))

    companion object {
        const val KIND_RUMOR = 14
        const val KIND_SEAL = 13
        const val KIND_GIFT_WRAP = 1059
        private const val TIMESTAMP_WINDOW_SECONDS = 172_800
    }
}
