package com.electricdreams.numo.feature.reporting

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityIssueReportBinding
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.launch

class IssueReportActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIssueReportBinding
    private val viewModel: IssueReportViewModel by viewModels()
    private val configuration by lazy(IssueReportConfiguration::fromBuildConfig)

    private val deviceModel: String by lazy {
        val manufacturer = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.trim()
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIssueReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        applyBottomInsets()
        bindDeviceContext()
        bindAutomaticReportingPreference()
        bindActions()
        observeState()

        if (configuration.validate().isFailure) {
            viewModel.markUnavailable()
        }
    }

    private fun applyBottomInsets() {
        val initialBottomPadding = binding.actionContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.actionContainer) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialBottomPadding + bottom
            )
            insets
        }
    }

    private fun bindDeviceContext() {
        binding.platformValue.text = getString(
            R.string.issue_report_platform_value,
            PLATFORM
        )
        binding.osVersionValue.text = getString(
            R.string.issue_report_os_value,
            Build.VERSION.RELEASE
        )
        binding.deviceModelValue.text = getString(
            R.string.issue_report_device_value,
            deviceModel
        )
        binding.appVersionValue.text = getString(
            R.string.issue_report_app_version_value,
            BuildConfig.VERSION_NAME
        )
        binding.appBuildValue.text = getString(
            R.string.issue_report_app_build_value,
            BuildConfig.VERSION_CODE.toString()
        )
    }

    private fun bindActions() {
        binding.topBar.onNavClick { finish() }
        binding.descriptionInput.doAfterTextChanged { editable ->
            val description = editable?.toString().orEmpty()
            binding.descriptionCount.text = getString(
                R.string.issue_report_description_count,
                IssueReportValidator.utf8Size(description)
            )
            viewModel.onDescriptionChanged(description)
        }
        binding.sendButton.setOnClickListener {
            viewModel.submit(currentInput(), configuration)
        }
    }

    private fun bindAutomaticReportingPreference() {
        binding.automaticReportsSwitch.isChecked =
            SevereErrorReportingPreferences.isEnabled(this)
        binding.automaticReportsSwitch.setOnCheckedChangeListener { _, enabled ->
            SevereErrorReportingPreferences.setEnabled(this, enabled)
            if (enabled) {
                SevereErrorReportScheduler.scheduleIfPending(this)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: IssueReportUiState) {
        if (binding.descriptionInput.text?.toString() != state.description) {
            binding.descriptionInput.setText(state.description)
            binding.descriptionInput.setSelection(state.description.length)
        }
        binding.descriptionLayout.error = validationMessage(state.validationError)

        val submitting = state.status == IssueReportUiState.Status.SUBMITTING
        binding.descriptionInput.isEnabled = !submitting &&
            state.status != IssueReportUiState.Status.UNAVAILABLE
        binding.sendButton.isEnabled = !submitting &&
            state.status != IssueReportUiState.Status.UNAVAILABLE
        binding.progress.visibility = if (submitting) View.VISIBLE else View.GONE
        binding.sendButton.text = if (state.status == IssueReportUiState.Status.FAILED) {
            getString(R.string.issue_report_retry)
        } else {
            getString(R.string.issue_report_send)
        }

        val status = when (state.status) {
            IssueReportUiState.Status.IDLE -> null
            IssueReportUiState.Status.SUBMITTING -> R.string.issue_report_sending
            IssueReportUiState.Status.SENT -> R.string.issue_report_sent
            IssueReportUiState.Status.FAILED -> R.string.issue_report_failed
            IssueReportUiState.Status.UNAVAILABLE -> R.string.issue_report_unavailable
        }
        binding.statusText.visibility = if (status == null) View.GONE else View.VISIBLE
        status?.let(binding.statusText::setText)
        binding.statusText.setTextColor(
            ContextCompat.getColor(
                this,
                when (state.status) {
                    IssueReportUiState.Status.FAILED,
                    IssueReportUiState.Status.UNAVAILABLE -> R.color.color_error
                    IssueReportUiState.Status.SENT -> R.color.color_success_green
                    else -> R.color.color_text_secondary
                }
            )
        )
    }

    private fun currentInput(): IssueReportInput = IssueReportInput(
        description = binding.descriptionInput.text?.toString().orEmpty(),
        appVersion = BuildConfig.VERSION_NAME,
        appBuild = BuildConfig.VERSION_CODE.toString(),
        platform = PLATFORM,
        osVersion = Build.VERSION.RELEASE,
        deviceModel = deviceModel
    )

    private fun validationMessage(error: IssueReportValidationResult.Invalid?): String? {
        if (error == null) return null
        return when {
            error.field == IssueReportValidationResult.Field.DESCRIPTION &&
                error.reason == IssueReportValidationResult.Reason.REQUIRED -> {
                getString(R.string.issue_report_required)
            }
            error.field == IssueReportValidationResult.Field.DESCRIPTION &&
                error.reason == IssueReportValidationResult.Reason.TOO_LARGE -> {
                getString(R.string.issue_report_too_large)
            }
            else -> getString(R.string.issue_report_invalid)
        }
    }

    companion object {
        private const val PLATFORM = "Android"
    }
}
