package com.electricdreams.numo.feature.settings

import android.content.Context
import androidx.annotation.StringRes

import com.electricdreams.numo.R
import com.electricdreams.numo.payment.card.CardSetupChecker.Check
import com.electricdreams.numo.payment.card.CardSetupChecker.Failure
import com.electricdreams.numo.payment.card.CardSetupChecker.Fix
import com.electricdreams.numo.payment.card.CardSetupChecker.Outcome

/** Wording for the card setup checks, shared by the check screen and the status page. */
internal object CardCheckText {

    /** What the screen says while [check] is under way. */
    @StringRes
    fun running(check: Check): Int = when (check) {
        Check.DEVICE_SUPPORTED -> R.string.stripe_card_check_running_device
        Check.NFC_ON -> R.string.stripe_card_check_running_nfc
        Check.LOCATION_PERMISSION -> R.string.stripe_card_check_running_location_permission
        // One request to Stripe answers all three.
        Check.KEY_ACCEPTED, Check.KEY_PERMISSION, Check.LOCATION_FOUND ->
            R.string.stripe_card_check_running_stripe
        Check.READER_STARTS -> R.string.stripe_card_check_reader_running
    }

    /** The problem in a few words; [failed] explains it. */
    @StringRes
    fun failureTitle(failure: Failure): Int = when (failure) {
        Failure.DEVICE_UNSUPPORTED -> R.string.stripe_card_headline_unsupported
        Failure.NFC_OFF -> R.string.stripe_card_check_problem_nfc
        Failure.LOCATION_PERMISSION_MISSING -> R.string.stripe_card_check_problem_location_permission
        Failure.NOT_SET_UP -> R.string.stripe_card_check_problem_not_set_up
        Failure.KEY_REJECTED -> R.string.stripe_card_check_problem_key
        Failure.KEY_PERMISSION_MISSING -> R.string.stripe_card_check_problem_permission
        Failure.LOCATION_NOT_FOUND -> R.string.stripe_card_check_problem_location
        Failure.STRIPE_UNREACHABLE -> R.string.stripe_card_check_problem_unreachable
        Failure.STRIPE_ERROR -> R.string.stripe_card_check_problem_stripe
        Failure.DEBUG_NEEDS_TEST_KEY,
        Failure.READER_NEEDS_DEVELOPER_MODE,
        Failure.READER_NEEDS_SIMULATION,
        Failure.SIMULATION_NEEDS_TEST_KEY,
        Failure.READER_FAILED -> R.string.stripe_card_check_problem_reader
    }

    /**
     * What to do about a problem, written to sit under [failureTitle] without repeating it.
     * [failed] is the same explanation as a sentence that stands on its own.
     */
    fun problemBody(context: Context, outcome: Outcome.Failed): String = when (outcome.failure) {
        Failure.DEVICE_UNSUPPORTED -> context.getString(R.string.stripe_card_body_unsupported)
        Failure.NFC_OFF -> context.getString(R.string.stripe_card_check_problem_nfc_body)
        Failure.KEY_REJECTED -> context.getString(R.string.stripe_card_check_problem_key_body)
        Failure.LOCATION_NOT_FOUND -> context.getString(R.string.stripe_card_check_problem_location_body)
        Failure.STRIPE_UNREACHABLE ->
            context.getString(R.string.stripe_card_check_problem_unreachable_body)
        Failure.READER_FAILED -> outcome.detail?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.stripe_card_check_problem_reader_body)
        else -> failed(context, outcome)
    }

    fun failed(context: Context, outcome: Outcome.Failed): String = when (outcome.failure) {
        Failure.DEVICE_UNSUPPORTED -> context.getString(R.string.stripe_card_check_device_failed)
        Failure.NFC_OFF -> context.getString(R.string.stripe_card_check_nfc_failed)
        Failure.LOCATION_PERMISSION_MISSING ->
            context.getString(R.string.stripe_card_check_location_permission_failed)
        Failure.NOT_SET_UP -> context.getString(R.string.stripe_card_check_key_not_set_up)
        Failure.KEY_REJECTED -> context.getString(R.string.stripe_card_key_rejected)
        Failure.KEY_PERMISSION_MISSING ->
            context.getString(R.string.stripe_card_check_permission_failed)
        Failure.LOCATION_NOT_FOUND -> context.getString(R.string.stripe_card_check_location_failed)
        Failure.STRIPE_UNREACHABLE -> context.getString(R.string.stripe_card_stripe_unreachable)
        Failure.STRIPE_ERROR -> context.getString(
            R.string.stripe_card_stripe_error,
            outcome.detail.orEmpty(),
        )
        Failure.DEBUG_NEEDS_TEST_KEY -> context.getString(R.string.stripe_card_debug_test_key)
        Failure.READER_NEEDS_DEVELOPER_MODE -> context.getString(R.string.stripe_card_debug_enable_developer)
        Failure.READER_NEEDS_SIMULATION ->
            context.getString(R.string.card_payment_debug_reader_unsupported)
        Failure.SIMULATION_NEEDS_TEST_KEY ->
            context.getString(R.string.card_payment_simulation_requires_test_key)
        Failure.READER_FAILED -> outcome.detail?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.stripe_card_check_reader_failed, it) }
            ?: context.getString(R.string.stripe_card_check_reader_failed_unknown)
    }

    /** Label of the fix button, or null when there is nothing the merchant can do here. */
    @StringRes
    fun fixLabel(fix: Fix, locationFix: LocationPermissionFix): Int? = when (fix) {
        Fix.NONE -> null
        Fix.OPEN_NFC_SETTINGS -> R.string.stripe_card_fix_nfc
        Fix.GRANT_LOCATION -> locationFix.actionLabel
        Fix.REPLACE_KEY -> R.string.stripe_card_fix_key
        Fix.CHOOSE_LOCATION -> R.string.stripe_card_fix_choose_location
        Fix.OPEN_ABOUT -> R.string.stripe_card_fix_about
        Fix.OPEN_DEVELOPER_SETTINGS -> R.string.stripe_card_fix_developer
        Fix.RETRY -> R.string.stripe_card_fix_retry
    }
}
