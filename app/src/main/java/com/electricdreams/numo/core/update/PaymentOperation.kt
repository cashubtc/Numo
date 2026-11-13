package com.electricdreams.numo.core.update

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.electricdreams.numo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Acquire before dispatch so queued withdrawal work cannot race an install. */
fun CoroutineScope.launchPaymentOperation(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val lease = UpdateOperationGate.shared.tryBeginOperation()
    return launch(context, start) {
        if (lease == null) {
            UpdateOperationGate.shared.withOperation { coroutineScope(block) }
        } else {
            try {
                coroutineScope(block)
            } finally {
                lease.close()
            }
        }
    }.also { job ->
        // Also close when the parent was already cancelled and the body never ran.
        job.invokeOnCompletion { lease?.close() }
    }
}

suspend fun <T> withPaymentOperation(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T,
): T = UpdateOperationGate.shared.withOperation { withContext(context, block) }

/** A checkout waiting for the customer is busy even between network requests. */
fun AppCompatActivity.holdPaymentScreen(): Boolean {
    val lease = UpdateOperationGate.shared.tryBeginOperation()
    if (lease == null) {
        Toast.makeText(this, R.string.update_installing, Toast.LENGTH_SHORT).show()
        finish()
        return false
    }
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { lease.close() }
    })
    return true
}
