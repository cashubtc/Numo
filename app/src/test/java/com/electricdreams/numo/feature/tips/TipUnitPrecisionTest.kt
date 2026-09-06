package com.electricdreams.numo.feature.tips

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.MintManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TipUnitPrecisionTest {
    @Test
    fun `tips use the selected issuer limits instead of another mint with the same unit`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mints = MintManager.getInstance(context)
        val selected = "https://selected.example"
        val other = "https://other.example"
        mints.setMintChangeListener(null)
        mints.getAllowedMints().forEach { mints.removeMint(it) }
        for ((mint, minimum) in listOf(selected to 1, other to 1000)) {
            mints.addMint(mint)
            mints.setMintUnits(mint, listOf("bux"))
            mints.setMintInfo(mint, """
                {"nuts":{"4":{"methods":[{"method":"bolt11","unit":"bux","min_amount":$minimum}]}}}
            """.trimIndent())
        }
        mints.setPreferredUnit("bux")
        assertTrue(mints.setPreferredLightningMint(other))
        val intent = Intent()
            .putExtra(TipSelectionActivity.EXTRA_PAYMENT_UNIT, "bux")
            .putExtra(TipSelectionActivity.EXTRA_PAYMENT_AMOUNT, 125L)
            .putExtra(TipSelectionActivity.EXTRA_FORMATTED_AMOUNT, "125 BUX")
            .putExtra(PaymentRequestActivity.EXTRA_PAYMENT_ISSUER_SCOPE, selected)
        val controller = Robolectric.buildActivity(TipSelectionActivity::class.java, intent).setup()
        try {
            controller.get().findViewById<View>(R.id.no_tip_button).performClick()
            val payment = shadowOf(controller.get()).nextStartedActivity
            assertNotNull(payment)
            assertEquals(selected, payment.getStringExtra(PaymentRequestActivity.EXTRA_PAYMENT_ISSUER_SCOPE))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `custom units have no decimal tip key and retain whole tip amounts`() {
        verifyKeypad("bux", decimalEnabled = false, expectedInput = "12")
    }

    @Test
    fun `direct fiat units retain decimal tip entry`() {
        verifyKeypad("usd", decimalEnabled = true, expectedInput = "1.2")
    }

    private fun verifyKeypad(unit: String, decimalEnabled: Boolean, expectedInput: String) {
        val intent = Intent()
            .putExtra(TipSelectionActivity.EXTRA_PAYMENT_UNIT, unit)
            .putExtra(TipSelectionActivity.EXTRA_PAYMENT_AMOUNT, 125L)
            .putExtra(
                TipSelectionActivity.EXTRA_FORMATTED_AMOUNT,
                if (unit == "bux") "125 BUX" else "$1.25",
            )
        val controller = Robolectric.buildActivity(TipSelectionActivity::class.java, intent).setup()
        val activity = controller.get()
        try {
            activity.findViewById<View>(R.id.custom_tip_button).performClick()
            val keypad = activity.findViewById<GridLayout>(R.id.custom_keypad)
            fun key(label: String): View = keypad.children.single {
                it.findViewById<TextView>(R.id.key_text).text.toString() == label
            }
            val decimal = key(".")
            assertEquals(decimalEnabled, decimal.isClickable)
            assertEquals(decimalEnabled, decimal.isFocusable)
            key("1").performClick()
            if (decimalEnabled) decimal.performClick()
            key("2").performClick()
            assertEquals(
                expectedInput,
                activity.findViewById<TextView>(R.id.custom_amount_display).text.toString(),
            )
            val tipField = TipSelectionActivity::class.java.getDeclaredField("selectedTipSats")
                .apply { isAccessible = true }
            assertEquals(if (decimalEnabled) 120L else 12L, tipField.getLong(activity))
            if (!decimalEnabled) {
                assertFalse(activity.findViewById<View>(R.id.custom_currency_toggle).isShown)
                assertTrue(activity.findViewById<TextView>(R.id.amount_display).text.contains("125 BUX"))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
