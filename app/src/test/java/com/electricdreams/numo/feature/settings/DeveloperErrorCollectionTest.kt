package com.electricdreams.numo.feature.settings

import android.content.Context
import com.electricdreams.numo.NumoApplication
import com.electricdreams.numo.core.dev.ErrorLogCollectionState
import com.electricdreams.numo.core.dev.ErrorLogCollector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Run for both debug and release to guard against accidentally gating on BuildConfig.DEBUG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NumoApplication::class)
class DeveloperErrorCollectionTest {
    @Before
    @After
    fun stopCollection() {
        DeveloperPrefs.setDeveloperModeEnabled(RuntimeEnvironment.getApplication(), false)
    }

    @Test
    fun `enabling developer mode starts collection immediately without restarting the app`() {
        val context = RuntimeEnvironment.getApplication()
        DeveloperPrefs.setDeveloperModeEnabled(context, true)
        assertTrue(DeveloperPrefs.isDeveloperModeEnabled(context))
        assertNotEquals(ErrorLogCollectionState.STOPPED, ErrorLogCollector.state.value)
        DeveloperPrefs.setDeveloperModeEnabled(context, false)
        assertEquals(ErrorLogCollectionState.STOPPED, ErrorLogCollector.state.value)
    }

    @Test
    fun `application startup resumes collection for a saved developer preference`() {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "developer_prefs", Context.MODE_PRIVATE
        ).edit().putBoolean("developer_mode_enabled", true).commit()
        RuntimeEnvironment.getApplication().onCreate()
        assertNotEquals(ErrorLogCollectionState.STOPPED, ErrorLogCollector.state.value)
    }

    @Test
    fun `application startup leaves collection stopped when developer mode is off`() {
        RuntimeEnvironment.getApplication().onCreate()
        assertEquals(ErrorLogCollectionState.STOPPED, ErrorLogCollector.state.value)
    }
}
