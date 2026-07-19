package com.electricdreams.numo.feature.withdraw

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WithdrawHubActivityTest {

    private lateinit var context: Context
    private lateinit var settingsManager: AutoWithdrawSettingsManager
    private lateinit var addressManager: LightningAddressManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Go through the singletons so reads and writes hit the same prefs
        // instance regardless of Robolectric's per-test application swaps.
        settingsManager = AutoWithdrawSettingsManager.getInstance(context)
        addressManager = LightningAddressManager.getInstance(context)
        settingsManager.setGloballyEnabled(false)
        addressManager.clearLightningAddress()
    }

    private fun launch(block: (WithdrawHubActivity) -> Unit) {
        val intent = Intent(context, WithdrawHubActivity::class.java)
        ActivityScenario.launch<WithdrawHubActivity>(intent).use { scenario ->
            scenario.onActivity(block)
        }
    }

    @Test
    fun `summary badge shows off when auto-withdraw disabled`() {
        launch { activity ->
            val badge = activity.findViewById<TextView>(R.id.auto_status_badge)
            assertEquals(activity.getString(R.string.withdraw_hub_status_off), badge.text.toString())
        }
    }

    @Test
    fun `summary badge shows needs setup when enabled without address`() {
        settingsManager.setGloballyEnabled(true)

        launch { activity ->
            val badge = activity.findViewById<TextView>(R.id.auto_status_badge)
            assertEquals(activity.getString(R.string.withdraw_hub_status_needs_setup), badge.text.toString())
        }
    }

    @Test
    fun `summary badge shows active with valid address and threshold summary`() {
        settingsManager.setGloballyEnabled(true)
        addressManager.setLightningAddress("user@getalby.com")

        launch { activity ->
            val badge = activity.findViewById<TextView>(R.id.auto_status_badge)
            val summary = activity.findViewById<TextView>(R.id.auto_summary_text)
            assertEquals(activity.getString(R.string.auto_withdraw_status_active), badge.text.toString())
            assertEquals(true, summary.text.toString().contains("user@getalby.com"))
        }
    }
}
