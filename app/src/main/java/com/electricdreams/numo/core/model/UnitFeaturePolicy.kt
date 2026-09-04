package com.electricdreams.numo.core.model

/**
 * Explicit rollout policy for features whose implementation currently assumes BOLT11 semantics.
 * Receiving and storing ecash is not restricted by this policy.
 */
object UnitFeaturePolicy {

    @JvmStatic
    fun supportsUnknownMintSwap(unit: UnitId): Boolean = supportsBolt11Automation(unit)

    @JvmStatic
    fun supportsAutoWithdraw(unit: UnitId): Boolean = supportsBolt11Automation(unit)

    private fun supportsBolt11Automation(unit: UnitId): Boolean {
        return when (UnitDescriptor.defaultFor(unit).kind) {
            UnitKind.BITCOIN,
            UnitKind.ISO_4217,
            UnitKind.STABLECOIN -> true

            UnitKind.CUSTOM,
            UnitKind.RESERVED -> false
        }
    }
}
