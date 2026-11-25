package com.electricdreams.numo.payment

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaymentTabManagerCardTest {

    private lateinit var context: Context
    private lateinit var container: LinearLayout
    private lateinit var unifiedTab: LinearLayout
    private lateinit var cashuTab: LinearLayout
    private lateinit var lightningTab: LinearLayout
    private lateinit var cardTab: LinearLayout
    private lateinit var cardContainer: View
    private lateinit var manager: PaymentTabManager
    private val selected = mutableListOf<PaymentTab>()

    @Before
    fun setUp() {
        context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Numo,
        )
        container = LinearLayout(context)
        unifiedTab = tab()
        cashuTab = tab()
        lightningTab = tab()
        cardTab = tab().apply { visibility = View.GONE }
        cardContainer = FrameLayout(context)

        manager = PaymentTabManager(
            unifiedTab = unifiedTab,
            cashuTab = cashuTab,
            lightningTab = lightningTab,
            unifiedTabText = TextView(context),
            cashuTabText = TextView(context),
            lightningTabText = TextView(context),
            unifiedTabIcon = TextView(context),
            cashuTabIcon = ImageView(context),
            lightningTabIcon = ImageView(context),
            unifiedQrContainer = FrameLayout(context),
            cashuQrContainer = FrameLayout(context),
            lightningQrContainer = FrameLayout(context),
            unifiedQrImageView = View(context),
            unifiedLoadingSpinner = View(context),
            lightningLoadingSpinner = View(context),
            cashuLoadingSpinner = View(context),
            cashuQrImageView = View(context),
            lightningQrImageView = View(context),
            resources = context.resources,
            theme = context.theme,
            cardTab = cardTab,
            cardTabText = TextView(context),
            cardTabIcon = ImageView(context),
            cardContainer = cardContainer,
        )
        DefaultPaymentMethodManager.getInstance(context).setDefaultPaymentMethod(PaymentTab.LIGHTNING)
        manager.setup(object : PaymentTabManager.TabSelectionListener {
            override fun onTabSelected(tab: PaymentTab) {
                selected += tab
            }
        })
    }

    private fun tab() = LinearLayout(context).also { container.addView(it) }

    @Test
    fun `checkout only contains Bitcoin and Card with Bitcoin selected by default`() {
        assertEquals(2, container.childCount)
        assertEquals(0, container.indexOfChild(unifiedTab))
        assertEquals(1, container.indexOfChild(cardTab))
        assertEquals(View.GONE, cashuTab.visibility)
        assertEquals(View.GONE, lightningTab.visibility)
        assertEquals(PaymentTab.UNIFIED, manager.getCurrentTab())
        assertEquals(PaymentTab.UNIFIED, selected.last())
        assertEquals("Bitcoin", context.getString(R.string.payment_request_tab_unified))
    }

    @Test
    fun `legacy Cashu and Lightning selections resolve to unified Bitcoin`() {
        manager.selectTab(PaymentTab.CASHU)
        assertEquals(PaymentTab.UNIFIED, manager.getCurrentTab())

        manager.selectTab(PaymentTab.LIGHTNING)
        assertEquals(PaymentTab.UNIFIED, manager.getCurrentTab())
    }

    @Test
    fun `selecting the card tab shows the card panel`() {
        manager.setCardTabAvailable(true)

        cardTab.performClick()

        assertEquals(PaymentTab.CARD, manager.getCurrentTab())
        assertEquals(View.VISIBLE, cardContainer.visibility)
        assertEquals(PaymentTab.CARD, selected.last())
    }

    @Test
    fun `hiding the card tab while selected falls back to Bitcoin`() {
        manager.setCardTabAvailable(true)
        cardTab.performClick()

        manager.setCardTabAvailable(false)

        assertEquals(View.GONE, cardTab.visibility)
        assertEquals(PaymentTab.UNIFIED, manager.getCurrentTab())
        assertEquals(View.INVISIBLE, cardContainer.visibility)
    }

    @Test
    fun `card default is selected only after the card tab is available`() {
        DefaultPaymentMethodManager.getInstance(context).setDefaultPaymentMethod(PaymentTab.CARD)

        manager.selectDefaultPaymentMethod()
        assertEquals(PaymentTab.UNIFIED, manager.getCurrentTab())

        manager.setCardTabAvailable(true)
        manager.selectDefaultPaymentMethod()

        assertEquals(PaymentTab.CARD, manager.getCurrentTab())
        assertEquals(PaymentTab.CARD, selected.last())
    }

    @Test
    fun `long pressing either checkout tab saves it as the default`() {
        val defaults = DefaultPaymentMethodManager.getInstance(context)
        manager.setCardTabAvailable(true)

        assertTrue(cardTab.performLongClick())
        assertEquals(PaymentTab.CARD, defaults.getDefaultPaymentMethod())
        assertEquals(PaymentTab.CARD, manager.getCurrentTab())

        assertTrue(unifiedTab.performLongClick())
        assertEquals(PaymentTab.UNIFIED, defaults.getDefaultPaymentMethod())
        assertEquals(PaymentTab.UNIFIED, manager.getCurrentTab())
    }
}
