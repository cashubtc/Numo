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
        if (mintLimits == null) {
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null
            )
        }

        val bolt11Method = mintLimits.mintMethods.find { 
            it.method.equals("bolt11", ignoreCase = true) && 
            it.unit.equals("sat", ignoreCase = true)
        }

        if (bolt11Method == null) {
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null
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

        // Handle invalid limits (0 or null for both means no limits)
        val minLimit = bolt11Method.minAmount
        val maxLimit = bolt11Method.maxAmount
        
        // If both are 0 or null, treat as no limits
        if ((minLimit == null || minLimit == 0L) && (maxLimit == null || maxLimit == 0L)) {
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null
            )
        }

        minLimit?.let { min ->
            // Only enforce min if it's > 0 (0 means no minimum)
            if (min > 0 && amount < min) {
                return LimitCheckResult(
                    isValid = false,
                    minAmount = min,
                    maxAmount = bolt11Method.maxAmount,
                    limitType = LimitType.MIN
                )
            }
        }

        maxLimit?.let { max ->
            // Only enforce max if it's > 0 (0 means no maximum)
            if (max > 0 && amount > max) {
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