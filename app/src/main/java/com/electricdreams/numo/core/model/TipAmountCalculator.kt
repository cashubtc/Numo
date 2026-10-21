package com.electricdreams.numo.core.model

import java.math.BigInteger

/** Checked arithmetic for tips expressed in the charge unit's atomic denomination. */
object TipAmountCalculator {

    @JvmStatic
    fun percentageTip(baseAmount: Long, percentage: Int): Long {
        require(baseAmount >= 0L) { "Base amount cannot be negative" }
        require(percentage >= 0) { "Tip percentage cannot be negative" }
        return BigInteger.valueOf(baseAmount)
            .multiply(BigInteger.valueOf(percentage.toLong()))
            .divide(BigInteger.valueOf(100L))
            .longValueExact()
    }

    @JvmStatic
    fun total(baseAmount: Long, tipAmount: Long): Long {
        require(baseAmount >= 0L) { "Base amount cannot be negative" }
        require(tipAmount >= 0L) { "Tip amount cannot be negative" }
        return Math.addExact(baseAmount, tipAmount)
    }
}
