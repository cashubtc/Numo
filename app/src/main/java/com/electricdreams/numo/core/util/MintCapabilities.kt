package com.electricdreams.numo.core.util

import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.UnitId
import java.util.Locale

enum class MintOperation { MINT, MELT }

/** One enabled payment route advertised by a particular mint. Amounts use its atomic unit. */
data class MintCapability(
    val mintUrl: String,
    val unit: UnitId,
    val operation: MintOperation,
    val method: String,
    val minAmount: Long?,
    val maxAmount: Long?,
) {
    fun allowsAmount(amount: Long): Boolean = amount >= 0 &&
        (minAmount == null || minAmount <= 0 || amount >= minAmount) &&
        (maxAmount == null || maxAmount <= 0 || amount <= maxAmount)
}

/** NUT-04 and NUT-05 capabilities. Unit display categories never imply method support. */
class MintCapabilities(
    private val mintUrl: String,
    private val limits: CashuWalletManager.MintLimits?,
) {
    fun find(unit: UnitId, operation: MintOperation, method: String): MintCapability? {
        if (unit.isReserved) return null
        val methodId = canonicalMethod(method)
        if (!methodId.matches(Regex("[a-z0-9_-]+"))) return null
        val methods = when (operation) {
            MintOperation.MINT -> limits?.mintMethods
            MintOperation.MELT -> limits?.meltMethods
        }
        val settings = methods?.firstOrNull {
            !it.disabled && UnitId.ofOrNull(it.unit) == unit &&
                canonicalMethod(it.method) == methodId
        } ?: return null
        return MintCapability(
            mintUrl, unit, operation, methodId, settings.minAmount, settings.maxAmount,
        )
    }

    companion object {
        const val BOLT11 = "bolt11"

        // Older caches stored the CDK singleton's JVM toString instead of the wire identifier.
        private val legacyMethod = Regex(
            "org\\.cashudevkit\\.PaymentMethod[$](Bolt11|Bolt12)@[0-9a-fA-F]+",
        )

        internal fun canonicalMethod(method: String): String {
            val legacy = legacyMethod.matchEntire(method)?.groupValues?.get(1)
            return (legacy ?: method).lowercase(Locale.ROOT)
        }
    }
}
