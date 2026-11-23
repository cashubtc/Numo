package com.electricdreams.numo.feature.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityStripeCardSetupBinding
import com.electricdreams.numo.payment.card.StripeCardConfig
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.electricdreams.numo.payment.card.StripeConnectionTokenClient.StripeApiException
import com.electricdreams.numo.payment.card.StripeLocation
import com.electricdreams.numo.payment.card.StripeLocationAddress
import com.electricdreams.numo.payment.card.StripeLocationsClient
import com.electricdreams.numo.payment.card.StripeTerminalManager
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Guided setup for card payments: paste a Stripe key, then pick or add the shop location. The
 * setup check follows in [StripeCardCheckActivity].
 *
 * The same screen edits one part later (the key or the location), opened at that step.
 */
class StripeCardSetupActivity : AppCompatActivity() {

    private enum class Mode { SETUP, EDIT_KEY, EDIT_LOCATION }

    private enum class Step { KEY, LOCATION, ADD_LOCATION }

    private lateinit var binding: ActivityStripeCardSetupBinding
    private lateinit var settings: StripeCardSettings

    /** Replaceable so tests can answer for Stripe. */
    internal var locationsClient = StripeLocationsClient()

    private lateinit var mode: Mode
    private var step = Step.KEY
    private var isBusy = false

    /** Locations of the account, once listed; null until then or when the key may not list them. */
    private var locations: List<StripeLocation>? = null
    private var isManualLocation = false
    private var selectedLocationId: String? = null
    private val countryCodesByName = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStripeCardSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        settings = StripeCardSettings.getInstance(this)
        mode = Mode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: Mode.SETUP
        selectedLocationId = settings.storedLocationId().ifEmpty { null }

        setupKeyStep()
        setupLocationStep()
        setupAddLocationStep()
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        binding.nextButton.setOnClickListener { onNext() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        showStep(if (mode == Mode.EDIT_LOCATION) Step.LOCATION else Step.KEY, animate = false)
    }

    // region Steps

    private fun showStep(target: Step, animate: Boolean = true) {
        val forward = target.ordinal >= step.ordinal
        step = target
        hideKeyboard()
        if (animate) {
            // Steps slide along one axis, so going back visibly goes back.
            TransitionManager.beginDelayedTransition(
                binding.stepContainer,
                MaterialSharedAxis(MaterialSharedAxis.X, forward).setDuration(STEP_ANIMATION_MS),
            )
        }
        mapOf(
            Step.KEY to binding.keyContainer,
            Step.LOCATION to binding.locationContainer,
            Step.ADD_LOCATION to binding.addLocationContainer,
        ).forEach { (containerStep, view) ->
            view.visibility = if (containerStep == target) View.VISIBLE else View.GONE
        }

        val position = if (target == Step.KEY) 1 else 2
        binding.progressHeader.visibility = if (mode == Mode.SETUP) View.VISIBLE else View.GONE
        binding.stepProgress.setProgressCompat(position, animate)
        binding.topBar.setTitle(
            getString(
                when (target) {
                    Step.KEY -> R.string.stripe_card_key_heading
                    Step.LOCATION -> R.string.stripe_card_location_heading
                    Step.ADD_LOCATION -> R.string.stripe_card_add_location_heading
                }
            )
        )
        updateNextButton()
        if (target == Step.LOCATION) enterLocationStep()
    }

    private fun goBack() {
        if (isBusy) return
        when {
            step == Step.ADD_LOCATION -> showStep(Step.LOCATION)
            step == Step.LOCATION && mode == Mode.SETUP -> showStep(Step.KEY)
            else -> finish()
        }
    }

    /**
     * The keyboard's action key submits the step. A hardware Enter arrives as both a press and
     * a release, and only the press may count, or the step would be submitted twice.
     */
    private val submitOnAction = TextView.OnEditorActionListener { _, _, event ->
        if (event == null || event.action == KeyEvent.ACTION_DOWN) onNext()
        true
    }

    private fun onNext() {
        if (isBusy) return
        when (step) {
            Step.KEY -> submitKey()
            Step.LOCATION -> submitLocation()
            Step.ADD_LOCATION -> submitNewLocation()
        }
    }

