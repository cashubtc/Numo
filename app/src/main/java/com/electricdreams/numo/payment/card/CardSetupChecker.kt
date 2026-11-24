package com.electricdreams.numo.payment.card

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Checks, step by step, whether this phone can take a card payment with the stored Stripe
 * setup, and says what to fix when it cannot.
 *
 * The phone and Stripe checks are quick; starting the reader takes several seconds and is only
 * run on request. The reader is only started when everything before it passed.
 */
class CardSetupChecker(private val environment: Environment) {

    enum class Group { PHONE, STRIPE, READER }

    /** The checks, in the order they are shown and run. */
    enum class Check(val group: Group) {
        DEVICE_SUPPORTED(Group.PHONE),
        NFC_ON(Group.PHONE),
        LOCATION_PERMISSION(Group.PHONE),
        KEY_ACCEPTED(Group.STRIPE),
        KEY_PERMISSION(Group.STRIPE),
        LOCATION_FOUND(Group.STRIPE),
        READER_STARTS(Group.READER),
    }

    enum class Failure {
        DEVICE_UNSUPPORTED,
        NFC_OFF,
        LOCATION_PERMISSION_MISSING,
        NOT_SET_UP,
        KEY_REJECTED,
        KEY_PERMISSION_MISSING,
        LOCATION_NOT_FOUND,
        STRIPE_UNREACHABLE,
        STRIPE_ERROR,
        READER_NEEDS_DEVELOPER_MODE,
        READER_NEEDS_SIMULATION,
        DEBUG_NEEDS_TEST_KEY,
        SIMULATION_NEEDS_TEST_KEY,
        READER_FAILED,
    }

    /** What the merchant can do about a failure. */
    enum class Fix {
        NONE,
        OPEN_NFC_SETTINGS,
        GRANT_LOCATION,
        REPLACE_KEY,
        CHOOSE_LOCATION,
        OPEN_ABOUT,
        OPEN_DEVELOPER_SETTINGS,
        RETRY,
    }

    sealed interface Outcome {
        data object Running : Outcome

        data object Passed : Outcome

        /** @property detail Stripe's own wording, when there is no better explanation. */
        data class Failed(val failure: Failure, val fix: Fix, val detail: String? = null) : Outcome

        /** Not run, because an earlier check has to pass first. */
        data object Skipped : Outcome
    }

    data class Report(val outcomes: Map<Check, Outcome>, val isTestMode: Boolean = false) {
        /** True when every check that was asked for passed. */
        val passed: Boolean get() = outcomes.isNotEmpty() && outcomes.values.all { it is Outcome.Passed }

        val failures: List<Outcome.Failed> get() = outcomes.values.filterIsInstance<Outcome.Failed>()
    }

    /** How starting the reader ended. */
    sealed interface ReaderResult {
        data object Started : ReaderResult

        data object DebugNeedsTestKey : ReaderResult

        data object NeedsDeveloperMode : ReaderResult

        /** A debuggable build cannot use the physical reader; the simulated one is needed. */
        data object NeedsSimulation : ReaderResult

        /** The simulated reader only works with a sandbox key. */
        data object SimulationNeedsTestKey : ReaderResult

        data class Error(val message: String?) : ReaderResult
    }

    /** What Stripe says about the stored location when asked for it directly. */
    enum class LocationStatus {
        FOUND,
        MISSING,

        /** Stripe would not say: the key may not read locations, or the request failed. */
        UNKNOWN,
    }

    /** Everything the checker needs from the phone and from Stripe; faked in tests. */
    interface Environment {
        fun isDeviceSupported(): Boolean

        fun isNfcEnabled(): Boolean

        fun hasLocationPermission(): Boolean

        suspend fun loadConfig(): StripeCardConfig?

        /** @throws IOException as [StripeConnectionTokenClient.createConnectionToken]. */
        suspend fun createConnectionToken(config: StripeCardConfig)

        /**
         * Looks the location up, because Stripe hands out a connection token for a location
         * that does not exist. Never throws: anything short of a clear answer is UNKNOWN.
         */
        suspend fun locationStatus(config: StripeCardConfig): LocationStatus

        suspend fun startReader(config: StripeCardConfig): ReaderResult
    }

    /**
     * Runs the checks in order, reporting each as it starts and as it resolves.
     *
     * @param includeReader false for the quick check, which leaves the reader alone.
     */
    suspend fun run(
        includeReader: Boolean,
        onUpdate: (Check, Outcome) -> Unit = { _, _ -> },
    ): Report {
        val outcomes = linkedMapOf<Check, Outcome>()
        fun set(check: Check, outcome: Outcome) {
            outcomes[check] = outcome
            onUpdate(check, outcome)
        }

        checkPhone(::set)
        val config = checkStripe(::set)

        if (includeReader) {
            if (config != null && outcomes.values.all { it is Outcome.Passed }) {
                set(Check.READER_STARTS, Outcome.Running)
                set(Check.READER_STARTS, readerOutcome(environment.startReader(config)))
            } else {
                set(Check.READER_STARTS, Outcome.Skipped)
            }
        }
        return Report(outcomes.toMap(), isTestMode = config?.isTestMode == true)
    }

