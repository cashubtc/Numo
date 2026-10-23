package com.electricdreams.numo.payment

import android.content.Context
import com.electricdreams.numo.core.cashu.CashuWalletManager
import org.cashudevkit.Wallet
import org.cashudevkit.WalletConfig
import org.cashudevkit.WalletStore
import org.cashudevkit.generateMnemonic
import java.security.MessageDigest

internal object SwapWalletStore {
    fun open(context: Context, paymentId: String, mintUrl: String, unit: String): Wallet {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(paymentId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences("swap_wallets", Context.MODE_PRIVATE)
        val mnemonic = synchronized(this) {
            prefs.getString(key, null) ?: generateMnemonic().also {
                check(prefs.edit().putString(key, it).commit()) { "Could not save swap wallet" }
            }
        }
        val file = context.getDatabasePath("swap_$key.db")
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Could not create swap wallet directory"
        }
        return Wallet(mintUrl, CashuWalletManager.getCurrencyUnit(unit), mnemonic,
            WalletStore.Sqlite(file.absolutePath), WalletConfig(targetProofCount = 10u))
    }
}
