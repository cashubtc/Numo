package com.electricdreams.numo.payment

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in UI captures with sample data; run on a fresh emulator with numo.screenshots=true. */
@RunWith(AndroidJUnit4::class)
class PendingSwapScreenshotTest {
    @Test
    fun capturePendingPaymentScreens() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("numo.screenshots") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)
        check(PaymentsHistoryActivity.getPaymentHistory(context).isEmpty()) {
            "Screenshot capture requires an empty payment history"
        }
        check(CashuWalletManager.getWallet() == null) {
            "Screenshot capture requires a fresh emulator without an initialized wallet"
        }
        val mintManager = MintManager.getInstance(context)
        val previousUnit = mintManager.getPreferredUnit()
        mintManager.setPreferredUnit("sat")
        try {
            val id = PaymentsHistoryActivity.addPendingPayment(
                context, 1_000L, "sat", 1_000L, null, null, "1,000 sat",
            )
            PaymentsHistoryActivity.recordPendingSwap(
                context, id, 1_000L, "sat",
                PaymentHistoryEntry.Companion.SwapToLightningMintFrame(
                    "https://source.example", "sample-source-quote",
                    "https://destination.example", "sample-destination-quote", true,
                ),
            )
            val entry = requireNotNull(PaymentsHistoryActivity.getPaymentEntryById(context, id))
            val intent = PaymentIntentFactory.createResumePaymentIntent(context, entry)
            ActivityScenario.launch<PaymentRequestActivity>(intent).use {
                onView(withText(R.string.payment_swap_check_status)).check(matches(isDisplayed()))
                capture(context, "pending-checkout", settleMillis = 2_700)
                onView(withText(R.string.payment_swap_check_status)).perform(click())
                val statusDeadline = SystemClock.uptimeMillis() + 5_000
                var statusEnabled = false
                while (!statusEnabled && SystemClock.uptimeMillis() < statusDeadline) {
                    onView(withText(R.string.payment_swap_check_status)).check { view, error ->
                        if (error != null) throw error
                        statusEnabled = view.isEnabled
                    }
                    if (!statusEnabled) SystemClock.sleep(50)
                }
                onView(withText(R.string.payment_swap_check_status)).check(matches(isEnabled()))
                assertTrue(PaymentsHistoryActivity.getPaymentEntryById(context, id)?.isPending() == true)
                onView(withText(R.string.payment_request_animation_close)).perform(click())
            }
            ActivityScenario.launch(PaymentsHistoryActivity::class.java).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 5_000
                var rowVisible = false
                while (!rowVisible && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        rowVisible = activity.findViewById<RecyclerView>(
                            R.id.history_recycler_view,
                        ).findViewHolderForAdapterPosition(1) != null
                    }
                    if (!rowVisible) SystemClock.sleep(50)
                }
                assertTrue("Sample payment must appear in history", rowVisible)
                capture(context, "pending-history")
                scenario.onActivity { activity ->
                    val row = activity.findViewById<RecyclerView>(R.id.history_recycler_view)
                        .findViewHolderForAdapterPosition(1)
                    assertTrue(requireNotNull(row).itemView.performClick())
                }
                capture(context, "history-status-feedback", settleMillis = 350)
                assertTrue(PaymentsHistoryActivity.getPaymentEntryById(context, id)?.isPending() == true)
            }
        } finally {
            preferences.edit().putString("history", "[]").commit()
            mintManager.setPreferredUnit(previousUnit)
        }
    }

    private fun capture(context: Context, name: String, settleMillis: Long = 700) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(settleMillis)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(context.getExternalFilesDir(null), "pr-415/$name.png")
            check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
