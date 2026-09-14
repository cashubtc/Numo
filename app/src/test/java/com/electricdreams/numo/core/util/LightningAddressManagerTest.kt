package com.electricdreams.numo.core.util

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LightningAddressManagerTest {
    @Test
    fun `incomplete hostname is invalid while editing a saved address`() {
        val manager = LightningAddressManager.getInstance(RuntimeEnvironment.getApplication())
        assertFalse(manager.isValidLightningAddress("user@.com"))
    }

    @Test
    fun `validation rejects empty host labels and hosts rejected by OkHttp`() {
        val manager = LightningAddressManager.getInstance(RuntimeEnvironment.getApplication())
        for (address in listOf("user@x.", "user@a..b", "user@exa:mple.com", "user@exa\\mple.com")) {
            assertFalse(address, manager.isValidLightningAddress(address))
        }
    }

    @Test
    fun `validation accepts supported addresses and ignores surrounding whitespace`() {
        val manager = LightningAddressManager.getInstance(RuntimeEnvironment.getApplication())
        for (address in listOf("alice@example.com", " alice+shop@EXAMPLE.com ", "alice@bücher.example")) {
            assertTrue(address, manager.isValidLightningAddress(address))
        }
    }
}
