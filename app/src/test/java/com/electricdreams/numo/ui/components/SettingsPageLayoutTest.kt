package com.electricdreams.numo.ui.components

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPageLayoutTest {

    @Test
    fun `content fills a phone and stays centered within a tablet`() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Numo_Settings
        )
        val page = SettingsPageLayout(context)
        val content = View(context)
        page.addView(content, FrameLayout.LayoutParams(-1, -1))
        val maxWidth = context.resources.getDimensionPixelSize(R.dimen.settings_content_max_width)

        measure(page, maxWidth / 2)
        assertEquals(page.width, content.width)
        assertEquals(0, content.left)

        measure(page, maxWidth * 2)
        assertEquals(maxWidth, content.width)
        assertEquals(maxWidth / 2, content.left)
    }

    @Test
    fun `system insets reduce the available content area`() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Numo_Settings
        )
        val page = SettingsPageLayout(context)
        val content = View(context)
        page.addView(content, FrameLayout.LayoutParams(-1, -1))
        page.setPadding(30, 20, 30, 40)
        measure(page, 300)
        assertEquals(240, content.width)
        assertEquals(30, content.left)
        assertEquals(20, content.top)
        assertEquals(40, page.height - content.bottom)
    }

    @Test
    fun `keyboard insets protect content and shrink again when keyboard closes`() {
        Robolectric.buildActivity(android.app.Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val page = SettingsPageLayout(activity)
            activity.setContentView(page)
            applySettingsWindowInsets(activity, page)
            val bars = Insets.of(4, 24, 6, 12)
            val withKeyboard = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200))
                .build()
            ViewCompat.dispatchApplyWindowInsets(page, withKeyboard)
            assertEquals(24, page.paddingTop)
            assertEquals(200, page.paddingBottom)
            assertEquals(4, page.paddingLeft)
            assertEquals(6, page.paddingRight)
            val withoutKeyboard = WindowInsetsCompat.Builder(withKeyboard)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE).build()
            ViewCompat.dispatchApplyWindowInsets(page, withoutKeyboard)
            assertEquals(12, page.paddingBottom)
        }
    }

    @Test
    fun `all settings pages inflate and measure within a compact viewport`() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Numo_Settings
        )
        val layouts = listOf(
            R.layout.activity_settings,
            R.layout.activity_theme_settings,
            R.layout.activity_language_settings,
            R.layout.activity_default_payment_method_settings,
            R.layout.activity_currency_settings,
            R.layout.activity_security_settings,
            R.layout.activity_developer_settings,
            R.layout.activity_about,
            R.layout.activity_btcpay_settings,
            R.layout.activity_webhook_settings,
            R.layout.activity_tips_settings,
            R.layout.activity_basket_names_settings,
            R.layout.activity_auto_withdraw_settings,
            R.layout.activity_item_list,
            R.layout.activity_item_entry,
            R.layout.activity_mints_settings,
            R.layout.activity_mint_details,
            R.layout.activity_seed_phrase,
            R.layout.activity_restore_wallet,
            R.layout.activity_device_backup_setup,
            R.layout.activity_device_backup_restore,
            R.layout.activity_pin_setup,
            R.layout.activity_pin_entry,
            R.layout.activity_pin_reset,
            R.layout.activity_withdraw_lightning,
            R.layout.activity_withdraw_melt_quote,
            R.layout.activity_withdraw_success,
            R.layout.activity_error_logs,
            R.layout.activity_wallet_logs,
        )
        layouts.forEach { layout ->
            val page = LayoutInflater.from(context).inflate(layout, null)
            measure(page, 360)
            assertEquals(context.resources.getResourceEntryName(layout), 360, page.measuredWidth)
        }
    }

    @Test
    fun `large multiline row content fits inside its padding`() {
        val base: Context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration(base.resources.configuration).apply { fontScale = 2f }
        val context = ContextThemeWrapper(
            base.createConfigurationContext(configuration), R.style.Theme_Numo_Settings
        )
        val page = LayoutInflater.from(context).inflate(R.layout.activity_settings, null)
        val row = page.findViewById<SettingsRowView>(R.id.basket_names_settings_item)
        row.setSubtitle("Create preset names for quick basket saving with a longer translated description")
        measure(page, (320 * context.resources.displayMetrics.density).toInt())
        val text = row.findViewById<View>(R.id.row_text_container)
        assertTrue("Text must respect top padding", text.top >= row.paddingTop)
        assertTrue("Text must respect bottom padding", text.bottom <= row.height - row.paddingBottom)
    }

    @Test
    fun `both search fields expose a working clear action`() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Numo_Settings
        )
        listOf(
            R.layout.activity_settings to R.id.search_input_layout,
            R.layout.activity_currency_settings to R.id.search_card,
        ).forEach { (layout, searchId) ->
            val page = LayoutInflater.from(context).inflate(layout, null)
            val search = page.findViewById<com.google.android.material.textfield.TextInputLayout>(searchId)
            val input = requireNotNull(search.editText)
            input.setText("EUR")
            search.findViewById<View>(com.google.android.material.R.id.text_input_end_icon).performClick()
            assertEquals("", input.text.toString())
        }
    }

    @Test
    fun `large settings titles stay between navigation and actions and grow the bar`() {
        val base: Context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration(base.resources.configuration).apply { fontScale = 2f }
        val context = ContextThemeWrapper(
            base.createConfigurationContext(configuration), R.style.Theme_Numo_Settings
        )
        val page = LayoutInflater.from(context)
            .inflate(R.layout.activity_btcpay_settings, null) as SettingsPageLayout
        val bar = page.findViewById<NumoTopBar>(R.id.top_bar)
        bar.setTitle("Payment server connection settings")
        measure(page, (360 * context.resources.displayMetrics.density).toInt())
        val title = bar.findViewById<TextView>(R.id.top_bar_title)
        val back = bar.findViewById<View>(R.id.top_bar_back)
        assertTrue(title.left >= back.right)
        assertTrue(title.right <= bar.width - bar.paddingRight)
        assertTrue(title.bottom <= bar.height - bar.paddingBottom)
        assertTrue(ViewCompat.isAccessibilityHeading(title))
        var navigated = false
        bar.onNavClick { navigated = true }
        back.performClick()
        assertTrue(navigated)
    }

    @Test
    fun `settings rows and form borders share the page gutter`() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Numo_Settings
        )
        val gutter = context.resources.getDimensionPixelSize(R.dimen.settings_page_margin)
        val layouts = listOf(
            R.layout.activity_settings to R.id.items_settings_item,
            R.layout.activity_theme_settings to R.id.dark_mode_switch,
            R.layout.activity_tips_settings to R.id.enable_tips_row,
            R.layout.activity_btcpay_settings to R.id.btcpay_server_url_input,
        )
        layouts.forEach { (layout, id) ->
            val page = LayoutInflater.from(context).inflate(layout, null) as android.view.ViewGroup
            measure(page, (360 * context.resources.displayMetrics.density).toInt())
            var content = page.findViewById<View>(id)
            if (id == R.id.btcpay_server_url_input) {
                // Compare the field border, preserving padding for text inside the field.
                content = content.parent.parent as View
            }
            val bounds = android.graphics.Rect(0, 0, content.width, content.height)
            page.offsetDescendantRectToMyCoords(content, bounds)
            assertEquals("Left gutter for $id", gutter, bounds.left)
            assertEquals("Right gutter for $id", gutter, page.width - bounds.right)
            if (id != R.id.btcpay_server_url_input) {
                assertEquals("No nested left inset for $id", 0, content.paddingLeft)
                assertEquals("No nested right inset for $id", 0, content.paddingRight)
            }
        }
    }

    private fun measure(page: View, width: Int) {
        page.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, page.measuredWidth, page.measuredHeight)
    }
}
