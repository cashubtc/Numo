package com.electricdreams.numo.feature.insights

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StackedAvatarsViewTest {

    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Numo)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `binding shows a placeholder until the thumbnail is ready`() = runTest {
        val result = CompletableDeferred<Bitmap?>()
        val avatars = StackedAvatarsView(context, null, 0) { _, _ -> result.await() }
        avatars.setItems(listOf(item("Latte", "latte.png")))
        val image = avatars.getChildAt(0) as ImageView

        runCurrent()
        assertFalse(image.drawable is RoundedBitmapDrawable)
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        result.complete(bitmap)
        runCurrent()

        assertSame(bitmap, (image.drawable as RoundedBitmapDrawable).bitmap)
        assertEquals(ImageView.ScaleType.CENTER_CROP, image.scaleType)
        assertEquals(null, image.colorFilter)
        avatars.setItems(emptyList())
    }

    @Test
    fun `rebinding cancels an old request and only displays the new item`() = runTest {
        val results = mapOf(
            "old.png" to CompletableDeferred<Bitmap?>(),
            "new.png" to CompletableDeferred<Bitmap?>(),
        )
        val jobs = mutableMapOf<String, Job>()
        val avatars = StackedAvatarsView(context, null, 0) { path, _ ->
            jobs[path] = requireNotNull(currentCoroutineContext()[Job])
            results.getValue(path).await()
        }
        avatars.setItems(listOf(item("Old item", "old.png")))
        runCurrent()
        avatars.setItems(listOf(item("New item", "new.png")))
        runCurrent()

        assertTrue(jobs.getValue("old.png").isCancelled)
        val oldBitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val newBitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        results.getValue("old.png").complete(oldBitmap)
        results.getValue("new.png").complete(newBitmap)
        runCurrent()

        val image = avatars.getChildAt(0) as ImageView
        assertEquals("New item", image.contentDescription)
        assertSame(newBitmap, (image.drawable as RoundedBitmapDrawable).bitmap)
        avatars.setItems(emptyList())
    }

    @Test
    fun `detaching cancels loading and reattaching resumes it`() = runTest {
        val jobs = mutableListOf<Job>()
        val result = CompletableDeferred<Bitmap?>()
        val avatars = StackedAvatarsView(context, null, 0) { _, _ ->
            jobs += requireNotNull(currentCoroutineContext()[Job])
            result.await()
        }
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val parent = LinearLayout(controller.get())
            controller.get().setContentView(parent)
            avatars.setItems(listOf(item("Latte", "latte.png")))
            parent.addView(avatars)
            runCurrent()

            parent.removeView(avatars)
            runCurrent()
            assertTrue(jobs.single().isCancelled)

            parent.addView(avatars)
            runCurrent()
            assertEquals(2, jobs.size)
            val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            result.complete(bitmap)
            runCurrent()

            val image = avatars.getChildAt(0) as ImageView
            assertSame(bitmap, (image.drawable as RoundedBitmapDrawable).bitmap)
        }
    }

    private fun item(name: String, path: String) = BasketItemSummary(name, path, 1)
}
