package com.electricdreams.numo.payment.card

import com.electricdreams.numo.payment.card.CardSetupChecker.Check
import com.electricdreams.numo.payment.card.CardSetupChecker.Failure
import com.electricdreams.numo.payment.card.CardSetupChecker.Fix
import com.electricdreams.numo.payment.card.CardSetupChecker.LocationStatus
import com.electricdreams.numo.payment.card.CardSetupChecker.Outcome
import com.electricdreams.numo.payment.card.CardSetupChecker.ReaderResult
import com.electricdreams.numo.payment.card.StripeConnectionTokenClient.StripeApiException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSetupCheckerTest {

    /** A phone and Stripe account where everything works, until a test says otherwise. */
    private class FakeEnvironment : CardSetupChecker.Environment {
        var deviceSupported = true
        var nfcEnabled = true
        var locationPermission = true
        var config: StripeCardConfig? = StripeCardConfig("rk_test_abc123", "tml_shop")
        var tokenError: IOException? = null
        var locationStatus = LocationStatus.FOUND
        var readerResult: ReaderResult = ReaderResult.Started
        var tokenRequests = 0
        var readerStarts = 0

        override fun isDeviceSupported() = deviceSupported
        override fun isNfcEnabled() = nfcEnabled
        override fun hasLocationPermission() = locationPermission
        override suspend fun loadConfig() = config

        override suspend fun createConnectionToken(config: StripeCardConfig) {
            tokenRequests++
            tokenError?.let { throw it }
        }

        override suspend fun locationStatus(config: StripeCardConfig) = locationStatus

        override suspend fun startReader(config: StripeCardConfig): ReaderResult {
            readerStarts++
            return readerResult
        }
    }

    private val environment = FakeEnvironment()
    private val checker = CardSetupChecker(environment)

    private fun failed(failure: Failure, fix: Fix, detail: String? = null) =
        Outcome.Failed(failure, fix, detail)

    @Test
    fun `everything working passes every check and starts the reader`() = runTest {
        val report = checker.run(includeReader = true)

        assertTrue(report.passed)
        assertEquals(Check.entries.toList(), report.outcomes.keys.toList())
        assertEquals(1, environment.readerStarts)
    }

    @Test
    fun `the quick check leaves the reader alone`() = runTest {
        val report = checker.run(includeReader = false)

        assertTrue(report.passed)
        assertFalse(report.outcomes.containsKey(Check.READER_STARTS))
        assertEquals(0, environment.readerStarts)
    }

    @Test
    fun `an unsupported phone skips the other phone checks but still checks Stripe`() = runTest {
        environment.deviceSupported = false

        val report = checker.run(includeReader = true)

        assertEquals(failed(Failure.DEVICE_UNSUPPORTED, Fix.NONE), report.outcomes[Check.DEVICE_SUPPORTED])
        assertEquals(Outcome.Skipped, report.outcomes[Check.NFC_ON])
        assertEquals(Outcome.Skipped, report.outcomes[Check.LOCATION_PERMISSION])
        assertEquals(Outcome.Passed, report.outcomes[Check.KEY_ACCEPTED])
        assertEquals(Outcome.Skipped, report.outcomes[Check.READER_STARTS])
    }

    @Test
    fun `NFC off and missing location permission are reported together with their fixes`() = runTest {
        environment.nfcEnabled = false
        environment.locationPermission = false

        val report = checker.run(includeReader = true)

        assertEquals(failed(Failure.NFC_OFF, Fix.OPEN_NFC_SETTINGS), report.outcomes[Check.NFC_ON])
        assertEquals(
            failed(Failure.LOCATION_PERMISSION_MISSING, Fix.GRANT_LOCATION),
            report.outcomes[Check.LOCATION_PERMISSION],
        )
        assertEquals(2, report.failures.size)
        assertEquals(0, environment.readerStarts)
    }

    @Test
    fun `nothing saved fails the key check without calling Stripe`() = runTest {
        environment.config = null

        val report = checker.run(includeReader = true)

        assertEquals(failed(Failure.NOT_SET_UP, Fix.REPLACE_KEY), report.outcomes[Check.KEY_ACCEPTED])
        assertEquals(Outcome.Skipped, report.outcomes[Check.KEY_PERMISSION])
        assertEquals(Outcome.Skipped, report.outcomes[Check.LOCATION_FOUND])
        assertEquals(0, environment.tokenRequests)
    }

    @Test
    fun `a key Stripe rejects fails the first Stripe check and skips the rest`() = runTest {
        environment.tokenError = StripeApiException("Invalid API Key provided", 401, null)

        val report = checker.run(includeReader = true)

        assertEquals(failed(Failure.KEY_REJECTED, Fix.REPLACE_KEY), report.outcomes[Check.KEY_ACCEPTED])
        assertEquals(Outcome.Skipped, report.outcomes[Check.KEY_PERMISSION])
        assertEquals(Outcome.Skipped, report.outcomes[Check.LOCATION_FOUND])
        assertEquals(Outcome.Skipped, report.outcomes[Check.READER_STARTS])
    }

    @Test
    fun `a key without permission is accepted but fails the permission check`() = runTest {
        environment.tokenError = StripeApiException("Missing permissions", 403, null)

        val report = checker.run(includeReader = false)

        assertEquals(Outcome.Passed, report.outcomes[Check.KEY_ACCEPTED])
        assertEquals(
            failed(Failure.KEY_PERMISSION_MISSING, Fix.REPLACE_KEY),
            report.outcomes[Check.KEY_PERMISSION],
        )
        assertEquals(Outcome.Skipped, report.outcomes[Check.LOCATION_FOUND])
    }

    @Test
    fun `a location Stripe no longer has fails the location check even though a token was issued`() = runTest {
        environment.locationStatus = LocationStatus.MISSING

        val report = checker.run(includeReader = true)

        assertEquals(Outcome.Passed, report.outcomes[Check.KEY_ACCEPTED])
        assertEquals(Outcome.Passed, report.outcomes[Check.KEY_PERMISSION])
        assertEquals(
            failed(Failure.LOCATION_NOT_FOUND, Fix.CHOOSE_LOCATION),
            report.outcomes[Check.LOCATION_FOUND],
        )
        assertEquals(Outcome.Skipped, report.outcomes[Check.READER_STARTS])
        assertEquals(0, environment.readerStarts)
    }

    @Test
    fun `a location Stripe will not answer for still passes`() = runTest {
        environment.locationStatus = LocationStatus.UNKNOWN

        assertTrue(checker.run(includeReader = true).passed)
    }

    @Test
    fun `an unknown location passes the key checks and fails the location check`() = runTest {
        environment.tokenError =
            StripeApiException("No such location", 400, "resource_missing", param = "location")

        val report = checker.run(includeReader = false)

        assertEquals(Outcome.Passed, report.outcomes[Check.KEY_ACCEPTED])
        assertEquals(Outcome.Passed, report.outcomes[Check.KEY_PERMISSION])
        assertEquals(
            failed(Failure.LOCATION_NOT_FOUND, Fix.CHOOSE_LOCATION),
            report.outcomes[Check.LOCATION_FOUND],
        )
    }

    @Test
    fun `no internet is reported as unreachable, not as a bad key`() = runTest {
        environment.tokenError = IOException("timeout")

        val report = checker.run(includeReader = false)

        assertEquals(failed(Failure.STRIPE_UNREACHABLE, Fix.RETRY), report.outcomes[Check.KEY_ACCEPTED])
    }

    @Test
    fun `other Stripe errors keep Stripe's own message`() = runTest {
        environment.tokenError = StripeApiException("Rate limit exceeded", 429, "rate_limit")

        val report = checker.run(includeReader = false)

        assertEquals(
            failed(Failure.STRIPE_ERROR, Fix.RETRY, "Rate limit exceeded"),
            report.outcomes[Check.KEY_ACCEPTED],
        )
    }

    @Test
    fun `a debug build is told to use the simulated reader`() = runTest {
        environment.readerResult = ReaderResult.NeedsSimulation

        val report = checker.run(includeReader = true)

        assertFalse(report.passed)
        assertEquals(
            failed(Failure.READER_NEEDS_SIMULATION, Fix.OPEN_DEVELOPER_SETTINGS),
            report.outcomes[Check.READER_STARTS],
        )
    }

    @Test
    fun `a reader error carries its message`() = runTest {
        environment.readerResult = ReaderResult.Error("Bluetooth is off")

        val report = checker.run(includeReader = true)

        assertEquals(
            failed(Failure.READER_FAILED, Fix.RETRY, "Bluetooth is off"),
            report.outcomes[Check.READER_STARTS],
        )
    }

    @Test
    fun `each check is announced as running before it resolves`() = runTest {
        val updates = mutableListOf<Pair<Check, Outcome>>()

        checker.run(includeReader = true) { check, outcome -> updates += check to outcome }

        val reader = updates.filter { it.first == Check.READER_STARTS }.map { it.second }
        assertEquals(listOf(Outcome.Running, Outcome.Passed), reader)
        val key = updates.filter { it.first == Check.KEY_ACCEPTED }.map { it.second }
        assertEquals(listOf(Outcome.Running, Outcome.Passed), key)
    }
}
