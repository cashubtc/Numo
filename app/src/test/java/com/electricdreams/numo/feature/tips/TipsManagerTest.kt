package com.electricdreams.numo.feature.tips

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TipsManagerTest {

    private lateinit var context: Context
    private lateinit var tipsManager: TipsManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("TipsSettings", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Reset singleton to avoid cross-test state
        val instanceField = TipsManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)

        tipsManager = TipsManager.getInstance(context)
    }

    @Test
    fun `default presets returned when none configured`() {
        val presets = tipsManager.getTipPresets()
        assertEquals(TipsManager.DEFAULT_PRESETS, presets)
    }

    @Test
    fun `setTipPresets enforces max size and valid range`() {
        tipsManager.setTipPresets(listOf(-5, 0, 10, 150, 20, 25))
        val presets = tipsManager.getTipPresets()
        // Implementation keeps only first MAX_PRESETS entries in range 1..100
        // For the given input this results in just [10]
        assertEquals(listOf(10), presets)
    }

    @Test
    fun `addPreset adds unique valid presets up to max`() {
        tipsManager.setTipPresets(emptyList())
        assertTrue(tipsManager.addPreset(10))
        assertTrue(tipsManager.addPreset(20))
        assertTrue(tipsManager.addPreset(5))
        assertTrue(tipsManager.addPreset(15))

        val presets = tipsManager.getTipPresets()
        assertEquals(listOf(5, 10, 15, 20), presets)
        assertFalse(tipsManager.canAddMorePresets())

        assertFalse(tipsManager.addPreset(10))
        assertFalse(tipsManager.addPreset(25))
    }

    @Test
    fun `removePreset removes existing value`() {
        tipsManager.setTipPresets(listOf(5, 10, 15))
        tipsManager.removePreset(10)
        assertEquals(listOf(5, 15), tipsManager.getTipPresets())
    }

    @Test
    fun `updatePreset updates in-range index and keeps sorted`() {
        tipsManager.setTipPresets(listOf(5, 10, 15))

        tipsManager.updatePreset(1, 7)
        assertEquals(listOf(5, 7, 15), tipsManager.getTipPresets())

        tipsManager.updatePreset(5, 20)
        assertEquals(listOf(5, 7, 15), tipsManager.getTipPresets())

        tipsManager.updatePreset(1, 0)
        assertEquals(listOf(5, 7, 15), tipsManager.getTipPresets())
    }

    @Test
    fun `resetToDefaults restores default presets`() {
        tipsManager.setTipPresets(listOf(1, 2, 3))
        tipsManager.resetToDefaults()
        assertEquals(TipsManager.DEFAULT_PRESETS, tipsManager.getTipPresets())
    }
}
