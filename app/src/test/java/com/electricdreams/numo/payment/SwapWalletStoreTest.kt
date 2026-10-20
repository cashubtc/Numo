package com.electricdreams.numo.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.cashudevkit.Wallet
import org.cashudevkit.WalletStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SwapWalletStoreTest {
    @Test
    fun `reopening a swap uses the same saved seed and disk database`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val id = "saved-payment"
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("swap_wallets", Context.MODE_PRIVATE).edit()
            .putString(key, "saved swap seed").commit()
        val paths = mutableListOf<String>()
        mockConstruction(Wallet::class.java) { _, construction ->
            assertEquals("saved swap seed", construction.arguments()[2])
            val database = construction.arguments()[3] as WalletStore.Sqlite
            assertFalse(database.path.contains(":memory:"))
            paths.add(database.path)
        }.use {
            SwapWalletStore.open(context, id, "https://source.example", "sat").close()
            SwapWalletStore.open(context, id, "https://source.example", "sat").close()
        }
        assertEquals(2, paths.size)
        assertEquals(paths.first(), paths.last())
    }
}
