package com.electricdreams.numo.feature.reporting

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.electricdreams.numo.BuildConfig
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SevereErrorReportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    internal var reportPublisher = IssueReportPublisher(OkHttpIssueReportRelayTransport())

    override suspend fun doWork(): Result {
        val store = SevereErrorReportStore(applicationContext)
        if (!SevereErrorReportingPreferences.isEnabled(applicationContext)) {
            store.clear()
            return Result.success()
        }
        val pending = store.read() ?: return Result.success()
        if (SevereErrorReportRetention.isExpired(pending.occurredAtEpochMillis)) {
            store.clearIfToken(pending.localToken)
            return Result.success()
        }

        val configuration = IssueReportConfiguration.fromBuildConfig()
        val validatedConfiguration = configuration.validate().getOrElse {
            return Result.success()
        }
        val existingPayload = pending.payloadJson
        val existingPrivateKey = pending.privateKeyHex
        val submission = if (existingPayload != null && existingPrivateKey != null) {
            IssueReportSubmission(existingPayload, existingPrivateKey)
        } else {
            val payload = AutomaticSevereErrorPayload.from(pending)
            IssueReportBuilder().buildPayload(Gson().toJson(payload)).getOrElse {
                return finishFailedAttempt(store, pending)
            }.also { constructed ->
                store.write(
                    pending.copy(
                        payloadJson = constructed.payloadJson,
                        privateKeyHex = constructed.privateKeyHex
                    )
                )
            }
        }
        val publishResult = reportPublisher.publish(
            submission,
            validatedConfiguration
        )
        return if (publishResult.accepted) {
            store.clearIfToken(pending.localToken)
            Result.success()
        } else {
            finishFailedAttempt(store, pending)
        }
    }

    private fun finishFailedAttempt(
        store: SevereErrorReportStore,
        pending: PendingSevereErrorReport
    ): Result = if (runAttemptCount >= MAX_RETRY_ATTEMPTS - 1) {
        store.clearIfToken(pending.localToken)
        Result.failure()
    } else {
        Result.retry()
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}

internal object SevereErrorReportRetention {
    private val maxReportAgeMillis = TimeUnit.DAYS.toMillis(7)

    fun isExpired(
        occurredAtEpochMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = nowMillis - occurredAtEpochMillis > maxReportAgeMillis
}

object SevereErrorReportScheduler {
    private const val UNIQUE_WORK_NAME = "automatic-severe-error-report"

    fun scheduleIfPending(context: Context) {
        if (!SevereErrorReportingPreferences.isEnabled(context)) return
        if (SevereErrorReportStore(context).read() == null) return
        val request = OneTimeWorkRequestBuilder<SevereErrorReportWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}

data class AutomaticSevereErrorPayload(
    val schema: String,
    val schemaVersion: Int,
    val submittedAt: String,
    val description: String,
    val reportType: String,
    val app: IssueReportPayload.AppContext,
    val device: IssueReportPayload.DeviceContext,
    val error: ErrorContext
) {
    data class ErrorContext(
        val occurredAt: String,
        val exceptionTypes: List<String>,
        val appFrames: List<SanitizedStackFrame>
    )

    companion object {
        fun from(
            pending: PendingSevereErrorReport,
            nowMillis: Long = System.currentTimeMillis()
        ): AutomaticSevereErrorPayload {
            val manufacturer = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.trim()
            val deviceModel = if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
            return AutomaticSevereErrorPayload(
                schema = IssueReportPayload.SCHEMA,
                schemaVersion = IssueReportPayload.SCHEMA_VERSION,
                submittedAt = formatUtc(nowMillis),
                description = "The application terminated unexpectedly.",
                reportType = "automatic-severe-error",
                app = IssueReportPayload.AppContext(
                    version = BuildConfig.VERSION_NAME,
                    build = BuildConfig.VERSION_CODE.toString()
                ),
                device = IssueReportPayload.DeviceContext(
                    platform = "Android",
                    osVersion = Build.VERSION.RELEASE,
                    model = deviceModel
                ),
                error = ErrorContext(
                    occurredAt = formatUtc(pending.occurredAtEpochMillis),
                    exceptionTypes = pending.exceptionTypes,
                    appFrames = pending.appFrames
                )
            )
        }

        private fun formatUtc(timestamp: Long): String = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))
    }
}
