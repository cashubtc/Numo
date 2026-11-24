package com.electricdreams.numo.feature.settings

import android.app.Activity
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Let IO complete and its UI callback run under Robolectric's paused main looper. */
internal fun Activity.idleUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < deadline) {
        shadowOf(mainLooper).idle()
        Thread.sleep(5)
    }
    assertTrue("Timed out waiting for activity", condition())
}
