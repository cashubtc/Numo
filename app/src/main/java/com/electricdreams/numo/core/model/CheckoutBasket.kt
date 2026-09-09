package com.electricdreams.numo.core.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Complete snapshot of a checkout basket at the time of payment.
 * Contains all items with quantities, prices, and VAT information.
 * 
 * This is serialized to JSON and stored with the payment history entry
 * to enable reconstruction of a detailed receipt view.
 */
data class CheckoutBasket(
    /** Unique identifier for this basket */
    @SerializedName("id")
    val id: String = java.util.UUID.randomUUID().toString(),

    /** Timestamp when the checkout was created */
    @SerializedName("checkoutTimestamp")
    val checkoutTimestamp: Long = System.currentTimeMillis(),

    /** All items in the basket */
    @SerializedName("items")
    val items: List<CheckoutBasketItem>,

    /** Currency used for fiat items (primary currency at checkout) */
    @SerializedName("currency")
    val currency: String,

    /** Bitcoin price at time of checkout (if available) */
    @SerializedName("bitcoinPrice")
    val bitcoinPrice: Double? = null,

    /** Total amount in satoshis (final payment amount) */
    @SerializedName("totalSatoshis")
    val totalSatoshis: Long,

    /** Canonical charge unit. Null only in legacy receipt snapshots. */
    @SerializedName("chargeUnit")
    val chargeUnit: String? = null,

    /** Final charge amount in the atomic denomination of [chargeUnit]. */
    @SerializedName("chargeAmountAtomic")
    val chargeAmountAtomic: Long? = null,

    /** Issuer scope when a custom charge unit is accepted from one mint only. */
    @SerializedName("chargeIssuerScope")
    val chargeIssuerScope: String? = null,
) {

    /**
     * Get all fiat-priced items.
     */
    fun getFiatItems(): List<CheckoutBasketItem> = items.filter { it.isFiatPrice() }

    /**
     * Get all sats-priced items.
     */
    fun getSatsItems(): List<CheckoutBasketItem> = items.filter { it.isSatsPrice() }

    /**
     * Check if basket contains mixed price types.
     */
    fun hasMixedPriceTypes(): Boolean {
        val hasFiat = items.any { it.isFiatPrice() }
        val hasSats = items.any { it.isSatsPrice() }
        return hasFiat && hasSats
    }

    /**
     * Check if any items have VAT enabled.
     */
    fun hasVat(): Boolean = items.any { it.vatEnabled && it.vatRate > 0 }

    /**
     * Calculate total item count (sum of all quantities).
     */
    fun getTotalItemCount(): Int = items.fold(0) { total, item ->
        Math.addExact(total, item.quantity)
    }

    /**
     * Calculate total net amount for fiat items (in minor units/cents).
     */
    fun getFiatNetTotalCents(): Long = getFiatItems().fold(0L) { total, item ->
        Math.addExact(total, item.getNetTotalCents())
    }

    /**
     * Calculate total VAT amount for fiat items (in minor units/cents).
     */
    fun getFiatVatTotalCents(): Long = getFiatItems().fold(0L) { total, item ->
        Math.addExact(total, item.getTotalVatCents())
    }

    /**
     * Calculate total gross amount for fiat items (in minor units/cents).
     */
    fun getFiatGrossTotalCents(): Long = getFiatItems().fold(0L) { total, item ->
        Math.addExact(total, item.getGrossTotalCents())
    }

    /**
     * Calculate total sats for directly sats-priced items.
     */
    fun getSatsDirectTotal(): Long = getSatsItems().fold(0L) { total, item ->
        Math.addExact(total, item.getNetTotalSats())
    }

    /**
     * Get grouped VAT breakdown by rate.
     * Returns a map of VAT rate (e.g., 20) to total VAT amount in cents.
     */
    fun getVatBreakdown(): Map<Int, Long> {
        return getFiatItems()
            .filter { it.vatEnabled && it.vatRate > 0 }
            .groupBy { it.vatRate }
            .mapValues { (_, items) ->
                items.fold(0L) { total, item ->
                    Math.addExact(total, item.getTotalVatCents())
                }
            }
    }

    /**
     * Get the checkout date as a Date object.
     */
    fun getCheckoutDate(): Date = Date(checkoutTimestamp)

    /** Resolve the final unit-bearing charge, including sat-only legacy snapshots. */
    fun getChargeAmount(): AtomicAmount {
        val unit = if (chargeUnit == null) {
            UnitId.SAT
        } else {
            requireNotNull(UnitId.ofOrNull(chargeUnit)?.takeUnless { it.isReserved }) {
                "Invalid checkout charge unit: $chargeUnit"
            }
        }
        val amount = chargeAmountAtomic ?: totalSatoshis
        val issuer = chargeIssuerScope?.takeIf {
            it.isNotBlank() && UnitDescriptor.defaultFor(unit).kind == UnitKind.CUSTOM
        }
        val asset = issuer?.let { AssetId.mintScoped(unit, it) } ?: AssetId.global(unit)
        return AtomicAmount(amount, asset)
    }

    /**
     * Serialize this basket to JSON string for storage.
     */
    fun toJson(): String = Gson().toJson(this)

    companion object {
        /**
         * Deserialize a basket from JSON string.
         * Returns null if parsing fails or json is null/empty.
         */
        fun fromJson(json: String?): CheckoutBasket? {
            if (json.isNullOrEmpty()) return null
            return try {
                Gson().fromJson(json, CheckoutBasket::class.java)
                    ?.takeIf(::isValidSnapshot)
            } catch (e: RuntimeException) {
                null
            }
        }

        private fun isValidSnapshot(basket: CheckoutBasket): Boolean = runCatching {
            require(basket.totalSatoshis >= 0L) { "Checkout total cannot be negative" }
            require(
                UnitId.ofOrNull(basket.currency)?.takeUnless { it.isReserved } != null,
            ) {
                "Checkout currency must be a valid, non-reserved unit"
            }
            require(basket.bitcoinPrice == null || (
                basket.bitcoinPrice.isFinite() && basket.bitcoinPrice >= 0.0
                )) {
                "Bitcoin price must be non-negative and finite"
            }

            val hasExplicitChargeUnit = basket.chargeUnit != null
            require(hasExplicitChargeUnit == (basket.chargeAmountAtomic != null)) {
                "Checkout charge unit and amount must be stored together"
            }
            require(hasExplicitChargeUnit || basket.chargeIssuerScope == null) {
                "Checkout issuer scope requires an explicit charge unit"
            }
            basket.getChargeAmount()

            basket.items.forEach { item ->
                require(item.quantity > 0) { "Checkout item quantity must be positive" }
                require(item.priceType == "FIAT" || item.priceType == "SATS") {
                    "Unknown checkout price type: ${item.priceType}"
                }
                require(item.netPriceCents >= 0L && item.priceSats >= 0L) {
                    "Legacy checkout prices cannot be negative"
                }
                require(item.vatRate >= 0) { "VAT rate cannot be negative" }
                val net = item.getNetAtomicAmount()
                val gross = item.getGrossAtomicAmount()
                require(gross.asset == net.asset && gross.value >= net.value) {
                    "Gross checkout price cannot be below net price"
                }
                item.getNetLineAtomicAmount()
                item.getGrossLineAtomicAmount()
                if (item.isFiatPrice()) item.getGrossTotalCents() else item.getNetTotalSats()
            }
            true
        }.getOrDefault(false)

        /**
         * Create a CheckoutBasket from the current BasketManager state.
         */
        fun fromBasketManager(
            basketManager: com.electricdreams.numo.core.util.BasketManager,
            currency: String,
            bitcoinPrice: Double?,
            totalSatoshis: Long,
            chargeAmount: AtomicAmount? = null,
        ): CheckoutBasket {
            val items = basketManager.getBasketItems().map { basketItem ->
                CheckoutBasketItem.fromBasketItem(
                    basketItem = basketItem,
                    currencyCode = currency,
                )
            }

            return CheckoutBasket(
                items = items,
                currency = currency,
                bitcoinPrice = bitcoinPrice,
                totalSatoshis = totalSatoshis,
                chargeUnit = chargeAmount?.unit?.value,
                chargeAmountAtomic = chargeAmount?.value,
                chargeIssuerScope = chargeAmount?.asset?.issuerScope,
            )
        }
    }
}
