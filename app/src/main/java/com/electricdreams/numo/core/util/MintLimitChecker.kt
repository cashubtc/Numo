package com.electricdreams.numo.core.util

import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager

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
        val limitType: LimitType = LimitType.NONE
    )

    fun checkMintLimits(amount: Long, mintLimits: CashuWalletManager.MintLimits?): LimitCheckResult {
        return checkMintLimitsWithTip(amount, 0, mintLimits)
    }
    
    /**
     * Check if amount + tip is within mint limits.
     * @param amount The base payment amount in sats
     * @param tipAmount The tip amount in sats
     * @param mintLimits The mint limits from the mint info
     */
    fun checkMintLimitsWithTip(amount: Long, tipAmount: Long, mintLimits: CashuWalletManager.MintLimits?): LimitCheckResult {
        val totalAmount = amount + tipAmount
        
        if (mintLimits == null) {
            return LimitCheckResult(
                isValid = false,
                minAmount = null,
                maxAmount = null,
                limitType = LimitType.DISABLED
            )
        }

        val bolt11Method = mintLimits.mintMethods.find { method ->
            val methodStr = method.method
            val unitStr = method.unit
            val methodMatch = methodStr.equals("bolt11", ignoreCase = true) ||
                methodStr.contains("Bolt11") || methodStr.contains("bolt11")
            val unitMatch = unitStr.equals("sat", ignoreCase = true) ||
                unitStr.equals("SAT", ignoreCase = true) || unitStr.contains("Sat")
            methodMatch && unitMatch
        }

        if (bolt11Method == null) {
            return LimitCheckResult(
                isValid = false,
                minAmount = null,
                maxAmount = null,
                limitType = LimitType.DISABLED
            )
        }

        if (bolt11Method.disabled) {
            return LimitCheckResult(
                isValid = false,
                minAmount = bolt11Method.minAmount,
                maxAmount = bolt11Method.maxAmount,
                limitType = LimitType.DISABLED
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
                    limitType = LimitType.MIN
                )
            }
        }

        maxLimit?.let { max ->
            if (max > 0 && totalAmount > max) {
                return LimitCheckResult(
                    isValid = false,
                    minAmount = bolt11Method.minAmount,
                    maxAmount = max,
                    limitType = LimitType.MAX
                )
            }
        }

        return LimitCheckResult(
            isValid = true,
            minAmount = bolt11Method.minAmount,
            maxAmount = bolt11Method.maxAmount
        )
    }
}