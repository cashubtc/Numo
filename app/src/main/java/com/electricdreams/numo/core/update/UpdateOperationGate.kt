package com.electricdreams.numo.core.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Serializes the decision to install against the start of money-moving operations. */
class UpdateOperationGate {
    private val monitor = Any()
    private var operations = 0
    private val installing = MutableStateFlow(false)

    fun tryBeginOperation(): AutoCloseable? = synchronized(monitor) {
        if (installing.value) return null
        operations++
        var closed = false
        AutoCloseable {
            synchronized(monitor) {
                if (!closed) {
                    closed = true
                    operations--
                }
            }
        }
    }

    suspend fun <T> withOperation(block: suspend () -> T): T {
        var lease: AutoCloseable? = null
        while (lease == null) {
            installing.first { !it }
            lease = tryBeginOperation()
        }
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    /** Never waits while holding the gate: existing operations may start child operations. */
    fun tryBeginInstall(): Boolean = synchronized(monitor) {
        if (operations != 0 || installing.value) return false
        installing.value = true
        true
    }

    fun finishInstall() = synchronized(monitor) {
        installing.value = false
    }

    companion object {
        @JvmField
        val shared = UpdateOperationGate()
    }
}
