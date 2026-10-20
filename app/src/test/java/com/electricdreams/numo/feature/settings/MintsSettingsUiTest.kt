package com.electricdreams.numo.feature.settings

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.ui.components.MintListItem
import com.electricdreams.numo.ui.components.NumoTopBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MintsSettingsUiTest {
    private val mintUrl = "https://test.mint.com/Bitcoin"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MintManager.getInstance(context).apply {
            addMint(mintUrl)
            setPreferredLightningMint(mintUrl)
        }
    }

    @Test
    fun `selected mint opens its details with lightning flag`() {
        ActivityScenario.launch(MintsSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val row = activity.findViewById<MintListItem>(R.id.lightning_mint_row)
                row.bind(mintUrl, 0L)
                row.findViewById<View>(R.id.mint_item_container).performClick()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(MintDetailsActivity::class.java.name, intent.component?.className)
                assertEquals(mintUrl, intent.getStringExtra(MintDetailsActivity.EXTRA_MINT_URL))
                assertTrue(intent.getBooleanExtra(MintDetailsActivity.EXTRA_IS_LIGHTNING_MINT, false))
            }
        }
    }

    @Test
    fun `overflow offers a labeled reset without changing mints`() {
        ActivityScenario.launch(MintsSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = MintManager.getInstance(activity)
                val before = manager.getAllowedMints().toList()
                activity.findViewById<NumoTopBar>(R.id.top_bar).actionView.performClick()
                val popup = ShadowPopupMenu.getLatestPopupMenu()
                assertNotNull(popup)
                assertEquals(activity.getString(R.string.mints_reset_title),
                    popup.menu.getItem(0).title.toString())
                assertEquals(before, manager.getAllowedMints())
            }
        }
    }

    @Test
    fun `long balance wraps below address and preserves endpoint path`() {
        ActivityScenario.launch(MintsSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val row = activity.findViewById<MintListItem>(R.id.lightning_mint_row)
                row.bind(mintUrl, Long.MAX_VALUE)
                val balance = row.findViewById<TextView>(R.id.balance_text)
                balance.text = "9 223 372 036 854 775 807 sat"
                row.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                val url = row.findViewById<TextView>(R.id.mint_url)
                assertEquals("test.mint.com/Bitcoin", url.text.toString())
                assertTrue("balance ${balance.top}, URL bottom ${url.bottom}", balance.top >= url.bottom)
                assertTrue(balance.right <= (balance.parent as View).width)
                assertEquals(null, balance.ellipsize)
                val chevron = row.findViewById<View>(R.id.chevron)
                assertEquals((balance.top + balance.bottom) / 2f,
                    (chevron.top + chevron.bottom) / 2f, 1f)
                balance.text = "0 sat"
                row.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                assertEquals((balance.top + balance.bottom) / 2f,
                    (chevron.top + chevron.bottom) / 2f, 1f)
                assertTrue(chevron.left >= balance.right)
            }
        }
    }
}