    private fun updateNextButton() {
        binding.nextButton.isEnabled = !isBusy && when (step) {
            Step.KEY -> true
            Step.LOCATION -> isManualLocation || selectedLocationId != null && locations != null
            Step.ADD_LOCATION -> true
        }
        binding.nextButton.setText(
            when {
                isBusy && step == Step.KEY -> R.string.stripe_card_setup_checking_key
                isBusy && step == Step.ADD_LOCATION -> R.string.stripe_card_add_location_saving
                step == Step.ADD_LOCATION -> R.string.stripe_card_add_location_submit
                else -> R.string.stripe_card_setup_next
            }
        )
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        // A waiting button that shows no motion looks frozen.
        binding.nextButton.icon = if (busy) busyIndicator else null
        updateNextButton()
    }

    private val busyIndicator by lazy {
        val spec = CircularProgressIndicatorSpec(
            this,
            null,
            0,
            com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall,
        )
        spec.indicatorColors = intArrayOf(binding.nextButton.currentTextColor)
        IndeterminateDrawable.createCircularDrawable(this, spec)
    }

    /** Leaves the flow: on to the setup check, or back to whoever asked for the edit. */
    private fun done() {
        if (isFinishing) return
        StripeTerminalManager.reset()
        if (intent.getBooleanExtra(EXTRA_THEN_CHECK, true)) {
            startActivity(StripeCardCheckActivity.intent(this, enableOnPass = false))
        }
        finish()
    }

    // endregion

    // region Step 1: key

    private fun setupKeyStep() {
        binding.openStripeButton.setOnClickListener { openStripe() }
        binding.stripeKeyLayout.setEndIconOnClickListener { paste(binding.stripeKeyInput) }
        binding.stripeKeyInput.doAfterTextChanged { binding.stripeKeyLayout.error = null }
        binding.stripeKeyInput.setOnEditorActionListener(submitOnAction)
        showCurrentKey(settings.maskedKey())
    }

    /**
     * Shows the stored key, masked, where a typed key would be. It is a placeholder, so the real
     * key never enters the field and typing or pasting replaces it at once.
     */
    internal fun showCurrentKey(masked: String?) {
        val layout = binding.stripeKeyLayout
        // The label stays on the outline, or it would cover the placeholder until focus.
        layout.isExpandedHintEnabled = masked == null
        layout.placeholderText = masked
        layout.helperText = masked?.let { getString(R.string.stripe_card_key_replace_hint) }
    }

    private fun submitKey() {
        val typed = binding.stripeKeyInput.text?.toString()?.trim().orEmpty()
        val key = typed.ifEmpty { settings.storedKey().orEmpty() }
        if (!StripeCardConfig.isValidRestrictedKey(key)) {
            binding.stripeKeyLayout.error = getString(R.string.stripe_card_invalid_key)
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            // Listing locations proves the key and fetches what step 2 needs in one request.
            val result = withContext(Dispatchers.IO) {
                runCatching { locationsClient.listLocations(key) }
            }
            setBusy(false)
            val error = result.exceptionOrNull()
            when {
                error == null -> acceptKey(key, result.getOrThrow())
                // A key without the locations permission still works for taking payments.
                error is StripeApiException && error.httpStatus == HTTP_FORBIDDEN -> acceptKey(key, null)
                else -> {
                    Log.w(TAG, "Stripe did not accept the key", error)
                    binding.stripeKeyLayout.error = stripeErrorText(error)
                }
            }
        }
    }

