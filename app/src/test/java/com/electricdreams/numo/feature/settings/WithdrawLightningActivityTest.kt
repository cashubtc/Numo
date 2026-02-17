package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.MintManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WithdrawLightningActivityTest {

    private val mintUrl = "https://test.mint.com"
    private val balance = 1000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Ensure MintManager is initialized and has the mint
        val mintManager = MintManager.getInstance(context)
        if (!mintManager.getAllowedMints().contains(mintUrl)) {
            mintManager.addMint(mintUrl)
        }
    }

    @Test
    fun `initial load defaults to lightning tab`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), WithdrawLightningActivity::class.java).apply {
            putExtra("mint_url", mintUrl)
            putExtra("balance", balance)
        }

        ActivityScenario.launch<WithdrawLightningActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val tabLightning = activity.findViewById<TextView>(R.id.tab_lightning)
                val tabCashu = activity.findViewById<TextView>(R.id.tab_cashu)
                val lightningContainer = activity.findViewById<View>(R.id.lightning_options_container)
                val cashuContainer = activity.findViewById<View>(R.id.cashu_token_options_container)

                // Verify initial visibility
                assertEquals("Lightning container should be visible", View.VISIBLE, lightningContainer.visibility)
                assertEquals("Cashu container should be gone", View.GONE, cashuContainer.visibility)
                
                // Verify tab styling (checking text color is a proxy for selection)
                val selectedColor = activity.getColor(R.color.color_text_primary)
                assertEquals(selectedColor, tabLightning.currentTextColor)
            }
        }
    }

    @Test
    fun `switching to cashu tab updates ui`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), WithdrawLightningActivity::class.java).apply {
            putExtra("mint_url", mintUrl)
            putExtra("balance", balance)
        }

        ActivityScenario.launch<WithdrawLightningActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val tabCashu = activity.findViewById<TextView>(R.id.tab_cashu)
                
                // Switch to Cashu tab
                tabCashu.performClick()
                
                val lightningContainer = activity.findViewById<View>(R.id.lightning_options_container)
                val cashuContainer = activity.findViewById<View>(R.id.cashu_token_options_container)

                // Verify visibility toggled
                assertEquals("Lightning container should be gone", View.GONE, lightningContainer.visibility)
                assertEquals("Cashu container should be visible", View.VISIBLE, cashuContainer.visibility)
            }
        }
    }

    @Test
    fun `create token button is disabled initially`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), WithdrawLightningActivity::class.java).apply {
            putExtra("mint_url", mintUrl)
            putExtra("balance", balance)
        }

        ActivityScenario.launch<WithdrawLightningActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Switch to Cashu tab first to ensure views are "visible" logically
                activity.findViewById<TextView>(R.id.tab_cashu).performClick()
                
                val createButton = activity.findViewById<Button>(R.id.create_token_button)
                assertTrue("Button should be disabled initially", !createButton.isEnabled)
            }
        }
    }

    @Test
    fun `entering amount enables create token button`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), WithdrawLightningActivity::class.java).apply {
            putExtra("mint_url", mintUrl)
            putExtra("balance", balance)
        }

        ActivityScenario.launch<WithdrawLightningActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<TextView>(R.id.tab_cashu).performClick()
                
                val amountInput = activity.findViewById<EditText>(R.id.cashu_amount_input)
                val createButton = activity.findViewById<Button>(R.id.create_token_button)
                
                amountInput.setText("100")
                
                assertTrue("Button should be enabled with valid amount", createButton.isEnabled)
                
                amountInput.setText("")
                assertTrue("Button should be disabled with empty amount", !createButton.isEnabled)
                
                amountInput.setText("0")
                assertTrue("Button should be disabled with 0 amount", !createButton.isEnabled)
            }
        }
    }
}
