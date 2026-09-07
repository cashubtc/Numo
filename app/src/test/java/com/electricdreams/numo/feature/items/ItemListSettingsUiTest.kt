package com.electricdreams.numo.feature.items

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.util.ItemManager

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
    fun `empty catalog keeps navigation visible and can open the item editor`() {
        ActivityScenario.launch(ItemListActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.top_bar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.empty_view).visibility)
                activity.findViewById<View>(R.id.empty_state_add_button).performClick()
                assertEquals(ItemEntryActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className)
            }
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
