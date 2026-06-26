package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BarkWithdrawActivityTest {

    @Test
    fun `initial load binds views correctly`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, BarkWithdrawActivity::class.java)

        ActivityScenario.launch<BarkWithdrawActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val balanceText = activity.findViewById<TextView>(R.id.ark_balance_text)
                val estimateButton = activity.findViewById<Button>(R.id.estimate_button)
                val withdrawButton = activity.findViewById<Button>(R.id.withdraw_button)
                val loadingIndicator = activity.findViewById<ProgressBar>(R.id.loading_indicator)

                assertNotNull("Balance display should be bound", balanceText)
                assertNotNull("Estimate button should be bound", estimateButton)
                assertNotNull("Withdraw button should be bound", withdrawButton)
                assertNotNull("Loading indicator should be bound", loadingIndicator)

                // Verify initial states
                assertEquals("Withdraw button should be hidden initially", View.GONE, withdrawButton.visibility)
                assertEquals("Loading indicator should be hidden initially", View.GONE, loadingIndicator.visibility)
            }
        }
    }
}
