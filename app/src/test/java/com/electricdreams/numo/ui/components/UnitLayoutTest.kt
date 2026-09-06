package com.electricdreams.numo.ui.components

import android.app.Activity
import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.electricdreams.numo.R
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnitLayoutTest {
    @Test
    fun `long custom amounts leave item names readable on narrow screens`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        try {
            listOf(
                Triple(R.layout.item_product, R.id.item_name, R.id.item_price),
                Triple(R.layout.item_basket_compact, R.id.item_name, R.id.item_total),
                Triple(R.layout.item_receipt_line, R.id.item_name, R.id.item_total),
                Triple(R.layout.item_saved_basket, R.id.basket_name, R.id.basket_total),
                Triple(R.layout.item_payment_history, R.id.title_text, R.id.amount_text),
            ).forEach { (layout, nameId, amountId) ->
                val row = LayoutInflater.from(activity).inflate(layout, null)
                val name = row.findViewById<TextView>(nameId)
                val amount = row.findViewById<TextView>(amountId)
                name.text = "Cappuccino"
                amount.text = "25,000 POINTS · merchant-loyalty-rewards.example"
                val density = activity.resources.displayMetrics.density
                val width = (360 * density).toInt()
                row.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                row.layout(0, 0, width, row.measuredHeight)
                val label = activity.resources.getResourceEntryName(layout)
                assertTrue(
                    "$label must reserve space for the item name",
                    (name.parent as View).width >= 80 * density,
                )
                assertEquals("$label must show the item name", 0, name.layout.getEllipsisCount(0))
                assertTrue("$label must wrap long amounts", amount.lineCount > 1)
                assertTrue("$label must show the complete amount", (0 until amount.lineCount).all {
                    amount.layout.getEllipsisCount(it) == 0
                })
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `short history amounts leave enough space for the full payment title`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        try {
            val row = LayoutInflater.from(activity).inflate(R.layout.item_payment_history, null)
            val title = row.findViewById<TextView>(R.id.title_text)
            title.text = "Payment received"
            row.findViewById<TextView>(R.id.amount_text).text = "+€2.50"
            val width = (360 * activity.resources.displayMetrics.density).toInt()
            row.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            row.layout(0, 0, width, row.measuredHeight)
            val amount = row.findViewById<TextView>(R.id.amount_text)
            assertEquals(
                "title ${title.width}px in ${(title.parent as View).width}px, amount ${amount.width}px, " +
                    "wanted ${title.paint.measureText(title.text.toString())}px within ${row.width}px",
                0, title.layout.getEllipsisCount(0),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `mixed-unit basket totals wrap without overlapping the item count`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        try {
            val screen = LayoutInflater.from(activity).inflate(R.layout.activity_item_selection, null)
            val header = screen.findViewById<View>(R.id.basket_header)
            (header.parent as ViewGroup).removeView(header)
            val count = header.findViewById<TextView>(R.id.basket_item_count)
            val total = header.findViewById<TextView>(R.id.basket_total)
            count.text = "3 items"
            total.text = "25,000 POINTS · merchant-loyalty-rewards.example + €2.50 + 500 sat"
            total.textSize = 24f
            val width = (320 * activity.resources.displayMetrics.density).toInt()
            header.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            header.layout(0, 0, width, header.measuredHeight)
            assertTrue(total.left > count.right)
            assertTrue(total.lineCount > 1)
            assertTrue(total.height >= total.layout.height)
            assertTrue(header.height >= total.height + header.paddingTop + header.paddingBottom)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
