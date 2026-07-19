package com.electricdreams.numo.feature.withdraw

/**
 * Detects what kind of Lightning destination a raw user input represents.
 *
 * Address validation is delegated so this stays pure logic, unit-testable
 * without Android dependencies.
 */
object WithdrawInputParser {

    sealed class Result {
        data class Bolt11(val invoice: String) : Result()
        data class LightningAddress(val address: String) : Result()
        object Invalid : Result()
    }

    private val BOLT11_PREFIXES = listOf("lnbc", "lntb", "lnbcrt")
    private const val LIGHTNING_URI_PREFIX = "lightning:"

    fun parse(raw: String, isValidAddress: (String) -> Boolean): Result {
        var input = raw.trim()
        if (input.startsWith(LIGHTNING_URI_PREFIX, ignoreCase = true)) {
            input = input.substring(LIGHTNING_URI_PREFIX.length).trim()
        }
        if (input.isBlank()) return Result.Invalid

        val lower = input.lowercase()
        if (BOLT11_PREFIXES.any { lower.startsWith(it) }) {
            return Result.Bolt11(input)
        }
        return if (isValidAddress(input)) Result.LightningAddress(input) else Result.Invalid
    }
}
