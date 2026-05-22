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
        val limitType: LimitType = LimitType.NONE,
        val isBolt11Supported: Boolean = true
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
                isValid = true,
                minAmount = null,
                maxAmount = null,
                limitType = LimitType.NONE,
                isBolt11Supported = false
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

        if (bolt11Method == null || bolt11Method.disabled) {
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null,
                limitType = LimitType.NONE,
                isBolt11Supported = false
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