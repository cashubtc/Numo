package com.electricdreams.numo.feature.offline

import android.content.Context
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsManager

/** What still works while offline, adapted to how this merchant has Numo set up. */
object OfflineCapabilities {

    enum class Feature {
        CATALOG,
        HISTORY,
        EXCHANGE_RATE,
        ACCEPT_PAYMENTS,
        WITHDRAWALS,
        AUTO_WITHDRAW,
    }

    /** Declared in display order: available first, then degraded, then unavailable. */
    enum class Status { AVAILABLE, DEGRADED, UNAVAILABLE }

    data class Row(
        val feature: Feature,
        val status: Status,
        /** For [Feature.EXCHANGE_RATE]: when the cached price was fetched. */
        val updatedAt: Long? = null,
    )

    data class Config(
        val autoWithdrawEnabled: Boolean,
        /** BTCPay POS mode loads the catalog from the server, so it's gone offline. */
        val usesBtcPayCatalog: Boolean,
        /** Null when no exchange rate was ever cached for the current currency. */
        val priceUpdatedAt: Long?,
    ) {
        companion object {
            fun from(context: Context): Config {
                val prefs = PreferenceStore.app(context)
                val usesBtcPayCatalog = prefs.getBoolean("btcpay_enabled", false) &&
                    !prefs.getString("btcpay_pos_app_id", null).isNullOrBlank()
                return Config(
                    autoWithdrawEnabled =
                        AutoWithdrawSettingsManager.getInstance(context).isGloballyEnabled(),
                    usesBtcPayCatalog = usesBtcPayCatalog,
                    priceUpdatedAt = BitcoinPriceWorker.getInstance(context).getPriceUpdatedAt(),
                )
            }
        }
    }

    fun rows(config: Config): List<Row> = buildList {
        add(
            Row(
                Feature.CATALOG,
                if (config.usesBtcPayCatalog) Status.UNAVAILABLE else Status.AVAILABLE
            )
        )
        add(Row(Feature.HISTORY, Status.AVAILABLE))
        add(
            if (config.priceUpdatedAt != null) {
                Row(Feature.EXCHANGE_RATE, Status.DEGRADED, config.priceUpdatedAt)
            } else {
                Row(Feature.EXCHANGE_RATE, Status.UNAVAILABLE)
            }
        )
        add(Row(Feature.ACCEPT_PAYMENTS, Status.UNAVAILABLE))
        add(Row(Feature.WITHDRAWALS, Status.UNAVAILABLE))
        if (config.autoWithdrawEnabled) add(Row(Feature.AUTO_WITHDRAW, Status.UNAVAILABLE))
    }.sortedBy { it.status.ordinal }
}
