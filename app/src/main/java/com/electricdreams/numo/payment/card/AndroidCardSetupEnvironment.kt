package com.electricdreams.numo.payment.card

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.util.Log
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.external.models.TerminalErrorCode
import com.stripe.stripeterminal.external.models.TerminalException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The real phone and the real Stripe, for [CardSetupChecker]. */
class AndroidCardSetupEnvironment(
    context: Context,
    private val settings: StripeCardSettings = StripeCardSettings.getInstance(context),
    private val tokenClient: StripeConnectionTokenClient = StripeConnectionTokenClient(),
    private val locationsClient: StripeLocationsClient = StripeLocationsClient(),
) : CardSetupChecker.Environment {

    private val appContext = context.applicationContext

    override fun isDeviceSupported(): Boolean = StripeTerminalManager.isDeviceSupported(appContext)

    override fun isNfcEnabled(): Boolean = NfcAdapter.getDefaultAdapter(appContext)?.isEnabled == true

    override fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    override fun loadConfig(): StripeCardConfig? = settings.loadConfig()

    override suspend fun createConnectionToken(config: StripeCardConfig) {
        withContext(Dispatchers.IO) { tokenClient.createConnectionToken(config) }
    }

    override suspend fun locationStatus(config: StripeCardConfig): CardSetupChecker.LocationStatus =
        withContext(Dispatchers.IO) {
            try {
                locationsClient.getLocation(config.restrictedKey, config.locationId)
                CardSetupChecker.LocationStatus.FOUND
            } catch (e: StripeConnectionTokenClient.StripeApiException) {
                // A key without the locations permission is refused before Stripe looks.
                if (e.httpStatus == HTTP_NOT_FOUND) CardSetupChecker.LocationStatus.MISSING
                else CardSetupChecker.LocationStatus.UNKNOWN
            } catch (e: IOException) {
                Log.w(TAG, "Could not look up the Stripe location", e)
                CardSetupChecker.LocationStatus.UNKNOWN
            }
        }

    override suspend fun startReader(config: StripeCardConfig): CardSetupChecker.ReaderResult =
        withContext(Dispatchers.Main) {
            // Stripe rejects a second connection attempt while one is under way.
            readerLock.withLock { connectReader(config) }
        }

    private suspend fun connectReader(config: StripeCardConfig): CardSetupChecker.ReaderResult = try {
        StripeTerminalManager.ensureReaderConnected(appContext, config)
        CardSetupChecker.ReaderResult.Started
    } catch (e: CancellationException) {
        throw e
    } catch (e: StripeTerminalManager.CardPaymentException) {
        Log.w(TAG, "Reader did not start: ${e.message}", e)
        if (e.reason == StripeTerminalManager.FailureReason.SIMULATION_REQUIRES_TEST_KEY) {
            CardSetupChecker.ReaderResult.SimulationNeedsTestKey
        } else {
            CardSetupChecker.ReaderResult.Error(e.message)
        }
    } catch (e: TerminalException) {
        Log.w(TAG, "Reader did not start: ${e.errorCode}", e)
        if (e.errorCode == TerminalErrorCode.TAP_TO_PAY_DEBUG_NOT_SUPPORTED) {
            CardSetupChecker.ReaderResult.NeedsSimulation
        } else {
            CardSetupChecker.ReaderResult.Error(e.errorMessage)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Reader did not start", e)
        CardSetupChecker.ReaderResult.Error(e.message)
    }

    companion object {
        private const val TAG = "CardSetupEnvironment"
        private const val HTTP_NOT_FOUND = 404
        private val readerLock = Mutex()
    }
}
