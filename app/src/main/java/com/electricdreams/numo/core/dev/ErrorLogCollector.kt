package com.electricdreams.numo.core.dev

import android.os.Process
import android.util.Log
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Collects this process's errors while Developer Mode is enabled, in every build variant. */
object ErrorLogCollector {
    private val monitor by lazy {
        ErrorLogMonitor(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            pid = Process.myPid(),
            processFactory = { pid ->
                ProcessBuilder("logcat", "-B", "--pid", pid.toString(),
                    "-b", "main", "-b", "system", "-b", "crash")
                    .redirectErrorStream(true)
                    .start()
            },
            record = ErrorLogStore::record,
        )
    }

    val state get() = monitor.state

    fun start() = monitor.start()

    fun stop() = monitor.stop()
}

enum class ErrorLogCollectionState { STOPPED, STARTING, COLLECTING, RETRYING }

/** Owns the subprocess so stopping collection also unblocks a pending read. */
internal class ErrorLogMonitor(
    private val scope: CoroutineScope,
    private val pid: Int,
    private val processFactory: (Int) -> java.lang.Process,
    private val record: (ErrorLogEntry) -> Unit,
) {
    private val mutableState = MutableStateFlow(ErrorLogCollectionState.STOPPED)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var process: java.lang.Process? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        mutableState.value = ErrorLogCollectionState.STARTING
        job = scope.launch {
            var retryDelay = 1_000L
            while (isActive) {
                var child: java.lang.Process? = null
                try {
                    synchronized(this@ErrorLogMonitor) {
                        if (!isActive) return@launch
                        child = processFactory(pid)
                        process = child
                        mutableState.value = ErrorLogCollectionState.COLLECTING
                    }
                    val activeProcess = child ?: return@launch
                    val assembler = ErrorLogAssembler(record)
                    activeProcess.inputStream.buffered().use { input ->
                        val reader = LogcatRecordReader(input, pid)
                        while (isActive) {
                            val entry = reader.read() ?: throw IOException("logcat closed its output")
                            ensureActive()
                            assembler.accept(entry)
                            retryDelay = 1_000L
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(this@ErrorLogMonitor) {
                        if (isActive) {
                            mutableState.value = ErrorLogCollectionState.RETRYING
                            // Warning level avoids feeding collection failures back into the store.
                            Log.w(TAG, "Error log collection failed; retrying", e)
                        }
                    }
                } finally {
                    synchronized(this@ErrorLogMonitor) {
                        child?.destroy()
                        if (process === child) process = null
                    }
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        process?.destroy()
        process = null
        mutableState.value = ErrorLogCollectionState.STOPPED
    }

    companion object {
        private const val TAG = "ErrorLogCollector"
    }
}
