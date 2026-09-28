package com.electricdreams.numo.core.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.electricdreams.numo.R
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Play distribution never downloads or installs APKs outside Google Play. */
class UpdateController private constructor(context: Context) {
    private val manager = AppUpdateManagerFactory.create(context)
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    val confirmation = MutableStateFlow<Intent?>(null).asStateFlow()
    private var checking = false

    init {
        manager.registerListener { install ->
            when (install.installStatus()) {
                InstallStatus.DOWNLOADING -> {
                    val total = install.totalBytesToDownload()
                    mutableState.value = UpdateState(UpdatePhase.DOWNLOADING,
                        progress = if (total > 0) (install.bytesDownloaded() * 100 / total).toInt() else 0)
                }
                InstallStatus.DOWNLOADED -> mutableState.value = UpdateState(UpdatePhase.READY)
                InstallStatus.FAILED, InstallStatus.CANCELED -> {
                    UpdateOperationGate.shared.finishInstall()
                    mutableState.value = UpdateState(UpdatePhase.ERROR,
                        message = R.string.update_install_failed)
                }
                InstallStatus.INSTALLED -> {
                    UpdateOperationGate.shared.finishInstall()
                    mutableState.value = UpdateState(UpdatePhase.CURRENT)
                }
            }
        }
    }

    fun check(force: Boolean = false) {
        if (checking || state.value.phase == UpdatePhase.INSTALLING) return
        checking = true
        manager.appUpdateInfo.addOnSuccessListener { info ->
            checking = false
            mutableState.value = when {
                info.installStatus() == InstallStatus.DOWNLOADED -> UpdateState(UpdatePhase.READY)
                info.installStatus() in listOf(InstallStatus.DOWNLOADING, InstallStatus.PENDING) ->
                    state.value.copy(phase = UpdatePhase.DOWNLOADING)
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> UpdateState(UpdatePhase.AVAILABLE)
                else -> UpdateState(UpdatePhase.CURRENT)
            }
        }.addOnFailureListener { error ->
            checking = false
            Log.w(TAG, "Play update check failed", error)
            mutableState.value = UpdateState(UpdatePhase.ERROR, message = R.string.update_check_failed)
        }
    }

    fun download(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (state.value.phase != UpdatePhase.AVAILABLE) return
        mutableState.value = state.value.copy(phase = UpdatePhase.CHECKING)
        // AppUpdateInfo is single-use; obtain fresh information for each consent attempt.
        manager.appUpdateInfo.addOnSuccessListener { info ->
            try {
                val started = manager.startUpdateFlowForResult(info, launcher,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
                if (!started) check(true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start Play update", e)
                mutableState.value = UpdateState(UpdatePhase.ERROR, message = R.string.update_download_failed)
            }
        }.addOnFailureListener { error ->
            Log.w(TAG, "Could not request Play update", error)
            mutableState.value = UpdateState(UpdatePhase.ERROR, message = R.string.update_download_failed)
        }
    }

    fun install(activity: Activity) {
        if (state.value.phase != UpdatePhase.READY) return
        if (!UpdateOperationGate.shared.tryBeginInstall()) {
            mutableState.value = state.value.copy(message = R.string.update_payment_busy)
            return
        }
        mutableState.value = state.value.copy(phase = UpdatePhase.INSTALLING, message = null)
        manager.completeUpdate().addOnFailureListener { error ->
            Log.w(TAG, "Play installation failed", error)
            UpdateOperationGate.shared.finishInstall()
            mutableState.value = state.value.copy(phase = UpdatePhase.READY,
                message = R.string.update_install_failed)
        }
    }

    // Play owns completion after completeUpdate; it cannot be cancelled safely by Numo.
    fun cancelInstall() = Unit
    fun takeConfirmation(): Intent? = null
    fun onInstallerReturned(resultCode: Int) = Unit
    fun onDownloadConsentResult(resultCode: Int) { check(true) }

    companion object {
        private const val TAG = "PlayUpdateController"
        @Volatile private var instance: UpdateController? = null
        fun getInstance(context: Context): UpdateController = instance ?: synchronized(this) {
            instance ?: UpdateController(context.applicationContext).also { instance = it }
        }
    }
}
