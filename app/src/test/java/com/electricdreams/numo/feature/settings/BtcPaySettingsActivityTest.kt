package com.electricdreams.numo.feature.settings

import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BtcPaySettingsActivityTest {

    @Test
    fun `row tap respects incomplete configuration and enables after all fields are provided`() {
        ActivityScenario.launch(BtcPaySettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val server = activity.findViewById<EditText>(R.id.btcpay_server_url_input)
                val key = activity.findViewById<EditText>(R.id.btcpay_api_key_input)
                val store = activity.findViewById<EditText>(R.id.btcpay_store_id_input)
                server.setText("")
                key.setText("")
                store.setText("")
                val toggle = activity.findViewById<MaterialSwitch>(R.id.btcpay_enable_switch)
                val row = activity.findViewById<View>(R.id.enable_toggle_row)
                row.performClick()
                assertFalse(toggle.isChecked)
                assertFalse(toggle.isEnabled)
                server.setText("https://test.btcpay.com")
                key.setText("test-key")
                store.setText("test-store")
                row.performClick()
                assertTrue(toggle.isChecked)
                assertTrue(PreferenceStore.app(activity).getBoolean("btcpay_enabled", false))
            }
        }
    }

    @Test
    fun `loads and saves settings correctly`() {
        val scenario = ActivityScenario.launch(BtcPaySettingsActivity::class.java)

        scenario.onActivity { activity ->
            // Simulate user input
            val serverUrlInput = activity.findViewById<EditText>(R.id.btcpay_server_url_input)
            val apiKeyInput = activity.findViewById<EditText>(R.id.btcpay_api_key_input)
            val storeIdInput = activity.findViewById<EditText>(R.id.btcpay_store_id_input)
            val enableSwitch = activity.findViewById<MaterialSwitch>(R.id.btcpay_enable_switch)

            // Set values
            serverUrlInput.setText("https://test.btcpay.com")
            apiKeyInput.setText("test-key")
            storeIdInput.setText("test-store")
            enableSwitch.isChecked = true
        }

        // Trigger lifecycle to save (onPause)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)

        scenario.onActivity { activity ->
            // Verify prefs
            val prefs = PreferenceStore.app(activity)
            assertEquals("https://test.btcpay.com", prefs.getString("btcpay_server_url"))
            assertEquals("test-key", prefs.getString("btcpay_api_key"))
            assertEquals("test-store", prefs.getString("btcpay_store_id"))
            assertTrue(prefs.getBoolean("btcpay_enabled", false))
        }
    }
}