    private fun acceptKey(key: String, listed: List<StripeLocation>?) {
        if (!settings.saveKey(key)) {
            Toast.makeText(this, R.string.stripe_card_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        locations = listed
        isManualLocation = listed == null
        if (mode == Mode.EDIT_KEY) done() else showStep(Step.LOCATION)
    }

    private fun stripeErrorText(error: Throwable): String = when {
        error is StripeApiException && error.httpStatus == HTTP_UNAUTHORIZED ->
            getString(R.string.stripe_card_key_rejected)
        error is StripeApiException ->
            getString(R.string.stripe_card_stripe_error, error.message.orEmpty())
        else -> getString(R.string.stripe_card_stripe_unreachable)
    }

    private fun openStripe() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STRIPE_API_KEYS_URL)))
        } catch (e: Exception) {
            Log.w(TAG, "No browser to open Stripe", e)
            Toast.makeText(this, R.string.stripe_card_setup_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // endregion

    // region Step 2: location

    private fun setupLocationStep() {
        binding.locationRetryButton.setOnClickListener { loadLocations() }
        binding.addLocationButton.setOnClickListener { showStep(Step.ADD_LOCATION) }
        binding.useOtherKeyButton.setOnClickListener {
            if (mode == Mode.SETUP) showStep(Step.KEY) else {
                startActivity(editKeyIntent(this, intent.getBooleanExtra(EXTRA_THEN_CHECK, true)))
                finish()
            }
        }
        binding.locationIdLayout.setEndIconOnClickListener { paste(binding.locationIdInput) }
        binding.locationIdInput.doAfterTextChanged { binding.locationIdLayout.error = null }
        binding.locationIdInput.setOnEditorActionListener(submitOnAction)
        binding.locationList.setOnCheckedChangeListener { group, checkedId ->
            selectedLocationId = group.findViewById<RadioButton>(checkedId)?.tag as? String
            updateNextButton()
        }
    }

    private fun enterLocationStep() {
        when {
            isManualLocation -> showManualLocation()
            locations != null -> showLocations()
            else -> loadLocations()
        }
    }

    private fun loadLocations() {
        val key = settings.storedKey()
        if (key == null) {
            showStep(Step.KEY)
            return
        }
        showLocationState(loading = true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { locationsClient.listLocations(key) }
            }
            val error = result.exceptionOrNull()
            when {
                error == null -> {
                    locations = result.getOrThrow()
                    showLocations(animate = true)
                }
                error is StripeApiException && error.httpStatus == HTTP_FORBIDDEN -> {
                    isManualLocation = true
                    showManualLocation(animate = true)
                }
                else -> {
                    Log.w(TAG, "Could not list Stripe locations", error)
                    showLocationState(animate = true, message = stripeErrorText(error), retry = true)
                }
            }
        }
    }

    private fun showLocations(animate: Boolean = false) {
        val listed = locations.orEmpty()
        binding.locationList.setOnCheckedChangeListener(null)
        binding.locationList.removeAllViews()
        // With a single location there is nothing to choose, so it is already selected.
        val preselected = listed.firstOrNull { it.id == selectedLocationId }?.id
            ?: listed.singleOrNull()?.id
        selectedLocationId = preselected
        listed.forEach { location ->
            val choice = layoutInflater.inflate(
                R.layout.item_card_location_choice,
                binding.locationList,
                false,
            ) as RadioButton
            choice.id = View.generateViewId()
            choice.tag = location.id
            choice.text = locationLabel(location)
            binding.locationList.addView(choice)
            if (location.id == preselected) binding.locationList.check(choice.id)
        }
        binding.locationList.setOnCheckedChangeListener { group, checkedId ->
            selectedLocationId = group.findViewById<RadioButton>(checkedId)?.tag as? String
            updateNextButton()
        }
        showLocationState(
            animate = animate,
            message = if (listed.isEmpty()) getString(R.string.stripe_card_location_empty) else null,
            list = listed.isNotEmpty(),
            add = true,
        )
    }

    /** Shop name with the place below it in the quieter caption colour. */
    private fun locationLabel(location: StripeLocation): CharSequence {
        if (location.place.isEmpty()) return location.displayName
        val text = SpannableString("${location.displayName}\n${location.place}")
        val start = location.displayName.length + 1
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.color_text_secondary)),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        text.setSpan(RelativeSizeSpan(CAPTION_SCALE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun showManualLocation(animate: Boolean = false) {
        binding.locationIdInput.setText(settings.storedLocationId())
        showLocationState(
            animate = animate,
            message = getString(R.string.stripe_card_location_no_permission),
            manual = true,
        )
    }

    private fun showLocationState(
        animate: Boolean = false,
        loading: Boolean = false,
        message: String? = null,
        retry: Boolean = false,
        list: Boolean = false,
        add: Boolean = false,
        manual: Boolean = false,
    ) {
        fun View.show(visible: Boolean) {
            visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.locationContainer, MaterialFadeThrough())
        }
        binding.locationLoading.show(loading)
        binding.locationMessage.show(message != null)
        binding.locationMessage.text = message
        binding.locationRetryButton.show(retry)
        binding.locationList.show(list)
        binding.addLocationButton.show(add)
        binding.locationManual.show(manual)
        updateNextButton()
    }

    private fun submitLocation() {
        if (isManualLocation) {
            val id = binding.locationIdInput.text?.toString()?.trim().orEmpty()
            if (!StripeCardConfig.isValidLocationId(id)) {
                binding.locationIdLayout.error = getString(R.string.stripe_card_invalid_location)
                return
            }
            settings.saveLocation(id, name = null)
        } else {
            val location = locations?.firstOrNull { it.id == selectedLocationId } ?: return
            settings.saveLocation(location.id, location.displayName)
        }
        done()
    }

    // endregion

    // region Add a location

    private fun setupAddLocationStep() {
        val locale = Locale.getDefault()
        Locale.getISOCountries()
            .map { code -> Locale("", code).getDisplayCountry(locale) to code }
            .filter { (name, _) -> name.isNotBlank() }
            .sortedBy { (name, _) -> name }
            .forEach { (name, code) -> countryCodesByName[name] = code }
        binding.countryInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, countryCodesByName.keys.toList())
        )
        countryCodesByName.entries.firstOrNull { it.value == locale.country }?.let {
            binding.countryInput.setText(it.key, false)
        }
        requiredFields().forEach { (layout, input) ->
            input.doAfterTextChanged { layout.error = null }
        }
        binding.postalInput.setOnEditorActionListener(submitOnAction)
    }

    private fun requiredFields(): List<Pair<TextInputLayout, EditText>> = listOf(
        binding.shopNameLayout to binding.shopNameInput,
        binding.countryLayout to binding.countryInput,
        binding.streetLayout to binding.streetInput,
        binding.cityLayout to binding.cityInput,
        binding.postalLayout to binding.postalInput,
    )

    private fun submitNewLocation() {
        val key = settings.storedKey() ?: return showStep(Step.KEY)
        var complete = true
        requiredFields().forEach { (layout, input) ->
            val missing = input.text.isNullOrBlank()
            layout.error = if (missing) getString(R.string.stripe_card_add_location_required) else null
            if (missing) complete = false
        }
        val country = countryCodesByName[binding.countryInput.text?.toString().orEmpty()]
        if (country == null) {
            binding.countryLayout.error = getString(R.string.stripe_card_add_location_required)
            complete = false
        }
        if (!complete || country == null) return

        val name = binding.shopNameInput.text.toString()
        val address = StripeLocationAddress(
            line1 = binding.streetInput.text.toString(),
            line2 = binding.unitInput.text?.toString(),
            city = binding.cityInput.text.toString(),
            state = binding.stateInput.text?.toString(),
            postalCode = binding.postalInput.text.toString(),
            country = country,
        )
        binding.addLocationError.visibility = View.GONE
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { locationsClient.createLocation(key, name, address) }
            }
            setBusy(false)
            result.onSuccess { location ->
                locations = locations.orEmpty() + location
                settings.saveLocation(location.id, location.displayName)
                done()
            }.onFailure { error ->
                Log.w(TAG, "Could not create the Stripe location", error)
                binding.addLocationError.text =
                    if (error is StripeApiException && error.httpStatus == HTTP_FORBIDDEN) {
                        getString(R.string.stripe_card_add_location_no_permission)
                    } else {
                        stripeErrorText(error)
                    }
                binding.addLocationError.visibility = View.VISIBLE
            }
        }
    }

    // endregion

    private fun paste(target: EditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.stripe_card_setup_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        target.setText(text)
        target.setSelection(target.text.length)
        ViewCompat.performHapticFeedback(target, HapticFeedbackConstantsCompat.CONFIRM)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    companion object {
        private const val TAG = "StripeCardSetup"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_THEN_CHECK = "then_check"
        private const val STRIPE_API_KEYS_URL = "https://dashboard.stripe.com/apikeys"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val STEP_ANIMATION_MS = 240L
        private const val CAPTION_SCALE = 0.85f

        /** First-time setup: key, then location, then the setup check. */
        fun setupIntent(context: Context): Intent =
            Intent(context, StripeCardSetupActivity::class.java).putExtra(EXTRA_MODE, Mode.SETUP.name)

        /** @param thenCheck false when the caller runs the check itself afterwards. */
        fun editKeyIntent(context: Context, thenCheck: Boolean): Intent =
            Intent(context, StripeCardSetupActivity::class.java)
                .putExtra(EXTRA_MODE, Mode.EDIT_KEY.name)
                .putExtra(EXTRA_THEN_CHECK, thenCheck)

        /** @param thenCheck as [editKeyIntent]. */
        fun editLocationIntent(context: Context, thenCheck: Boolean): Intent =
            Intent(context, StripeCardSetupActivity::class.java)
                .putExtra(EXTRA_MODE, Mode.EDIT_LOCATION.name)
                .putExtra(EXTRA_THEN_CHECK, thenCheck)
    }
}
