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
            Log.d(TAG, "No bolt11/sat method found in mint limits")
            return LimitCheckResult(
                isValid = true,
                minAmount = null,
                maxAmount = null
            )
        }

        if (bolt11Method.disabled) {
            Log.d(TAG, "Minting is disabled for this mint")
            return LimitCheckResult(
                isValid = false,
                minAmount = bolt11Method.minAmount,
                maxAmount = bolt11Method.maxAmount,
                limitType = LimitType.DISABLED
            )
        }

        bolt11Method.minAmount?.let { min ->
            if (amount < min) {
                Log.d(TAG, "Amount $amount is below minimum $min")
                return LimitCheckResult(
                    isValid = false,
                    minAmount = min,
                    maxAmount = bolt11Method.maxAmount,
                    limitType = LimitType.MIN
                )
            }
        }

        bolt11Method.maxAmount?.let { max ->
            if (amount > max) {
                Log.d(TAG, "Amount $amount exceeds maximum $max")
                return LimitCheckResult(
                    isValid = false,
                    minAmount = bolt11Method.minAmount,
                    maxAmount = max,
                    limitType = LimitType.MAX
                )
            }
        }

        Log.d(TAG, "Amount $amount is within limits")
        return LimitCheckResult(
            isValid = true,
            minAmount = bolt11Method.minAmount,
            maxAmount = bolt11Method.maxAmount
        )
    }
}