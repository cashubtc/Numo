package com.electricdreams.numo.feature.withdraw

import androidx.annotation.StringRes
import com.electricdreams.numo.R

/**
 * Translates raw wallet/CDK error messages (often FFI-wrapped, e.g.
 * "code=50000, errorMessage=...") into user-facing string resources.
 *
 * Pure logic — callers resolve the result via getString(messageRes) or
 * getString(messageRes, detail) when [MappedError.detail] is non-null.
 */
object WithdrawErrorMapper {

    data class MappedError(@StringRes val messageRes: Int, val detail: String? = null)

    private val FFI_WRAPPER = Regex("""code=\d+,\s*errorMessage=(.*)""", RegexOption.DOT_MATCHES_ALL)

    fun map(rawMessage: String?): MappedError {
        val cleaned = unwrap(rawMessage)
        if (cleaned.isBlank()) {
            return MappedError(R.string.withdraw_error_generic)
        }

        val lower = cleaned.lowercase()
        return when {
            "description hash does not match" in lower ->
                MappedError(R.string.withdraw_error_lnurl_incompatible)

            "invoice amount" in lower && "does not match" in lower ->
                MappedError(R.string.withdraw_error_lnurl_wrong_amount)

            "insufficient" in lower ->
                MappedError(R.string.withdraw_error_insufficient_short)

            "expired" in lower ->
                MappedError(R.string.withdraw_error_expired)

            "already paid" in lower ->
                MappedError(R.string.withdraw_error_already_paid)

            listOf("timeout", "timed out", "connection", "network", "unreachable", "failed to connect")
                .any { it in lower } ->
                MappedError(R.string.withdraw_error_network)

            else ->
                MappedError(R.string.withdraw_error_generic_detail, cleaned)
        }
    }

    /** Map and resolve to a display string in one step. */
    fun resolve(context: android.content.Context, rawMessage: String?): String {
        val mapped = map(rawMessage)
        return if (mapped.detail != null) {
            context.getString(mapped.messageRes, mapped.detail)
        } else {
            context.getString(mapped.messageRes)
        }
    }

    /** Strip the FFI "code=NNNNN, errorMessage=" wrapper, if present. */
    private fun unwrap(rawMessage: String?): String {
        val raw = rawMessage?.trim() ?: return ""
        val match = FFI_WRAPPER.find(raw) ?: return raw
        return match.groupValues[1].trim()
    }
}
