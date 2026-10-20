package com.electricdreams.numo.feature.insights

import android.content.Context
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.ItemManager
import com.electricdreams.numo.core.util.SavedBasketManager

/**
 * What a sale was, for the rows in Sales and Activity: the items sold, or a quick charge.
 * Both lists name a sale the same way.
 */
object SaleSummaries {

    /** Item pictures by item id, for [basket]'s thumbnails */
    fun imagesByItemId(context: Context): Map<String, String> =
        ItemManager.getInstance(context).getAllItems()
            .mapNotNull { item ->
                val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path = item.imagePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to path
            }
            .toMap()

    /** The items [entry] sold, most first; null for a quick charge */
    fun basket(
        entry: PaymentHistoryEntry,
        basketManager: SavedBasketManager,
        imagesByItemId: Map<String, String>,
    ): BasketSummary? {
        val saved = entry.basketId?.let { basketManager.getBasket(it) }
        if (saved != null && saved.items.isNotEmpty()) {
            val items = saved.items
                .filter { it.item.name?.isNotBlank() == true }
                .groupBy { it.item.name!! }
                .map { (name, instances) ->
                    BasketItemSummary(
                        itemName = name,
                        itemImagePath = instances.firstNotNullOfOrNull { it.item.imagePath?.takeIf { p -> p.isNotBlank() } },
                        quantity = instances.sumOf { it.quantity },
                    )
                }
                .sortedByDescending { it.quantity }
            return BasketSummary(
                items = items,
                totalQuantity = items.sumOf { it.quantity },
                distinctTypes = items.size,
            )
        }

        val checkout = entry.getCheckoutBasket()
        if (checkout != null && checkout.items.isNotEmpty()) {
            val items = checkout.items
                .filter { it.name.isNotBlank() }
                .groupBy { it.name }
                .map { (name, instances) ->
                    BasketItemSummary(
                        itemName = name,
                        itemImagePath = instances.firstNotNullOfOrNull { imagesByItemId[it.itemId] },
                        quantity = instances.sumOf { it.quantity },
                    )
                }
                .sortedByDescending { it.quantity }
            return BasketSummary(
                items = items,
                totalQuantity = items.sumOf { it.quantity },
                distinctTypes = items.size,
            )
        }

        return null
    }

    /** "2 × Latte", "1 × Latte, 1 × Croissant", "3 items · Latte +2 more", or "Quick charge" */
    fun title(context: Context, basket: BasketSummary?): String {
        val items = basket?.items.orEmpty()
        return when {
            basket == null || items.isEmpty() -> context.getString(R.string.insights_quick_charge)
            items.size == 1 -> {
                val first = items[0]
                context.getString(R.string.insights_item_xn, first.quantity, first.itemName)
            }
            items.size == 2 -> {
                items.joinToString(", ") { context.getString(R.string.insights_item_xn, it.quantity, it.itemName) }
            }
            else -> {
                val countLabel = if (basket.totalQuantity == 1) {
                    context.getString(R.string.insights_items_count_one)
                } else {
                    context.getString(R.string.insights_items_count_other, basket.totalQuantity)
                }
                val lead = items[0]
                context.getString(R.string.insights_items_lead_with_more, countLabel, lead.itemName, items.size - 1)
            }
        }
    }
}
