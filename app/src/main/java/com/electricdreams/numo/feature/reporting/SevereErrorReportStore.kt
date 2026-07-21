package com.electricdreams.numo.feature.reporting

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File

class SevereErrorReportStore(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    @Synchronized
    fun write(report: PendingSevereErrorReport): Boolean {
        var output: java.io.FileOutputStream? = null
        return try {
            output = file.startWrite()
            output.write(gson.toJson(report).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            true
        } catch (_: Throwable) {
            output?.let(file::failWrite)
            false
        }
    }

    @Synchronized
    fun read(): PendingSevereErrorReport? {
        if (!file.baseFile.exists()) return null
        return try {
            val bytes = file.readFully()
            if (bytes.size > MAX_FILE_BYTES) {
                clear()
                null
            } else {
                val report = gson.fromJson(
                    String(bytes, Charsets.UTF_8),
                    PendingSevereErrorReport::class.java
                )
                if (isValid(report)) report else {
                    clear()
                    null
                }
            }
        } catch (_: Throwable) {
            clear()
            null
        }
    }

    @Synchronized
    fun clearIfToken(localToken: String) {
        if (read()?.localToken == localToken) {
            clear()
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun isValid(report: PendingSevereErrorReport?): Boolean {
        if (report == null || report.localToken.isBlank() || report.occurredAtEpochMillis <= 0) {
            return false
        }
        if (report.exceptionTypes.size > 4 || report.appFrames.size > 12) return false
        if (report.exceptionTypes.any { it.length > 160 }) return false
        if (report.appFrames.any {
                it.className.length > 160 || it.methodName.length > 160 ||
                    !it.className.startsWith("com.electricdreams.numo.")
            }
        ) return false
        val serialized = report.serializedEvent
        val eventId = report.eventId
        if ((serialized == null) != (eventId == null)) return false
        if (serialized != null && serialized.toByteArray(Charsets.UTF_8).size > 65_536) return false
        if (eventId != null && !eventId.matches(Regex("^[0-9a-f]{64}$"))) return false
        return true
    }

    companion object {
        private const val FILE_NAME = "pending_severe_error.json"
        private const val MAX_FILE_BYTES = 81_920
    }
}
