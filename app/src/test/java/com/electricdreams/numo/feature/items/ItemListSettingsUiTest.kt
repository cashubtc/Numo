package com.electricdreams.numo.feature.items

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.util.ItemManager
import com.electricdreams.numo.databinding.ActivityItemListBinding

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemListSettingsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearCatalog() {
        ItemManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        ItemManager.getInstance(context).clearItems()
    }

    @Test
    fun `empty catalog keeps its original artwork and can open the item editor`() {
        ActivityScenario.launch(ItemListActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.top_bar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.empty_view).visibility)
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.empty_state_back_button).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.empty_state_close_button).visibility)
                assertTrue(activity.findViewById<View>(R.id.ribbon_container).isShown)
                assertEquals(activity.getString(R.string.empty_state_items_title),
                    activity.findViewById<TextView>(R.id.empty_state_title).text.toString())
                activity.findViewById<View>(R.id.empty_state_add_button).performClick()
                assertEquals(ItemEntryActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
    }

    @Test
    fun `original empty catalog import and back actions remain available`() {
        ActivityScenario.launch(ItemListActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.empty_state_import_button).performClick()
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                    shadowOf(activity).nextStartedActivity.action)
                activity.findViewById<View>(R.id.empty_state_back_button).performClick()
                assertTrue(activity.isFinishing)
            }
        }
    }

    @Test
    fun `catalog transitions preserve full width empty state and constrain populated content`() {
        val manager = ItemManager.getInstance(context)
        ActivityScenario.launch(ItemListActivity::class.java).use { scenario ->
            fun checkSurface(hasItems: Boolean) {
                scenario.onActivity { activity ->
                    val binding = ActivityItemListBinding.bind(
                        activity.findViewById<View>(R.id.catalog_content).parent as View)
                    val page = binding.root
                    val maxWidth = activity.resources
                        .getDimensionPixelSize(R.dimen.settings_content_max_width)
                    page.measure(
                        View.MeasureSpec.makeMeasureSpec(maxWidth * 2, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    )
                    page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                    assertEquals(hasItems, binding.catalogContent.isShown)
                    assertEquals(!hasItems, binding.emptyView.root.isShown)
                    val color = activity.getColor(if (hasItems) R.color.settings_background
                        else R.color.empty_state_background)
                    assertEquals(color, (page.background as ColorDrawable).color)
                    assertEquals(color, activity.window.navigationBarColor)
                    if (hasItems) {
                        assertEquals(maxWidth, binding.catalogContent.getChildAt(0).width)
                    } else {
                        assertEquals(page.width - page.paddingLeft - page.paddingRight,
                            binding.emptyView.root.width)
                    }
                }
            }

            checkSurface(false)
            scenario.moveToState(Lifecycle.State.STARTED)
            manager.addItem(Item(name = "Coffee", price = 3.0))
            scenario.moveToState(Lifecycle.State.RESUMED)
            checkSurface(true)
            scenario.moveToState(Lifecycle.State.STARTED)
            manager.clearItems()
            scenario.moveToState(Lifecycle.State.RESUMED)
            checkSurface(false)
        }
    }

    @Test
    fun `populated catalog shows items and import export actions`() {
        ItemManager.getInstance(context).addItem(Item(name = "Coffee", price = 3.0))
        ActivityScenario.launch(ItemListActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.empty_view).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.items_content).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.top_bar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.fab_add_item).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.import_csv_button).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.export_csv_button).visibility)
            }
        }
    }

    @Test
    fun `labeled editor fields save a new item and update it in edit mode`() {
        ActivityScenario.launch(ItemEntryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.item_name_input).setText("Coffee")
                activity.findViewById<EditText>(R.id.item_price_input).setText("3.50")
                activity.findViewById<View>(R.id.item_save_button).performClick()
            }
        }
        val itemManager = ItemManager.getInstance(context)
        val item = itemManager.getAllItems().single()
        assertEquals("Coffee", item.name)
        assertEquals(3.5, item.price, 0.0)

        val intent = Intent(context, ItemEntryActivity::class.java)
            .putExtra(ItemEntryActivity.EXTRA_ITEM_ID, item.id)
        ActivityScenario.launch<ItemEntryActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Coffee",
                    activity.findViewById<EditText>(R.id.item_name_input).text.toString())
                activity.findViewById<EditText>(R.id.item_name_input).setText("Espresso")
                activity.findViewById<View>(R.id.item_save_button).performClick()
            }
        }
        assertEquals("Espresso", itemManager.getAllItems().single().name)
        assertEquals(item.id, itemManager.getAllItems().single().id)
    }
}
