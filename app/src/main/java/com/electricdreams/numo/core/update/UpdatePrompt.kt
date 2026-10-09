package com.electricdreams.numo.core.update

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.electricdreams.numo.R
import com.electricdreams.numo.feature.settings.AppUpdateActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Non-blocking prompt, shown only on the POS home screen and once per activity instance. */
fun AppCompatActivity.observeAppUpdates() {
    val controller = UpdateController.getInstance(this)
    lifecycleScope.launch {
        var shown: UpdatePhase? = null
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.check()
            controller.state.collect { state ->
                if (state.phase in listOf(UpdatePhase.AVAILABLE, UpdatePhase.READY) &&
                    state.phase != shown) {
                    shown = state.phase
                    val root = findViewById<View>(android.R.id.content)
                    Snackbar.make(root, if (state.phase == UpdatePhase.READY)
                        R.string.update_ready else R.string.update_available, Snackbar.LENGTH_LONG)
                        .setAction(R.string.update_view) {
                            startActivity(Intent(this@observeAppUpdates, AppUpdateActivity::class.java))
                        }.show()
                }
            }
        }
    }
}
