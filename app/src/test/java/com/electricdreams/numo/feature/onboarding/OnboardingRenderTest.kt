package com.electricdreams.numo.feature.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Skia snapshots complement physical-device motion review; they are not device captures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        context.getSharedPreferences("OnboardingPrefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanup() {
        RuntimeEnvironment.setFontScale(1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test
    fun `checkout snapshots have distinct entered amounts and settled payment states`() {
        val screens = CheckoutPreviewScreens(context)
        screens.keypad.zipWithNext().forEach { (before, after) ->
            assertFalse("Each entered digit must change the checkout screen", before.sameAs(after))
        }
        assertFalse(screens.waiting.sameAs(screens.received))
        assertTrue(screens.chargeBounds.width() > 0)
        assertTrue("Main amount must be visible: ${screens.amountBounds}",
            screens.amountBounds.width() > 100 && screens.amountBounds.height() > 40)
        val amount = screens.amountBounds
        val bitmap = screens.keypad.last()
        val inkPixels = (amount.top.toInt() until amount.bottom.toInt()).sumOf { y ->
            (amount.left.toInt() until amount.right.toInt()).count { x ->
                android.graphics.Color.red(bitmap.getPixel(x, y)) < 100
            }
        }
        assertTrue("Keypad amount must contain rendered text; bounds=$amount, ink=$inkPixels", inkPixels > 100)
        assertTrue(screens.keyBounds.values.all { it.height() > 0 && it.width() > 0 })
        save(screens.keypad.last(), "checkout-keypad")
        save(screens.waiting, "checkout-waiting")
        save(screens.received, "checkout-received")
    }

    @Test
    fun `all scenes render and the primary action remains visible at normal and large text`() {
        for (fontScale in listOf(1f, 1.6f)) {
            RuntimeEnvironment.setFontScale(fontScale)
            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val tour = activity.findViewById<OnboardingTourView>(R.id.welcome_tour)
                    val root = activity.findViewById<View>(android.R.id.content)
                    for (page in 0..3) {
                        tour.restorePlaybackState(Bundle().apply { putInt("tour_page", page) })
                        layout(root)
                        val action = activity.findViewById<View>(R.id.accept_button)
                        val visible = Rect()
                        assertTrue(action.getGlobalVisibleRect(visible))
                        assertTrue("Get started must remain fully visible", visible.height() >= action.height)
                        save(draw(root), "page-$page-font-$fontScale")
                    }
                    if (fontScale == 1f) {
                        tour.restorePlaybackState(Bundle().apply { putInt("tour_page", 1) })
                        layout(root)
                        val scene = descendants(root).filterIsInstance<OnboardingSceneView>()
                            .first { it.page == 1 }
                        for (time in listOf(2200L, 3500L, 4180L, 4900L, 5950L, 7100L)) {
                            scene.timeMillis = time
                            save(draw(root), "tap-$time")
                        }
                    }
                }
            }
        }
    }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 411, 840)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun draw(view: View): Bitmap = Bitmap.createBitmap(
        view.width, view.height, Bitmap.Config.ARGB_8888,
    ).also { view.draw(Canvas(it)) }

    private fun save(bitmap: Bitmap, name: String) {
        val file = File("build/onboarding-previews/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
