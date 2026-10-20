package com.electricdreams.numo.feature.offline

import android.content.Context
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsManager

/**
 * The explainer's answer to "can I still sell right now?": what needs internet comes first, then
 * what keeps working offline, adapted to how this merchant has Numo set up.
 */
object OfflineCapabilities {

    enum class Feature {
        NEW_CHARGES,
        CATALOG,
        WITHDRAWALS,
        AUTO_WITHDRAW,
        PAYMENT_ON_SCREEN,
        HISTORY,
        EXCHANGE_RATE,
    }

    enum class Status { AVAILABLE, DEGRADED, UNAVAILABLE }

    /** Declared in display order. */
    enum class Group { NEEDS_INTERNET, WORKS_OFFLINE }

    data class Row(
        val feature: Feature,
        val status: Status,
        /** For [Feature.EXCHANGE_RATE]: when the cached price was fetched. */
        val updatedAt: Long? = null,
    ) {
        val group: Group
            get() = if (status == Status.UNAVAILABLE) Group.NEEDS_INTERNET else Group.WORKS_OFFLINE
    }

    data class Config(
        val autoWithdrawEnabled: Boolean,
        /** BTCPay POS mode loads the catalog from the server, so it's gone offline. */
        val usesBtcPayCatalog: Boolean,
        /** Null when no exchange rate was ever cached for the current currency. */
        val priceUpdatedAt: Long?,
        /** Opened from a payment request whose code a customer can still pay. */
        val paymentOnScreen: Boolean = false,
    ) {
        companion object {
            fun from(context: Context, paymentOnScreen: Boolean): Config {
                val prefs = PreferenceStore.app(context)
                val usesBtcPayCatalog = prefs.getBoolean("btcpay_enabled", false) &&
                    !prefs.getString("btcpay_pos_app_id", null).isNullOrBlank()
                return Config(
                    autoWithdrawEnabled =
                        AutoWithdrawSettingsManager.getInstance(context).isGloballyEnabled(),
                    usesBtcPayCatalog = usesBtcPayCatalog,
                    priceUpdatedAt = BitcoinPriceWorker.getInstance(context).getPriceUpdatedAt(),
                    paymentOnScreen = paymentOnScreen,
                )
            }
        }
    }

    /**
     * Rows grouped and in display order. Within a group, rows sharing a status sit together
     * (✓ before !), and among equals what matters most for a sale leads.
     */
    fun rows(config: Config): List<Row> {
        val catalog = Row(
            Feature.CATALOG,
            if (config.usesBtcPayCatalog) Status.UNAVAILABLE else Status.AVAILABLE
        )
        val exchangeRate = if (config.priceUpdatedAt != null) {
            Row(Feature.EXCHANGE_RATE, Status.DEGRADED, config.priceUpdatedAt)
        } else {
            Row(Feature.EXCHANGE_RATE, Status.UNAVAILABLE)
        }
        return listOfNotNull(
            Row(Feature.NEW_CHARGES, Status.UNAVAILABLE),
            Row(Feature.PAYMENT_ON_SCREEN, Status.DEGRADED).takeIf { config.paymentOnScreen },
            catalog,
            Row(Feature.WITHDRAWALS, Status.UNAVAILABLE),
            Row(Feature.AUTO_WITHDRAW, Status.UNAVAILABLE).takeIf { config.autoWithdrawEnabled },
            Row(Feature.HISTORY, Status.AVAILABLE),
            exchangeRate,
        ).sortedWith(compareBy({ it.group.ordinal }, { it.status.ordinal }))
    }

    fun groups(config: Config): Map<Group, List<Row>> = rows(config).groupBy { it.group }
}
