package com.electricdreams.numo.feature.history

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.feature.insights.BasketItemSummary
import com.electricdreams.numo.feature.insights.BasketSummary
import com.electricdreams.numo.feature.insights.DisplayUnit
import com.electricdreams.numo.feature.insights.InsightsTransactionAdapter
import com.electricdreams.numo.feature.insights.TxRow
import com.electricdreams.numo.ui.adapter.PaymentsHistoryAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/** Activity and Sales list the same sales, so their rows must line up with each other */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
class ActivityRowRenderTest {

    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Numo)

    private val buns = BasketSummary(listOf(BasketItemSummary("Cinnamon Bun", null, 2)), 2, 1)

    @Test
    fun `titles start at the same x in Activity and Sales, whatever the row shows`() {
        val basketSale = payment(amount = 10_109)
        val quickCharge = payment(amount = 4_460)
        val withdrawal = payment(amount = -50_000)

        val activity = PaymentsHistoryAdapter()
        activity.setEntries(listOf(basketSale, quickCharge, withdrawal), mapOf(basketSale.id to buns))
        val activityRows = render { parent ->
            // Position 0 is the month header
            (1..3).map { position ->
                activity.onCreateViewHolder(parent, activity.getItemViewType(position))
                    .also { activity.onBindViewHolder(it, position) }.itemView
            }
        }

        val sales = InsightsTransactionAdapter(DisplayUnit.FIAT, Amount.Currency.USD)
        sales.submit(
            listOf(
                TxRow(basketSale.id, Date(), 10_109, 850, buns),
                TxRow(quickCharge.id, Date(), 4_460, 375, null),
            ),
            DisplayUnit.FIAT,
            Amount.Currency.USD,
        )
        val salesRows = render { parent ->
            (0..1).map { position ->
                sales.onCreateViewHolder(parent, sales.getItemViewType(position))
                    .also { sales.onBindViewHolder(it, position) }.itemView
            }
        }

        val activityStarts = activityRows.map { startInRow(it, it.findViewById(R.id.title_text)) }
        val salesStarts = salesRows.map { startInRow(it, it.findViewById(R.id.tx_title)) }
        assertEquals(
            "Titles should share one x: Activity $activityStarts, Sales $salesStarts",
            1,
            (activityStarts + salesStarts).distinct().size,
        )
        assertEquals(
            listOf("2 × Cinnamon Bun", context.getString(R.string.insights_quick_charge)),
            activityRows.take(2).map { it.findViewById<TextView>(R.id.title_text).text.toString() },
        )
    }

    private fun payment(amount: Long) = PaymentHistoryEntry(
        token = "",
        amount = amount,
        date = Date(),
        rawUnit = "sat",
        rawEntryUnit = "sat",
        enteredAmount = amount,
        rawStatus = PaymentHistoryEntry.STATUS_COMPLETED,
    )

    /** [view]'s left edge measured from [row]'s, through any columns it sits in */
    private fun startInRow(row: View, view: View): Int {
        var x = 0
        var current: View = view
        while (current !== row) {
            x += current.left
            current = current.parent as View
        }
        return x
    }

    /** Lays [rows] out at phone width, as the lists do */
    private fun render(rows: (LinearLayout) -> List<View>): List<View> {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val views = rows(list)
        views.forEach { list.addView(it) }
        val width = (411 * context.resources.displayMetrics.density).toInt()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        list.layout(0, 0, width, list.measuredHeight)
        return views
    }
}
