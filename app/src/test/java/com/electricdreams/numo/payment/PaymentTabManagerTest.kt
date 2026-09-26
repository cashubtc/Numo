package com.electricdreams.numo.payment

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.electricdreams.numo.R
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentTabManagerTest {

    private lateinit var context: Context
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var unifiedTab: MaterialButton
    private lateinit var cashuTab: MaterialButton
    private lateinit var lightningTab: MaterialButton
    private lateinit var unifiedQr: View
    private lateinit var cashuQr: View
    private lateinit var lightningQr: View
    private lateinit var manager: PaymentTabManager
    private val selections = mutableListOf<PaymentTab>()

    @Before
    fun setUp() {
        context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Numo
        )
        toggleGroup = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        unifiedTab = button()
        cashuTab = button()
        lightningTab = button()
        listOf(unifiedTab, cashuTab, lightningTab).forEach(toggleGroup::addView)

        val qrCard = FrameLayout(context)
        unifiedQr = View(context).also(qrCard::addView)
        cashuQr = View(context).also(qrCard::addView)
        lightningQr = View(context).also(qrCard::addView)

        manager = PaymentTabManager(
            toggleGroup = toggleGroup,
            unifiedTab = unifiedTab,
            cashuTab = cashuTab,
            lightningTab = lightningTab,
            unifiedQrContainer = unifiedQr,
            cashuQrContainer = cashuQr,
            lightningQrContainer = lightningQr,
            unifiedQrImageView = View(context),
            unifiedLoadingSpinner = View(context),
            lightningLoadingSpinner = View(context),
            cashuLoadingSpinner = View(context),
            cashuQrImageView = View(context),
            lightningQrImageView = View(context)
        )
    }

    @Test
    fun `setup selects the saved default method and shows only its QR`() {
        setUpWithDefault(PaymentTab.CASHU)

        assertEquals(PaymentTab.CASHU, manager.getCurrentTab())
        assertEquals(cashuTab.id, toggleGroup.checkedButtonId)
        assertQrVisible(cashuQr)
        assertEquals(listOf(PaymentTab.CASHU), selections)
    }

    @Test
    fun `tapping a tab switches the checked button and visible QR`() {
        setUpWithDefault(PaymentTab.UNIFIED)

        lightningTab.performClick()

        assertEquals(PaymentTab.LIGHTNING, manager.getCurrentTab())
        assertEquals(lightningTab.id, toggleGroup.checkedButtonId)
        assertQrVisible(lightningQr)
        assertEquals(listOf(PaymentTab.UNIFIED, PaymentTab.LIGHTNING), selections)
    }

    @Test
    fun `long-pressing a tab makes it the default, moves it first and keeps it checked`() {
        setUpWithDefault(PaymentTab.UNIFIED)
        cashuTab.performClick()

        lightningTab.performLongClick()

        assertEquals(
            PaymentTab.LIGHTNING,
            DefaultPaymentMethodManager.getInstance(context).getDefaultPaymentMethod()
        )
        assertEquals(listOf(lightningTab, unifiedTab, cashuTab), buttonOrder())
        assertEquals(listOf(lightningTab.id), toggleGroup.checkedButtonIds)
        assertQrVisible(lightningQr)
    }

    @Test
    fun `long-pressing the selected tab keeps it checked after reordering`() {
        setUpWithDefault(PaymentTab.UNIFIED)
        cashuTab.performClick()

        cashuTab.performLongClick()

        assertEquals(listOf(cashuTab, unifiedTab, lightningTab), buttonOrder())
        assertEquals(listOf(cashuTab.id), toggleGroup.checkedButtonIds)
        assertQrVisible(cashuQr)
    }

    @Test
    fun `disabling cashu disables its button and selects lightning`() {
        setUpWithDefault(PaymentTab.CASHU)

        manager.disableTab(PaymentTabManager.Tab.CASHU)

        assertFalse(cashuTab.isEnabled)
        assertEquals(lightningTab.id, toggleGroup.checkedButtonId)
        assertQrVisible(lightningQr)
    }

    private fun setUpWithDefault(tab: PaymentTab) {
        DefaultPaymentMethodManager.getInstance(context).setDefaultPaymentMethod(tab)
        manager.setup(object : PaymentTabManager.TabSelectionListener {
            override fun onTabSelected(tab: PaymentTab) {
                selections += tab
            }
        })
    }

    private fun button() = MaterialButton(context).apply { id = View.generateViewId() }

    private fun buttonOrder() = (0 until toggleGroup.childCount).map { toggleGroup.getChildAt(it) }

    private fun assertQrVisible(visible: View) {
        listOf(unifiedQr, cashuQr, lightningQr).forEach { qr ->
            assertTrue(qr.visibility == if (qr === visible) View.VISIBLE else View.INVISIBLE)
        }
    }
}
