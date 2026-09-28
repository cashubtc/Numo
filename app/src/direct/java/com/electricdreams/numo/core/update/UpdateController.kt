package com.electricdreams.numo.core.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-scoped downloads continue when the settings screen closes. */
class UpdateController internal constructor(
    private val context: Context,
    repositoryFactory: () -> UpdateRepository = { UpdateRepository(context) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val installer: PackageInstaller = context.packageManager.packageInstaller,
    private val gate: UpdateOperationGate = UpdateOperationGate.shared,
) {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val repository by lazy(repositoryFactory)
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private val mutableConfirmation = MutableStateFlow<Intent?>(null)
    val confirmation = mutableConfirmation.asStateFlow()
    private var candidate: UpdateManifest? = null
    private var work: Job? = null
    @Volatile private var sessionId = preferences.getInt("session_id", -1)
    private var initialized = false
    private var requestedCheck: Boolean? = null
    private var preparingInstall = false
    private var cancelRequested = false

    init {
        // A previous process cannot retain an operation lease. Abandon its outstanding
        // install before accepting new payments; the user can safely retry from settings.
        if (sessionId != -1) {
            gate.tryBeginInstall()
            cancelInstall()
        }
        work = scope.launch {
            try {
                candidate = withContext(ioDispatcher) { repository.cached() }
                if (candidate != null || sessionId != -1) showCandidate()
                val update = candidate
                if (update != null && preferences.getString("download_hash", null) == update.sha256 &&
                    state.value.phase == UpdatePhase.AVAILABLE) {
                    downloadCandidate(update)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not restore update state", e)
            } finally {
                initialized = true
                work = null
                requestedCheck?.let { force ->
                    requestedCheck = null
                    check(force)
                }
            }
        }
    }

    fun check(force: Boolean = false) {
        if (!initialized) {
            requestedCheck = force || requestedCheck == true
            return
        }
        if (work?.isActive == true || state.value.phase == UpdatePhase.INSTALLING) return
        if (!force && BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        val elapsed = now - preferences.getLong("last_check", 0)
        if (!force && elapsed in 0 until CHECK_INTERVAL_MS) return
        preferences.edit().putLong("last_check", now).apply()
        work = scope.launch {
            val previous = state.value
            mutableState.value = previous.copy(phase = UpdatePhase.CHECKING, message = null)
            try {
                candidate = withContext(ioDispatcher) { repository.check() }
                showCandidate()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Update check failed", e)
                mutableState.value = previous.copy(
                    phase = if (candidate == null) UpdatePhase.ERROR else previous.phase,
                    message = R.string.update_check_failed,
                )
            }
        }
    }

    fun download(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val update = candidate ?: return
        if (work?.isActive == true || state.value.phase == UpdatePhase.INSTALLING) return
        work = scope.launch { downloadCandidate(update) }
    }

    private suspend fun downloadCandidate(update: UpdateManifest) {
        preferences.edit().putString("download_hash", update.sha256).apply()
        mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, update.versionName,
            releaseNotes = update.releaseNotes)
        try {
            withContext(ioDispatcher) {
                repository.download(update) { percent ->
                    mutableState.value = mutableState.value.copy(progress = percent)
                }
            }
            showCandidate()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Update download failed", e)
            mutableState.value = state.value.copy(phase = UpdatePhase.AVAILABLE,
                message = R.string.update_download_failed)
        }
    }

    private suspend fun showCandidate() {
        if (sessionId != -1) {
            mutableState.value = state.value.copy(phase = UpdatePhase.INSTALLING, canCancelInstall = true)
            return
        }
        val update = candidate
        mutableState.value = if (update == null) {
            UpdateState(UpdatePhase.CURRENT)
        } else {
            val ready = withContext(ioDispatcher) { repository.readyFile(update) != null }
            UpdateState(if (ready) UpdatePhase.READY else UpdatePhase.AVAILABLE,
                update.versionName, releaseNotes = update.releaseNotes)
        }
    }

    fun install(activity: Activity) {
        val update = candidate ?: return
        if (work?.isActive == true || state.value.phase != UpdatePhase.READY) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")))
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not open installer settings", e)
                        mutableState.value = state.value.copy(message = R.string.update_install_failed)
                    }
                }.show()
            return
        }
        if (!gate.tryBeginInstall()) {
            mutableState.value = state.value.copy(message = R.string.update_payment_busy)
            return
        }
        mutableState.value = state.value.copy(phase = UpdatePhase.INSTALLING, message = null)
        preparingInstall = true
        cancelRequested = false
        work = scope.launch {
            try {
                withContext(ioDispatcher) {
                    val apk = repository.verifyForInstall(update)
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(context.packageName)
                    params.setSize(apk.length())
                    if (Build.VERSION.SDK_INT >= 31) {
                        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    sessionId = installer.createSession(params)
                    kotlin.check(preferences.edit().putInt("session_id", sessionId).commit())
                    installer.openSession(sessionId).use { session ->
                        session.openWrite("base.apk", 0, apk.length()).use { output ->
                            apk.inputStream().use { it.copyTo(output) }
                            session.fsync(output)
                        }
                        val intent = Intent(context, UpdateInstallReceiver::class.java)
                            .setAction("${context.packageName}.UPDATE_RESULT.$sessionId")
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                        val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                        session.commit(callback.intentSender)
                    }
                }
                preparingInstall = false
                if (cancelRequested) {
                    cancelInstall()
                } else if (state.value.phase == UpdatePhase.INSTALLING) {
                    mutableState.value = state.value.copy(canCancelInstall = true)
                }
            } catch (e: Exception) {
                preparingInstall = false
                if (e is CancellationException) throw e
                Log.e(TAG, "Could not install update", e)
                if (sessionId == -1) finishInstallState() else cancelInstall()
                mutableState.value = state.value.copy(message = R.string.update_install_failed)
            }
        }
    }

    fun onInstallStatus(intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != sessionId) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    cancelInstall()
                } else {
                    mutableConfirmation.value = confirmation
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                preferences.edit().remove("download_hash").remove("session_id").apply()
                sessionId = -1
                gate.finishInstall()
                mutableState.value = UpdateState(UpdatePhase.CURRENT)
            }
            else -> {
                Log.w(TAG, "Installer returned status ${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)}")
                finishInstallState()
                mutableState.value = state.value.copy(message = R.string.update_install_failed)
            }
        }
    }

    fun takeConfirmation(): Intent? = mutableConfirmation.value.also {
        mutableConfirmation.value = null
    }

    fun onInstallerReturned(resultCode: Int) {
        if (resultCode == Activity.RESULT_CANCELED && sessionId != -1) cancelInstall()
    }

    fun onDownloadConsentResult(resultCode: Int) = Unit

    fun cancelInstall() {
        if (preparingInstall) {
            cancelRequested = true
            return
        }
        if (sessionId == -1) return
        if (sessionId != -1) {
            try {
                installer.abandonSession(sessionId)
            } catch (e: Exception) {
                Log.w(TAG, "Could not abandon completed update session", e)
                // Do not resume payments if Android might still be installing this session.
                val stillInstalling = try {
                    installer.getSessionInfo(sessionId) != null
                } catch (queryError: Exception) {
                    Log.w(TAG, "Could not determine installer state", queryError)
                    true
                }
                if (stillInstalling) {
                    mutableState.value = state.value.copy(phase = UpdatePhase.INSTALLING,
                        canCancelInstall = true, message = R.string.update_install_failed)
                    return
                }
            }
        }
        finishInstallState()
    }

    private fun finishInstallState() {
        sessionId = -1
        preferences.edit().remove("session_id").apply()
        mutableConfirmation.value = null
        gate.finishInstall()
        mutableState.value = state.value.copy(phase = UpdatePhase.READY, canCancelInstall = false)
    }

    companion object {
        private const val TAG = "UpdateController"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        @Volatile private var instance: UpdateController? = null
        fun getInstance(context: Context): UpdateController = instance ?: synchronized(this) {
            instance ?: UpdateController(context.applicationContext).also { instance = it }
        }
    }
}
