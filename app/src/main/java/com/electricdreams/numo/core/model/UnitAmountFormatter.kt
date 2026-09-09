package com.electricdreams.numo.core.model

import java.math.BigDecimal
import java.net.URI
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Formats atomic values without assuming that every non-Bitcoin unit has two decimals. */
object UnitAmountFormatter {

    @JvmStatic
    @JvmOverloads
    fun formatAtomic(
        value: Long,
        descriptor: UnitDescriptor,
        locale: Locale = Locale.getDefault(),
    ): String {
        require(value >= 0) { "Atomic amount cannot be negative" }
        val majorValue = BigDecimal.valueOf(value).movePointLeft(descriptor.fractionDigits)

        if (descriptor.kind == UnitKind.ISO_4217) {
            val currency = runCatching {
                Currency.getInstance(descriptor.unit.value.uppercase(Locale.ROOT))
            }.getOrNull()
            if (currency != null) {
                return NumberFormat.getCurrencyInstance(locale).apply {
                    this.currency = currency
                    minimumFractionDigits = descriptor.fractionDigits
                    maximumFractionDigits = descriptor.fractionDigits
                }.format(majorValue)
            }
        }

        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = descriptor.fractionDigits
            maximumFractionDigits = descriptor.fractionDigits
            isGroupingUsed = true
        }.format(majorValue)
        return "$number ${descriptor.displayCode}"
    }

    @JvmStatic
    @JvmOverloads
    fun format(
        amount: AtomicAmount,
        descriptor: UnitDescriptor,
        locale: Locale = Locale.getDefault(),
    ): String {
        require(amount.unit == descriptor.unit) {
            "Descriptor unit ${descriptor.unit} does not match amount unit ${amount.unit}"
        }
        return formatAtomic(amount.value, descriptor, locale)
    }

    /**
     * Format an economic asset for UI surfaces that may show non-fungible custom units.
     * The issuer suffix is deliberately omitted for globally fungible assets.
     */
    @JvmStatic
    @JvmOverloads
    fun formatAsset(
        amount: AtomicAmount,
        locale: Locale = Locale.getDefault(),
    ): String {
        val formatted = format(amount, UnitDescriptor.defaultFor(amount.unit, locale), locale)
        val issuer = amount.asset.issuerScope ?: return formatted
        return "$formatted · ${issuerLabel(issuer)}"
    }

    @JvmStatic
    fun issuerLabel(issuerScope: String): String {
        val normalized = issuerScope.trim()
        if (normalized.isEmpty()) return issuerScope
        return runCatching { URI(normalized).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: normalized.removePrefix("https://").removePrefix("http://").substringBefore('/')
    }

    /** Custom balances belong to their issuers and must never be summed across mints. */
    fun formatBalances(
        balances: Map<String, Long>,
        unit: UnitId,
        locale: Locale = Locale.getDefault(),
    ): String {
        val descriptor = UnitDescriptor.defaultFor(unit, locale)
        if (descriptor.kind == UnitKind.CUSTOM && balances.isNotEmpty()) {
            return balances.toSortedMap().entries.joinToString(" + ") { (mint, value) ->
                formatAsset(AtomicAmount(value, AssetId.mintScoped(unit, mint)), locale)
            }
        }
        val total = balances.values.fold(0L) { sum, value -> Math.addExact(sum, value) }
        return formatAtomic(total, descriptor, locale)
    }
}
