package com.electricdreams.numo.feature.settings

import android.content.Context
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BtcPaySettingsActivityTest {

    @Before
    fun clearPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("numo_prefs", "app_prefs", "NumoPrefs").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun `enable switch stays interactive and guides user to missing settings`() {
        ActivityScenario.launch(BtcPaySettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val enableSwitch = activity.findViewById<MaterialSwitch>(
                    R.id.btcpay_enable_switch,
                )
                val serverUrlInput = activity.findViewById<EditText>(
                    R.id.btcpay_server_url_input,
                )
                val apiKeyInput = activity.findViewById<EditText>(R.id.btcpay_api_key_input)
                val storeIdInput = activity.findViewById<EditText>(R.id.btcpay_store_id_input)
                val status = activity.findViewById<TextView>(R.id.test_connection_status)

                serverUrlInput.setText("")
                apiKeyInput.setText("")
                storeIdInput.setText("")

                assertTrue(enableSwitch.isEnabled)
                assertTrue(serverUrlInput.text.isBlank())
                assertTrue(apiKeyInput.text.isBlank())
                assertTrue(storeIdInput.text.isBlank())

                enableSwitch.isChecked = true

                assertFalse(enableSwitch.isChecked)
                assertEquals(
                    activity.getString(R.string.btcpay_test_fill_all_fields),
                    status.text.toString(),
                )
                assertTrue(serverUrlInput.hasFocus())
            }
        }
    }

    @Test
    fun `saves connection fields without enabling untested integration`() {
        ActivityScenario.launch(BtcPaySettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.btcpay_server_url_input)
                    .setText("https://test.btcpay.com")
                activity.findViewById<EditText>(R.id.btcpay_api_key_input)
                    .setText("test-key")
                activity.findViewById<EditText>(R.id.btcpay_store_id_input)
                    .setText("test-store")
            }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)

            scenario.onActivity { activity ->
                val prefs = PreferenceStore.app(activity)
                assertEquals(
                    "https://test.btcpay.com",
                    prefs.getString("btcpay_server_url"),
                )
                assertEquals("test-key", prefs.getString("btcpay_api_key"))
                assertEquals("test-store", prefs.getString("btcpay_store_id"))
                assertFalse(prefs.getBoolean("btcpay_enabled", false))
            }
        }
    }

    @Test
    fun `loads previously verified integration as enabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceStore.app(context)
        prefs.putString("btcpay_server_url", "https://test.btcpay.com")
        prefs.putString("btcpay_api_key", "test-key")
        prefs.putString("btcpay_store_id", "test-store")
        prefs.putBoolean("btcpay_enabled", true)

        ActivityScenario.launch(BtcPaySettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val enableSwitch = activity.findViewById<MaterialSwitch>(
                    R.id.btcpay_enable_switch,
                )

                assertTrue(enableSwitch.isEnabled)
                assertTrue(enableSwitch.isChecked)
            }
        }
    }
}
