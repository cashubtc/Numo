package com.electricdreams.numo.feature.items.handlers

import com.electricdreams.numo.core.model.PriceType
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemBuilderUnitTest {

    @Test
    fun `builder stores explicit custom atomic price and issuer`() {
        val item = ItemBuilder().build(
            validationResult = ItemFormValidator.ValidationResult(
                isValid = true,
                name = "Credit",
                fiatPrice = 5.0,
                priceUnit = "points",
                priceAtomic = 5L,
                grossPriceAtomic = 5L,
                priceIssuerScope = "https://mint.example",
            ),
            isEditMode = false,
            editItemId = null,
            currentItem = null,
            variationName = "",
            category = null,
            description = "",
            sku = "",
            gtin = "",
            // Explicit unit must win even if a stale caller supplied SATS.
            priceType = PriceType.SATS,
            vatEnabled = true,
            vatRate = 10,
            trackInventory = false,
            alertEnabled = false,
            hasNewImage = false,
        )

        assertEquals(PriceType.FIAT, item.priceType)
        assertEquals("points", item.priceUnit)
        assertEquals(5L, item.priceAtomic)
        assertEquals(5L, item.grossPriceAtomic)
        assertEquals("https://mint.example", item.priceIssuerScope)
    }
}
