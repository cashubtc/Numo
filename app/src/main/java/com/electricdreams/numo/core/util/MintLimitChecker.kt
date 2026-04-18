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
        Log.d(TAG, "Checking limits for amount $amount, mintLimits: $mintLimits")
        
        if (mintLimits == null) {
            Log.d(TAG, "No mint limits available, allowing amount")
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
            Log.d(TAG, "No bolt11/sat method found in mint limits, methods: ${mintLimits.mintMethods}")
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null
            )
        }

        Log.d(TAG, "Found bolt11/sat method: min=${bolt11Method.minAmount}, max=${bolt11Method.maxAmount}, disabled=${bolt11Method.disabled}")

        if (bolt11Method.disabled) {
            Log.d(TAG, "Minting is disabled for this mint")
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
        
        Log.d(TAG, "Checking limits: minLimit=$minLimit, maxLimit=$maxLimit")
        
        // If both are 0 or null, treat as no limits
        if ((minLimit == null || minLimit == 0L) && (maxLimit == null || maxLimit == 0L)) {
            Log.d(TAG, "Mint has no limits (both 0 or null)")
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null
            )
        }

        minLimit?.let { min ->
            // Only enforce min if it's > 0 (0 means no minimum)
            if (min > 0 && amount < min) {
                Log.d(TAG, "Amount $amount is below minimum $min")
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
                Log.d(TAG, "Amount $amount exceeds maximum $max")
                return LimitCheckResult(
                    isValid = false,
                    minAmount = bolt11Method.minAmount,
                    maxAmount = max,
                    limitType = LimitType.MAX
                )
            }
        }

        Log.d(TAG, "Amount $amount is within limits (min: $minLimit, max: $maxLimit)")
        return LimitCheckResult(
            isValid = true,
            minAmount = bolt11Method.minAmount,
            maxAmount = bolt11Method.maxAmount
        )
    }
}