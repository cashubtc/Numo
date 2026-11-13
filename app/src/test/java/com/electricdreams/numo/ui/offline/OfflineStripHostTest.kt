package com.electricdreams.numo.ui.offline

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.electricdreams.numo.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineStripHostTest {

    private val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_HEIGHT, 0, 0))
        .build()

    @Before
    fun setUp() {
        RuntimeEnvironment.setFontScale(2f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun `offline strip reserves its wrapped height when the same window narrows and widens`() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val fixture = Fixture(controller.get())
            try {
                fixture.resize(800)
                fixture.host.render(OfflineStripView.Mode.OFFLINE, animate = false)
                fixture.resize(800)
                fixture.assertContentBelowStrip()
                val wideHeight = fixture.stripContent.height

                fixture.resize(320)
                assertTrue("Narrow text must wrap", fixture.stripContent.height > wideHeight)
                fixture.assertContentBelowStrip()

                fixture.resize(800)
                assertEquals(wideHeight, fixture.stripContent.height)
                fixture.assertContentBelowStrip()
            } finally {
                fixture.host.detach()
            }
        }
    }

    @Test
    fun `hidden strip leaves no extra inset after resizing and remeasures when shown again`() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val fixture = Fixture(controller.get())
            try {
                fixture.resize(800)
                fixture.host.render(OfflineStripView.Mode.OFFLINE, animate = false)
                fixture.resize(800)

                fixture.host.render(null, animate = false)
                fixture.resize(320)
                assertEquals(STATUS_BAR_HEIGHT, fixture.content.paddingTop)

                fixture.host.render(OfflineStripView.Mode.OFFLINE, animate = false)
                fixture.resize(320)
                fixture.assertContentBelowStrip()
            } finally {
                fixture.host.detach()
            }
        }
    }

    @Test
    fun `resizing during reveal and hide preserves progress and updates the reserved inset`() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val fixture = Fixture(controller.get())
            try {
                fixture.resize(800)
                fixture.host.render(OfflineStripView.Mode.OFFLINE, animate = true)
                val revealAnimation = fixture.animation()
                revealAnimation.currentPlayTime = 80L
                assertTrue(revealAnimation.isRunning)
                val revealProgress = fixture.strip.reveal
                assertTrue("Expected a partial reveal, was $revealProgress",
                    revealProgress > 0f && revealProgress < 1f)

                fixture.resize(320)
                assertEquals(revealProgress, fixture.strip.reveal)
                fixture.assertAnimatedInset()

                revealAnimation.end()
                fixture.resize(320)
                fixture.assertContentBelowStrip()
                fixture.host.render(null, animate = true)
                val hideAnimation = fixture.animation()
                hideAnimation.currentPlayTime = 80L
                assertTrue(hideAnimation.isRunning)
                val hideProgress = fixture.strip.reveal
                assertTrue("Expected a partial hide, was $hideProgress",
                    hideProgress > 0f && hideProgress < 1f)

                fixture.resize(800)
                assertEquals(hideProgress, fixture.strip.reveal)
                fixture.assertAnimatedInset()

                hideAnimation.end()
                fixture.resize(800)
                assertEquals(View.GONE, fixture.strip.visibility)
                assertEquals(STATUS_BAR_HEIGHT, fixture.content.paddingTop)
            } finally {
                fixture.host.detach()
            }
        }
    }

    private inner class Fixture(activity: Activity) {
        val decor = activity.window.decorView as ViewGroup
        val content = View(activity)
        val host: OfflineStripHost
        val strip: OfflineStripView
        val stripContent: View

        init {
            activity.setTheme(R.style.Theme_Numo)
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.setContentView(content)
            ViewCompat.setOnApplyWindowInsetsListener(content) { view, applied ->
                view.setPadding(0, applied.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
                applied
            }
            host = OfflineStripHost(activity) {}
            host.attach()
            strip = decor.children.filterIsInstance<OfflineStripView>().single()
            stripContent = strip.findViewById(R.id.strip_content)
        }

        fun resize(width: Int) {
            layout(width)
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
            layout(width)
        }

        fun assertContentBelowStrip() {
            assertEquals("App content must clear the strip", stripContent.height, content.paddingTop)
            assertEquals(stripContent.height - STATUS_BAR_HEIGHT, strip.contentHeight)
        }

        fun assertAnimatedInset() {
            val visibleHeight = ((stripContent.height - STATUS_BAR_HEIGHT) * strip.reveal).roundToInt()
            assertEquals(STATUS_BAR_HEIGHT + visibleHeight, content.paddingTop)
            assertEquals(stripContent.height - STATUS_BAR_HEIGHT, strip.contentHeight)
        }

        fun animation(): ValueAnimator = ReflectionHelpers.getField(host, "animator")

        private fun layout(width: Int) {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, width, 1000)
        }
    }

    companion object {
        private const val STATUS_BAR_HEIGHT = 24
    }
}
