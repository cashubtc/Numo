/**
 * Developer logging helper for wallet activity.
 *
 * This logger persists wallet activity to [WalletLogStore] so it can
 * be inspected from the in-app Developer Settings.
 */
package com.electricdreams.numo.core.dev

object WalletLogger {

    /**
     * Log a wallet activity and persist it to [WalletLogStore].
     */
    @JvmStatic
    fun log(direction: String, amount: Long, mintUrl: String, message: String) {
        WalletLogStore.appendEntry(
            direction = direction,
            amount = amount,
            mintUrl = mintUrl,
            message = message
        )
    }
}
