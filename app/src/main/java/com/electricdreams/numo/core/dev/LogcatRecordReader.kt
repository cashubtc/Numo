package com.electricdreams.numo.core.dev

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.UUID

internal data class LogcatRecord(
    val id: String,
    val timestamp: Date,
    val threadId: Int,
    val tag: String,
    val message: String,
)

/**
 * Reads logcat -B's length-prefixed logger_entry records (Android's log/log_read.h).
 * Binary records preserve message boundaries, embedded newlines and epoch timestamps.
 * Filter here: logcat's text priority filters are not applied to binary output.
 */
internal class LogcatRecordReader(input: InputStream, private val pid: Int) {
    private val input = DataInputStream(input)

    fun read(): LogcatRecord? {
        while (true) {
            val firstByte = input.read()
            if (firstByte == -1) return null
            val payloadSize = firstByte or (input.readUnsignedByte() shl 8)
            val headerSize = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            if (headerSize !in 24..64 || payloadSize !in 3..65_535) {
                throw IOException("Invalid logcat record header")
            }
            val headerBytes = ByteArray(headerSize - 4).also(input::readFully)
            val payload = ByteArray(payloadSize).also(input::readFully)
            val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val recordPid = header.int
            val threadId = header.int
            val seconds = header.int.toLong() and 0xffffffffL
            val nanos = header.int.toLong() and 0xffffffffL
            val bufferId = header.int
            if (recordPid != pid || bufferId !in TEXT_BUFFERS || payload[0].toInt() !in 6..7) {
                continue
            }
            if (nanos >= 1_000_000_000L) throw IOException("Invalid logcat timestamp")
            val tagEnd = (1 until payload.size).firstOrNull { payload[it] == 0.toByte() }
                ?: throw IOException("Missing logcat tag terminator")
            val messageEnd = if (payload.last() == 0.toByte()) payload.lastIndex else payload.size
            if (tagEnd >= messageEnd) continue
            return LogcatRecord(
                // Stable across collector retries, including sub-millisecond event identity.
                id = UUID.nameUUIDFromBytes(headerBytes + payload).toString(),
                timestamp = Date(seconds * 1_000 + nanos / 1_000_000),
                threadId = threadId,
                tag = String(payload, 1, tagEnd - 1, Charsets.UTF_8),
                message = String(payload, tagEnd + 1, messageEnd - tagEnd - 1, Charsets.UTF_8),
            )
        }
    }

    companion object {
        private val TEXT_BUFFERS = setOf(0, 3, 4) // main, system, crash
    }
}
