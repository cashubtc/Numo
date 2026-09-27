package com.electricdreams.numo.feature.insights

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import java.util.Date

/** Native Skia snapshots of the Sales rows; they complement device review, not replace it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsRowRenderTest {
    /** Themed like the Insights screen, so the rows' theme attributes resolve */
    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Numo)

    /** Preview PNGs are design-iteration tooling; CI runs the assertions alone. */
    private val previews = System.getProperty("numo.previews").toBoolean()

    @Test
    fun `thumbnail stacks never push a row's text off the grid`() {
        for (withPhotos in listOf(true, false)) {
            val list = render(sampleRows(if (withPhotos) photo() else null))
            val titleStarts = (0 until list.childCount).map {
                list.getChildAt(it).findViewById<TextView>(R.id.tx_title).left
            }
            assertEquals(
                "Every row's title must start at the same x, one item or many: $titleStarts",
                1,
                titleStarts.distinct().size,
            )
            save(draw(list), if (withPhotos) "sales-rows-photos" else "sales-rows-placeholders")
        }
    }

    /** The rows from the Sales screen, one item to three */
    private fun sampleRows(photo: String?): List<TxRow> {
        fun item(name: String, quantity: Int) = BasketItemSummary(name, photo, quantity)
        fun row(id: String, day: Int, hour: Int, minute: Int, cents: Long, vararg items: BasketItemSummary) =
            TxRow(
                id = id,
                date = at(day, hour, minute),
                totalSats = cents * 10,
                totalFiatMinor = cents,
                basket = BasketSummary(items.toList(), items.sumOf { it.quantity }, items.size),
            )
        return listOf(
            row("1", 27, 10, 26, 850, item("Cinnamon Bun", 2)),
            row("2", 27, 8, 48, 2400, item("Latte", 1), item("Croissant", 1), item("Cold Brew", 1)),
            row("3", 27, 8, 30, 1825, item("Cappuccino", 1), item("Croissant", 1), item("Mocha", 1)),
            row("4", 27, 8, 2, 2100, item("Cappuccino", 3), item("Pain au Chocolat", 2)),
            row("5", 27, 7, 47, 375, item("Pain au Chocolat", 1)),
            row("6", 26, 17, 6, 1375, item("Mocha", 2), item("Croissant", 1)),
            row("7", 26, 14, 56, 925, item("Latte", 1), item("Cold Brew", 1)),
        )
    }

    private fun at(day: Int, hour: Int, minute: Int): Date =
        Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, day, hour, minute, 0) }.time

    /**
     * A stand-in item photo, drawn here rather than shipped as a file, and saved as a JPEG the
     * way item pictures are stored on the device: a product on a white ground.
     */
    private fun photo(): String {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawCircle(200f, 210f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 112, 64) })
        }
        val file = File.createTempFile("item", ".jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }

    /** Bind the rows through the real adapter and layout, as the Sales list does */
    private fun render(rows: List<TxRow>): LinearLayout {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.color_bg_white))
        }
        val adapter = InsightsTransactionAdapter(DisplayUnit.FIAT, Amount.Currency.USD)
        adapter.submit(rows, DisplayUnit.FIAT, Amount.Currency.USD)
        for (position in rows.indices) {
            val holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            list.addView(holder.itemView)
        }
        val width = (411 * context.resources.displayMetrics.density).toInt()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        list.layout(0, 0, width, list.measuredHeight)
        return list
    }

    private fun draw(view: View): Bitmap = Bitmap.createBitmap(
        view.width, view.height, Bitmap.Config.ARGB_8888,
    ).also { view.draw(Canvas(it)) }

    private fun save(bitmap: Bitmap, name: String) {
        if (!previews) return
        val file = File("build/insights-previews/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
