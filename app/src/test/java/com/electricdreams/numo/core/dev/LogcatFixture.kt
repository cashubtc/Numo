package com.electricdreams.numo.core.dev

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** logger_entry_v4 layout from Android's log/log_read.h, followed by priority/tag/message. */
internal fun logcatBytes(
    message: String,
    tag: String = "AutoWithdrawManager",
    pid: Int = 1234,
    tid: Int = 1235,
    seconds: Int = 1_767_225_600,
    nanos: Int = 123_456_789,
    priority: Int = 6,
    buffer: Int = 0,
    headerSize: Int = 28,
): ByteArray {
    val payload = byteArrayOf(priority.toByte()) + tag.toByteArray() + byteArrayOf(0) +
        message.toByteArray() + byteArrayOf(0)
    return ByteBuffer.allocate(headerSize + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(payload.size.toShort())
        putShort(headerSize.toShort())
        putInt(pid)
        putInt(tid)
        putInt(seconds)
        putInt(nanos)
        putInt(buffer)
        if (headerSize >= 28) putInt(10_001)
        position(headerSize)
        put(payload)
    }.array()
}
