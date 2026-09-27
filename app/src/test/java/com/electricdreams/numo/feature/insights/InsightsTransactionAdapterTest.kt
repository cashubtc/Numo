package com.electricdreams.numo.feature.insights

import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsightsTransactionAdapterTest {

    private val context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Numo)

    @Test
    fun `tapping a sale opens that sale, quick charge or basket`() {
        val tapped = mutableListOf<String>()
        val adapter = InsightsTransactionAdapter(DisplayUnit.FIAT, Amount.Currency.USD) {
            tapped += it.id
        }
        val basket = BasketSummary(listOf(BasketItemSummary("Latte", null, 1)), 1, 1)
        adapter.submit(
            listOf(row("quick", basket = null), row("latte", basket)),
            DisplayUnit.FIAT,
            Amount.Currency.USD,
        )
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1080, 2400)
        }

        list.getChildAt(1).performClick()
        list.getChildAt(0).performClick()

        assertEquals(listOf("latte", "quick"), tapped)
    }

    private fun row(id: String, basket: BasketSummary?) =
        TxRow(id = id, date = Date(), totalSats = 1_000, totalFiatMinor = 100, basket = basket)
}
