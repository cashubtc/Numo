package com.electricdreams.numo.feature.settings

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ComponentWithdrawAmountBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WithdrawAmountFieldTest {

    private lateinit var context: Context
    private lateinit var binding: ComponentWithdrawAmountBinding
    private lateinit var field: WithdrawAmountField
    private var changes = 0

    @Before
    fun setUp() {
        BitcoinPriceWorker.isTesting = true
        context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Numo)
        binding = ComponentWithdrawAmountBinding.inflate(LayoutInflater.from(context))
        field = WithdrawAmountField(context, binding) { changes++ }
    }

    @Test
    fun `prefills the suggested amount until the merchant types their own`() {
        field.setAvailable(available = 1_000, maxSendable = 980)
        assertEquals(980L, field.value)
        assertTrue(field.isValid)

        field.setAvailable(available = 2_000, maxSendable = 1_960)
        assertEquals("a balance refresh replaces an untouched suggestion", 1_960L, field.value)

        binding.amountInput.setText("200")
        field.setAvailable(available = 900, maxSendable = 882)
        assertEquals("a typed amount survives a balance refresh", 200L, field.value)
    }

    @Test
    fun `max fills the largest sendable amount`() {
        field.setAvailable(available = 1_000, maxSendable = 980)
        binding.amountInput.setText("5")

        binding.maxButton.performClick()

        assertEquals(980L, field.value)
    }

    @Test
    fun `amounts above the balance show an inline error and are invalid`() {
        field.setAvailable(available = 1_000, maxSendable = 980)

        binding.amountInput.setText("1500")

        assertFalse(field.isValid)
        assertEquals(
            context.getString(R.string.withdraw_amount_too_large, Amount(1_000, Amount.Currency.BTC).toString()),
            binding.amountLayout.error
        )

        binding.amountInput.setText("1000")
        assertTrue(field.isValid)
        assertNull(binding.amountLayout.error)
    }

    @Test
    fun `empty or zero amounts are invalid and the caption still shows what is available`() {
        field.setAvailable(available = 1_000, maxSendable = 980)

        binding.amountInput.setText("")
        assertEquals(0L, field.value)
        assertFalse(field.isValid)
        assertEquals(
            context.getString(R.string.withdraw_from_available, Amount(1_000, Amount.Currency.BTC).toString()),
            binding.amountLayout.helperText
        )

        binding.amountInput.setText("0")
        assertFalse(field.isValid)
        assertTrue(changes > 0)
    }

    @Test
    fun `max is disabled when nothing can be sent`() {
        field.setAvailable(available = 0, maxSendable = 0)
        assertFalse(binding.maxButton.isEnabled)
        assertEquals("", binding.amountInput.text.toString())
    }
}
