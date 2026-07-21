package com.electricdreams.numo.feature.reporting

import java.nio.charset.StandardCharsets

data class IssueReportInput(
    val description: String,
    val appVersion: String,
    val appBuild: String,
    val platform: String,
    val osVersion: String,
    val deviceModel: String?
)

data class IssueReportPayload(
    val schema: String = SCHEMA,
    val schemaVersion: Int = SCHEMA_VERSION,
    val submittedAt: String,
    val description: String,
    val app: AppContext,
    val device: DeviceContext
) {
    data class AppContext(
        val version: String,
        val build: String
    )

    data class DeviceContext(
        val platform: String,
        val osVersion: String,
        val model: String?
    )

    companion object {
        const val SCHEMA = "enuts.issue-report"
        const val SCHEMA_VERSION = 1
    }
}

data class IssueReportSubmission(
    val serializedEvent: String,
    val eventId: String
)

sealed interface IssueReportValidationResult {
    data class Valid(val input: IssueReportInput) : IssueReportValidationResult
    data class Invalid(val field: Field, val reason: Reason) : IssueReportValidationResult

    enum class Field {
        DESCRIPTION,
        APP_VERSION,
        APP_BUILD,
        PLATFORM,
        OS_VERSION,
        DEVICE_MODEL,
        PAYLOAD
    }

    enum class Reason {
        REQUIRED,
        TOO_LARGE
    }
}

object IssueReportValidator {
    const val DESCRIPTION_MAX_BYTES = 8_000
    const val APP_VALUE_MAX_BYTES = 100
    const val DEVICE_VALUE_MAX_BYTES = 200
    const val PAYLOAD_MAX_BYTES = 16_384
    const val OUTER_EVENT_MAX_BYTES = 65_536

    fun validate(input: IssueReportInput): IssueReportValidationResult {
        val normalized = input.copy(
            description = input.description.trim(),
            appVersion = input.appVersion.trim(),
            appBuild = input.appBuild.trim(),
            platform = input.platform.trim(),
            osVersion = input.osVersion.trim(),
            deviceModel = input.deviceModel?.trim()?.takeIf(String::isNotEmpty)
        )

        required(normalized.description, IssueReportValidationResult.Field.DESCRIPTION)?.let {
            return it
        }
        bounded(
            normalized.description,
            DESCRIPTION_MAX_BYTES,
            IssueReportValidationResult.Field.DESCRIPTION
        )?.let { return it }
        required(normalized.appVersion, IssueReportValidationResult.Field.APP_VERSION)?.let {
            return it
        }
        bounded(
            normalized.appVersion,
            APP_VALUE_MAX_BYTES,
            IssueReportValidationResult.Field.APP_VERSION
        )?.let { return it }
        required(normalized.appBuild, IssueReportValidationResult.Field.APP_BUILD)?.let {
            return it
        }
        bounded(
            normalized.appBuild,
            APP_VALUE_MAX_BYTES,
            IssueReportValidationResult.Field.APP_BUILD
        )?.let { return it }
        required(normalized.platform, IssueReportValidationResult.Field.PLATFORM)?.let {
            return it
        }
        required(normalized.osVersion, IssueReportValidationResult.Field.OS_VERSION)?.let {
            return it
        }
        bounded(
            normalized.osVersion,
            DEVICE_VALUE_MAX_BYTES,
            IssueReportValidationResult.Field.OS_VERSION
        )?.let { return it }
        normalized.deviceModel?.let { model ->
            bounded(
                model,
                DEVICE_VALUE_MAX_BYTES,
                IssueReportValidationResult.Field.DEVICE_MODEL
            )?.let { return it }
        }
        return IssueReportValidationResult.Valid(normalized)
    }

    fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private fun required(
        value: String,
        field: IssueReportValidationResult.Field
    ): IssueReportValidationResult.Invalid? = if (value.isBlank()) {
        IssueReportValidationResult.Invalid(field, IssueReportValidationResult.Reason.REQUIRED)
    } else {
        null
    }

    private fun bounded(
        value: String,
        maximum: Int,
        field: IssueReportValidationResult.Field
    ): IssueReportValidationResult.Invalid? = if (utf8Size(value) > maximum) {
        IssueReportValidationResult.Invalid(field, IssueReportValidationResult.Reason.TOO_LARGE)
    } else {
        null
    }
}
