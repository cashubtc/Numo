package com.electricdreams.numo.core.dev

import com.electricdreams.numo.core.data.model.ErrorLogEntry

internal object ErrorLogSummary {
    private const val MAX_STACK_LINES = 6 // Exception description and the first five frames.
    private const val MAX_MESSAGE_CHARS = 4_096
    private const val TRUNCATED = "… (stack trace truncated)"
    private val frame = Regex("^at\\s+\\S+\\(.*\\)$")
    private val omittedFrames = Regex("^\\.\\.\\. \\d+ more$")

    fun isContinuation(message: String): Boolean {
        val first = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return frame.matches(first) || first.startsWith("Caused by:") ||
            first.startsWith("Suppressed:") || omittedFrames.matches(first)
    }

    fun stackTop(stack: String?): String? {
        if (stack.isNullOrBlank()) return null
        val lines = stack.trim().lineSequence().take(MAX_STACK_LINES + 1).toList()
        return lines.take(MAX_STACK_LINES).joinToString("\n") { it.take(MAX_MESSAGE_CHARS) } +
            if (lines.size > MAX_STACK_LINES) "\n$TRUNCATED" else ""
    }

    fun fromRecord(record: LogcatRecord): ErrorLogEntry {
        val lines = record.message.trimEnd().lines()
        val firstFrame = lines.indexOfFirst { isContinuation(it) }
        val traceStart = when {
            firstFrame < 0 -> lines.size
            firstFrame == 0 -> 0
            else -> firstFrame - 1
        }
        val message = if (traceStart == 0) lines.first() else lines.take(traceStart).joinToString("\n")
        return ErrorLogEntry(
            id = record.id,
            timestamp = record.timestamp,
            tag = record.tag,
            message = message.take(MAX_MESSAGE_CHARS),
            stackTrace = stackTop(lines.drop(traceStart).joinToString("\n")),
        )
    }

    fun appendStack(existing: String?, continuation: String): String? {
        if (existing?.endsWith(TRUNCATED) == true) return existing
        return stackTop(listOfNotNull(existing, continuation).joinToString("\n"))
    }
}

/** Android may split a large throwable into multiple log records. Keep its tail out of the list. */
internal class ErrorLogAssembler(private val record: (ErrorLogEntry) -> Unit) {
    private data class Pending(val entry: ErrorLogEntry, val lastTimestamp: Long)
    private val pending = LinkedHashMap<Pair<Int, String>, Pending>()

    fun accept(raw: LogcatRecord) {
        val key = raw.threadId to raw.tag
        val previous = pending[key]
        val entry = if (previous != null && ErrorLogSummary.isContinuation(raw.message) &&
            raw.timestamp.time - previous.lastTimestamp in 0..1_000
        ) {
            previous.entry.copy(
                stackTrace = ErrorLogSummary.appendStack(previous.entry.stackTrace, raw.message),
            )
        } else {
            ErrorLogSummary.fromRecord(raw)
        }
        record(entry)
        pending.remove(key)
        pending[key] = Pending(entry, raw.timestamp.time)
        if (pending.size > 64) pending.remove(pending.keys.first())
    }
}
