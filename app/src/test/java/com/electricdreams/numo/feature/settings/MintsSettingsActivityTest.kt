package com.electricdreams.numo.feature.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MintsSettingsActivityTest {
    private lateinit var mints: MintManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        CashuWalletManager.appContext = context
        mints = MintManager.getInstance(context)
        mints.setMintChangeListener(null)
        mints.getAllowedMints().forEach { mints.removeMint(it) }
        mints.addMint("https://mint.example")
        mints.setMintUnits("https://mint.example", listOf("usd"))
        mints.setMintRefreshTimestamp("https://mint.example")
        mints.setPreferredUnit("sat")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `merchant can switch to the only supported unit after removing sat mints`() {
        val controller = Robolectric.buildActivity(MintsSettingsActivity::class.java).setup()
        val activity = controller.get()
        try {
            // Balance loading returns through Dispatchers.IO before the card is updated.
            val deadline = System.nanoTime() + 3_000_000_000L
            val row = activity.findViewById<View>(R.id.active_unit_row)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (row.isShown) break
                Thread.yield()
            }
            assertEquals(View.VISIBLE, row.visibility)
            row.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotNull(dialog.listView.parent)
            assertEquals(1, dialog.listView.adapter.count)
            assertEquals(-1, dialog.listView.checkedItemPosition)
            dialog.listView.performItemClick(null, 0, 0)
            assertEquals("usd", mints.getPreferredUnit())
            assertEquals("https://mint.example", mints.getPreferredLightningMint())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
