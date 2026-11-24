package com.electricdreams.numo.payment.card

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StripeCardConnectionTester(
    private val tokenClient: StripeConnectionTokenClient = StripeConnectionTokenClient(),
) {
    @MainThread
    suspend fun validate(context: Context, config: StripeCardConfig) {
        withContext(Dispatchers.IO) {
            tokenClient.createConnectionToken(config)
        }
        StripeTerminalManager.ensureReaderConnected(context, config)
    }
}
