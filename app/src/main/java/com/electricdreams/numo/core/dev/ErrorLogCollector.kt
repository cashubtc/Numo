/**
 * Background collector that mirrors android.util.Log error output for this
 * process into [ErrorLogStore] by tailing logcat.
 *
 * This keeps the existing Log.e() usage intact while providing an in-app
 * history of recent errors for the Developer Settings > Error Logs screen.
 */
package com.electricdreams.numo.core.dev

import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object ErrorLogCollector {

    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // Example logcat -v time prefix: "03-15 12:34:56.789"
    private val logcatDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Start tailing logcat for this process's error-level logs.
     * Safe to call multiple times; only the first call starts the collector.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return

        workerThread = Thread {
            try {
                val pid = Process.myPid()
                val cmd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    arrayOf("logcat", "-v", "time", "--pid", pid.toString(), "*:E")
                } else {
                    arrayOf("logcat", "-v", "time", "*:E")
                }

                val process = Runtime.getRuntime().exec(cmd)
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (running.get()) {
                        line = reader.readLine() ?: break
                        parseAndStoreLine(line!!, pid)
                    }
                }
                process.destroy()
            } catch (_: Throwable) {
                // Collector is best-effort for developer diagnostics; ignore failures.
            } finally {
                running.set(false)
            }
        }.apply {
            name = "Numo-ErrorLogCollector"
            isDaemon = true
            start()
        }
    }

    /**
     * Stop tailing logcat.
     */
    fun stop() {
        running.set(false)
        workerThread?.interrupt()
        workerThread = null
    }

    private fun parseAndStoreLine(raw: String, pid: Int) {
        // For pre-N devices, filter out other pids manually.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N && !raw.contains(" $pid ")) {
            return
        }

        // Expected -v time format: "MM-dd HH:mm:ss.SSS PID T TAG: message"
        val firstSpace = raw.indexOf(' ')
        val secondSpace = if (firstSpace > 0) raw.indexOf(' ', firstSpace + 1) else -1
        if (secondSpace <= 0) return

        val timestampStr = raw.substring(0, secondSpace) // "MM-dd HH:mm:ss.SSS"
        val rest = raw.substring(secondSpace + 1)

        val colonIndex = rest.indexOf(':')
        if (colonIndex <= 0) return

        val header = rest.substring(0, colonIndex).trim()
        val message = rest.substring(colonIndex + 1).trim()

        // header typically ends with the tag, e.g. "1234 1234 E MyTag"
        val parts = header.split(Regex("\\s+"))
        val tag = parts.lastOrNull() ?: "Unknown"

        val timestamp = parseTimestamp(timestampStr) ?: Date()

        ErrorLogStore.appendError(
            tag = tag,
            message = message,
            throwable = null,
        )
    }

    private fun parseTimestamp(value: String): Date? = try {
        logcatDateFormat.parse(value)
    } catch (_: Exception) {
        null
    }
}
