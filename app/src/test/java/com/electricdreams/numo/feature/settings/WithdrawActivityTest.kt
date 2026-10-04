package com.electricdreams.numo.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import com.electricdreams.numo.ui.components.SettingsRowView
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WithdrawActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        BitcoinPriceWorker.isTesting = true
        ReflectionHelpers.setStaticField(LightningAddressManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(AutoWithdrawSettingsManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", null)
        AutoWithdrawSettingsManager.getInstance(context).setGloballyEnabled(false)
    }

    @Test
    fun `with nothing to send the actions are disabled and the empty copy shows`() {
        launch { activity ->
            awaitBalance(activity)
            assertEquals(Amount(0, Amount.Currency.BTC).toString(), text(activity, R.id.balance_text))
            assertEquals(activity.getString(R.string.withdraw_hub_empty), text(activity, R.id.destination_text))
            assertFalse(activity.findViewById<Button>(R.id.send_button).isEnabled)
            assertFalse(activity.findViewById<Button>(R.id.export_button).isEnabled)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recent_empty_text).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.see_all_row).visibility)
        }
    }

    @Test
    fun `recent shows the three newest withdrawals with their status in words`() {
        val manager = AutoWithdrawManager.getInstance(context)
        manager.addManualWithdrawalEntry(MINT, 100, 1, "old@wallet.com", "manual_address", WithdrawHistoryEntry.STATUS_COMPLETED)
        manager.addManualWithdrawalEntry(MINT, 200, 1, "shop@wallet.com", "manual_address", WithdrawHistoryEntry.STATUS_COMPLETED)
        manager.addManualWithdrawalEntry(MINT, 300, 1, "lnbc3u1pxyz", "manual_invoice", WithdrawHistoryEntry.STATUS_PENDING)
        manager.addManualWithdrawalEntry(MINT, 400, 0, "Ecash token", "manual_token", WithdrawHistoryEntry.STATUS_FAILED, token = "cashuBxyz")

        launch { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.recent_list)
            assertEquals(3, list.childCount)

            val newest = list.getChildAt(0)
            assertEquals(activity.getString(R.string.withdraw_recent_token), rowText(newest, R.id.recent_title))
            assertEquals(activity.getString(R.string.auto_withdraw_status_failed), rowText(newest, R.id.recent_status))

            val pending = list.getChildAt(1)
            assertEquals(activity.getString(R.string.withdraw_review_to_invoice), rowText(pending, R.id.recent_title))
            assertEquals(activity.getString(R.string.auto_withdraw_status_pending), rowText(pending, R.id.recent_status))

            val sent = list.getChildAt(2)
            assertEquals(activity.getString(R.string.withdraw_recent_to, "shop@wallet.com"), rowText(sent, R.id.recent_title))
            assertEquals(View.GONE, sent.findViewById<View>(R.id.recent_status).visibility)

            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.see_all_row).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.recent_empty_text).visibility)
        }
    }

    @Test
    fun `auto-withdraw row summarises the rule`() {
        val settings = AutoWithdrawSettingsManager.getInstance(context)
        settings.setGloballyEnabled(true)
        settings.setDefaultPercentage(95)
        settings.setDefaultThreshold(50_000)

        launch { activity ->
            val row = activity.findViewById<SettingsRowView>(R.id.auto_withdraw_row)
            val expected = activity.getString(
                R.string.withdraw_hub_auto_on,
                95,
                Amount(50_000, Amount.Currency.BTC).toString()
            )
            assertTrue(row.searchableText.contains(expected))
        }
    }

    private fun launch(block: (WithdrawActivity) -> Unit) {
        ActivityScenario.launch<WithdrawActivity>(Intent(context, WithdrawActivity::class.java)).use { scenario ->
            idle()
            scenario.onActivity(block)
        }
    }

    /** The balance loads off the main thread; wait for the headline to be filled in. */
    private fun awaitBalance(activity: WithdrawActivity) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (text(activity, R.id.balance_text).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            idle()
        }
    }

    private fun text(activity: WithdrawActivity, id: Int) = activity.findViewById<TextView>(id).text.toString()

    private fun rowText(row: View, id: Int) = row.findViewById<TextView>(id).text.toString()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    companion object {
        private const val MINT = "https://mint.example.com"
    }
}
