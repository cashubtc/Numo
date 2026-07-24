package com.electricdreams.numo.feature.reporting

import java.util.UUID

data class PendingSevereErrorReport(
    val localToken: String,
    val occurredAtEpochMillis: Long,
    val exceptionTypes: List<String>,
    val appFrames: List<SanitizedStackFrame>,
    val payloadJson: String? = null,
    val privateKeyHex: String? = null
)

data class SanitizedStackFrame(
    val className: String,
    val methodName: String,
    val lineNumber: Int?
)

object SevereErrorSanitizer {
    private const val APP_PACKAGE_PREFIX = "com.electricdreams.numo."
    private const val MAX_CAUSE_DEPTH = 4
    private const val MAX_FRAMES = 12
    private const val MAX_NAME_LENGTH = 160
    private val safeName = Regex("^[A-Za-z0-9_.$-]+$")

    fun sanitize(
        throwable: Throwable,
        occurredAtEpochMillis: Long = System.currentTimeMillis()
    ): PendingSevereErrorReport {
        val causes = generateSequence(throwable) { current -> current.cause }
            .take(MAX_CAUSE_DEPTH)
            .toList()
        val exceptionTypes = causes.map { current ->
            sanitizeName(current.javaClass.simpleName.ifBlank { "Throwable" })
        }
        val frames = causes.asSequence()
            .flatMap { current -> current.stackTrace.asSequence() }
            .filter { frame -> frame.className.startsWith(APP_PACKAGE_PREFIX) }
            .distinctBy { frame -> Triple(frame.className, frame.methodName, frame.lineNumber) }
            .take(MAX_FRAMES)
            .map { frame ->
                SanitizedStackFrame(
                    className = sanitizeName(frame.className),
                    methodName = sanitizeName(frame.methodName),
                    lineNumber = frame.lineNumber.takeIf { it > 0 }
                )
            }
            .toList()

        return PendingSevereErrorReport(
            localToken = UUID.randomUUID().toString(),
            occurredAtEpochMillis = occurredAtEpochMillis,
            exceptionTypes = exceptionTypes,
            appFrames = frames
        )
    }

    private fun sanitizeName(value: String): String {
        val bounded = value.take(MAX_NAME_LENGTH)
        return if (safeName.matches(bounded)) bounded else "redacted"
    }
}
