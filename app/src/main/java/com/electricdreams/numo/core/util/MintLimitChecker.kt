package com.electricdreams.numo.core.util

import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.UnitId

object MintLimitChecker {

    private const val TAG = "MintLimitChecker"

    enum class LimitType {
        NONE,
        MIN,
        MAX,
        DISABLED
    }

    data class LimitCheckResult(
        val isValid: Boolean,
        val minAmount: Long?,
        val maxAmount: Long?,
        val limitType: LimitType = LimitType.NONE,
        val isBolt11Supported: Boolean = true
    )

    fun checkMintLimits(
        amount: Long,
        mintLimits: CashuWalletManager.MintLimits?,
        preferredUnit: String = "sat",
    ): LimitCheckResult {
        return checkMintLimitsWithTip(amount, 0, mintLimits, preferredUnit)
    }
    
    /**
     * Check if amount + tip is within mint limits.
     * @param amount The base payment amount
     * @param tipAmount The tip amount
     * @param mintLimits The mint limits from the mint info
     * @param preferredUnit The unit to check against
     */
    fun checkMintLimitsWithTip(
        amount: Long,
        tipAmount: Long,
        mintLimits: CashuWalletManager.MintLimits?,
        preferredUnit: String = "sat",
    ): LimitCheckResult {
        if (mintLimits == null) {
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null,
                limitType = LimitType.NONE,
                isBolt11Supported = false
            )
        }

        val expectedUnit = UnitId.ofOrNull(preferredUnit)
        if (expectedUnit == null || expectedUnit.isReserved) {
            Log.e(TAG, "Cannot check mint limits for invalid unit: $preferredUnit")
            return LimitCheckResult(
                isValid = false,
                minAmount = null,
                maxAmount = null,
                limitType = LimitType.DISABLED,
                isBolt11Supported = false,
            )
        }

        val bolt11Method = mintLimits.mintMethods.find { method ->
            val methodStr = method.method
            val methodMatch = methodStr.equals("bolt11", ignoreCase = true) ||
                methodStr.contains("Bolt11") || methodStr.contains("bolt11")
            val unitMatch = UnitId.ofOrNull(method.unit) == expectedUnit
            methodMatch && unitMatch
        }

        if (bolt11Method == null || bolt11Method.disabled) {
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null,
                limitType = LimitType.NONE,
                isBolt11Supported = false
            )
        }

        if (amount < 0L || tipAmount < 0L) {
            return LimitCheckResult(
                isValid = false,
                minAmount = bolt11Method.minAmount,
                maxAmount = bolt11Method.maxAmount,
                limitType = LimitType.MIN,
                isBolt11Supported = true,
            )
        }

        val totalAmount = try {
            Math.addExact(amount, tipAmount)
        } catch (e: ArithmeticException) {
            Log.e(TAG, "Payment amount overflow for unit $expectedUnit", e)
            return LimitCheckResult(
                isValid = false,
                minAmount = bolt11Method.minAmount,
                maxAmount = bolt11Method.maxAmount,
                limitType = LimitType.MAX,
                isBolt11Supported = true,
            )
        }

        val minLimit = bolt11Method.minAmount
        val maxLimit = bolt11Method.maxAmount
        
        if ((minLimit == null || minLimit == 0L) && (maxLimit == null || maxLimit == 0L)) {
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null
            )
        }

        minLimit?.let { min ->
            if (min > 0 && totalAmount < min) {
                return LimitCheckResult(
                    isValid = false,
                    minAmount = min,
                    maxAmount = bolt11Method.maxAmount,
                    limitType = LimitType.MIN,
                    isBolt11Supported = true
                )
            }
        }

        maxLimit?.let { max ->
            if (max > 0 && totalAmount > max) {
                return LimitCheckResult(
                    isValid = false,
                    minAmount = bolt11Method.minAmount,
                    maxAmount = max,
                    limitType = LimitType.MAX,
                    isBolt11Supported = true
                )
            }
        }

        return LimitCheckResult(
            isValid = true,
            minAmount = bolt11Method.minAmount,
            maxAmount = bolt11Method.maxAmount,
            isBolt11Supported = true
        )
    }
}