    private fun checkPhone(set: (Check, Outcome) -> Unit) {
        if (!environment.isDeviceSupported()) {
            set(Check.DEVICE_SUPPORTED, Outcome.Failed(Failure.DEVICE_UNSUPPORTED, Fix.NONE))
            set(Check.NFC_ON, Outcome.Skipped)
            set(Check.LOCATION_PERMISSION, Outcome.Skipped)
            return
        }
        set(Check.DEVICE_SUPPORTED, Outcome.Passed)
        set(
            Check.NFC_ON,
            if (environment.isNfcEnabled()) Outcome.Passed
            else Outcome.Failed(Failure.NFC_OFF, Fix.OPEN_NFC_SETTINGS),
        )
        set(
            Check.LOCATION_PERMISSION,
            if (environment.hasLocationPermission()) Outcome.Passed
            else Outcome.Failed(Failure.LOCATION_PERMISSION_MISSING, Fix.GRANT_LOCATION),
        )
    }

    /**
     * Asking for a connection token fails differently for a bad key and a key without
     * permission. It does not prove the location, so that is looked up as well.
     *
     * @return the stored config, or null when nothing is set up.
     */
    private suspend fun checkStripe(set: (Check, Outcome) -> Unit): StripeCardConfig? {
        val config = environment.loadConfig()
        if (config == null) {
            set(Check.KEY_ACCEPTED, Outcome.Failed(Failure.NOT_SET_UP, Fix.REPLACE_KEY))
            set(Check.KEY_PERMISSION, Outcome.Skipped)
            set(Check.LOCATION_FOUND, Outcome.Skipped)
            return null
        }

        val stripeChecks = listOf(Check.KEY_ACCEPTED, Check.KEY_PERMISSION, Check.LOCATION_FOUND)
        stripeChecks.forEach { set(it, Outcome.Running) }

        val failure: Pair<Check, Outcome.Failed>? = try {
            environment.createConnectionToken(config)
            if (environment.locationStatus(config) == LocationStatus.MISSING) {
                Check.LOCATION_FOUND to Outcome.Failed(Failure.LOCATION_NOT_FOUND, Fix.CHOOSE_LOCATION)
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StripeConnectionTokenClient.StripeApiException) {
            stripeFailure(e)
        } catch (e: IOException) {
            Check.KEY_ACCEPTED to Outcome.Failed(Failure.STRIPE_UNREACHABLE, Fix.RETRY)
        }

        stripeChecks.forEach { check ->
            set(
                check,
                when {
                    failure == null || check.ordinal < failure.first.ordinal -> Outcome.Passed
                    check == failure.first -> failure.second
                    else -> Outcome.Skipped
                },
            )
        }
        return config
    }

    private fun stripeFailure(
        e: StripeConnectionTokenClient.StripeApiException,
    ): Pair<Check, Outcome.Failed> = when {
        e.httpStatus == HTTP_UNAUTHORIZED ->
            Check.KEY_ACCEPTED to Outcome.Failed(Failure.KEY_REJECTED, Fix.REPLACE_KEY)
        e.httpStatus == HTTP_FORBIDDEN ->
            Check.KEY_PERMISSION to Outcome.Failed(Failure.KEY_PERMISSION_MISSING, Fix.REPLACE_KEY)
        e.param == PARAM_LOCATION || e.stripeCode == CODE_RESOURCE_MISSING ->
            Check.LOCATION_FOUND to Outcome.Failed(Failure.LOCATION_NOT_FOUND, Fix.CHOOSE_LOCATION)
        else ->
            Check.KEY_ACCEPTED to Outcome.Failed(Failure.STRIPE_ERROR, Fix.RETRY, e.message)
    }

    private fun readerOutcome(result: ReaderResult): Outcome = when (result) {
        ReaderResult.Started -> Outcome.Passed
        ReaderResult.NeedsDeveloperMode ->
            Outcome.Failed(Failure.READER_NEEDS_DEVELOPER_MODE, Fix.OPEN_ABOUT)
        ReaderResult.NeedsSimulation ->
            Outcome.Failed(Failure.READER_NEEDS_SIMULATION, Fix.OPEN_DEVELOPER_SETTINGS)
        ReaderResult.DebugNeedsTestKey ->
            Outcome.Failed(Failure.DEBUG_NEEDS_TEST_KEY, Fix.REPLACE_KEY)
        ReaderResult.SimulationNeedsTestKey ->
            Outcome.Failed(Failure.SIMULATION_NEEDS_TEST_KEY, Fix.OPEN_DEVELOPER_SETTINGS)
        is ReaderResult.Error -> Outcome.Failed(Failure.READER_FAILED, Fix.RETRY, result.message)
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val PARAM_LOCATION = "location"
        private const val CODE_RESOURCE_MISSING = "resource_missing"
    }
}
