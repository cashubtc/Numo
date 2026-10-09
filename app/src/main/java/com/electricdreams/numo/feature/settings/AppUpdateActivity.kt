package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.R
import com.electricdreams.numo.core.update.UpdateController
import com.electricdreams.numo.core.update.UpdatePhase
import com.electricdreams.numo.core.update.UpdateState
import com.electricdreams.numo.databinding.ActivityAppUpdateBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets
import kotlinx.coroutines.launch

class AppUpdateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppUpdateBinding
    private val controller by lazy { UpdateController.getInstance(this) }
    private val downloadConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { controller.onDownloadConsentResult(it.resultCode) }
    private val installConfirmation = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { controller.onInstallerReturned(it.resultCode) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)
        binding.topBar.onNavClick { finish() }
        binding.action.setOnClickListener {
            when (controller.state.value.phase) {
                UpdatePhase.AVAILABLE -> controller.download(this, downloadConsent)
                UpdatePhase.READY -> controller.install(this)
                UpdatePhase.INSTALLING -> controller.cancelInstall()
                else -> controller.check(force = true)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                controller.confirmation.collect { intent ->
                    if (intent != null) {
                        controller.takeConfirmation()?.let {
                            try {
                                installConfirmation.launch(it)
                            } catch (e: Exception) {
                                Log.e(TAG, "Could not open Android installer", e)
                                controller.cancelInstall()
                            }
                        }
                    }
                }
            }
        }
        controller.check()
    }

    private fun render(state: UpdateState) {
        binding.status.text = when (state.phase) {
            UpdatePhase.IDLE, UpdatePhase.ERROR -> getString(R.string.update_check)
            UpdatePhase.CHECKING -> getString(R.string.update_checking)
            UpdatePhase.CURRENT -> getString(R.string.update_current)
            UpdatePhase.AVAILABLE -> getString(R.string.update_available)
            UpdatePhase.DOWNLOADING -> getString(R.string.update_downloading, state.progress)
            UpdatePhase.READY -> getString(R.string.update_ready)
            UpdatePhase.INSTALLING -> getString(R.string.update_installing)
        }
        binding.version.text = getString(R.string.update_version,
            state.versionName.ifEmpty { BuildConfig.VERSION_NAME })
        binding.releaseNotes.text = state.releaseNotes
        binding.message.isVisible = state.message != null
        binding.message.text = state.message?.let { getString(it) }.orEmpty()
        binding.progress.isVisible = state.phase == UpdatePhase.DOWNLOADING
        binding.progress.progress = state.progress
        binding.action.isEnabled = state.phase !in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING) &&
            (state.phase != UpdatePhase.INSTALLING || state.canCancelInstall)
        binding.action.setText(when (state.phase) {
            UpdatePhase.AVAILABLE -> R.string.update_download
            UpdatePhase.READY -> R.string.update_install
            UpdatePhase.INSTALLING -> if (state.canCancelInstall) R.string.update_cancel_install
                else R.string.update_installing
            else -> R.string.update_check
        })
    }

    override fun onDestroy() {
        // Leaving this screen must not strand payments behind an unattended installer.
        // A configuration change keeps the session for the replacement activity.
        if (isFinishing) controller.cancelInstall()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AppUpdateActivity"
    }
}
