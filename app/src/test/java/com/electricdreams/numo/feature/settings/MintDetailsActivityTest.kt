package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.ui.util.DialogHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MintDetailsActivityTest {

    private val mintUrl = "https://test.mint.com"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mintManager = MintManager.getInstance(context)
        mintManager.addMint(mintUrl)
    }

    @Test
    fun `initial load displays mint details`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MintDetailsActivity::class.java).apply {
            putExtra(MintDetailsActivity.EXTRA_MINT_URL, mintUrl)
        }

        ActivityScenario.launch<MintDetailsActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val mintName = activity.findViewById<TextView>(R.id.mint_name)
                val mintUrlText = activity.findViewById<TextView>(R.id.mint_url)
                
                // Initially it might just show the host if no info is cached
                assertEquals("test.mint.com", mintName.text.toString())
                assertEquals("test.mint.com", mintUrlText.text.toString())
                
                val lightningBadge = activity.findViewById<View>(R.id.lightning_badge)
                assertEquals(View.GONE, lightningBadge.visibility)
            }
        }
    }

    @Test
    fun `set as lightning mint updates UI and sets result`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MintDetailsActivity::class.java).apply {
            putExtra(MintDetailsActivity.EXTRA_MINT_URL, mintUrl)
            putExtra(MintDetailsActivity.EXTRA_IS_LIGHTNING_MINT, false)
        }

        ActivityScenario.launch<MintDetailsActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val setLightningButton = activity.findViewById<View>(R.id.set_lightning_button)
                assertEquals(View.VISIBLE, setLightningButton.visibility)
                
                setLightningButton.performClick()
            }
            
            // Advance looper to handle animations
            shadowOf(Looper.getMainLooper()).idle()
            
            scenario.onActivity { activity ->
                val setLightningButton = activity.findViewById<View>(R.id.set_lightning_button)
                val lightningBadge = activity.findViewById<View>(R.id.lightning_badge)
                
                assertEquals(View.VISIBLE, lightningBadge.visibility)
                assertEquals(View.GONE, setLightningButton.visibility)
                
                // Verify result
                val result = shadowOf(activity).resultIntent
                assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
                assertTrue(result.getBooleanExtra(MintDetailsActivity.EXTRA_SET_AS_LIGHTNING, false))
            }
        }
    }

    @Test
    fun `delete button shows confirmation dialog`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MintDetailsActivity::class.java).apply {
            putExtra(MintDetailsActivity.EXTRA_MINT_URL, mintUrl)
        }

        ActivityScenario.launch<MintDetailsActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val deleteButton = activity.findViewById<View>(R.id.delete_button)
                deleteButton.performClick()
            }
            
            // Advance looper to handle animations
            shadowOf(Looper.getMainLooper()).idle()
            
            val dialog = ShadowAlertDialog.getLatestDialog()
            assertNotNull("Confirmation dialog should be shown", dialog)
        }
    }
}
