package com.electricdreams.numo.feature.insights

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemThumbnailLoaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `large photos load as square thumbnails and repeated requests reuse the bitmap`() = runTest {
        val photo = temporaryFolder.newFile("item.png")
        writePhoto(photo, Color.RED)

        val thumbnail = requireNotNull(ItemThumbnailLoader.load(photo.path, 40))
        val cached = ItemThumbnailLoader.load(photo.path, 40)

        assertEquals(40, thumbnail.width)
        assertEquals(40, thumbnail.height)
        assertEquals(Color.RED, thumbnail.getPixel(20, 20))
        assertSame(thumbnail, cached)
    }

    @Test
    fun `a replaced photo at the same path gets a new thumbnail`() = runTest {
        val photo = temporaryFolder.newFile("item.png")
        writePhoto(photo, Color.RED)
        val oldThumbnail = requireNotNull(ItemThumbnailLoader.load(photo.path, 40))
        val modified = photo.lastModified()
        writePhoto(photo, Color.BLUE)
        assertTrue(photo.setLastModified(modified + 2_000))

        val thumbnail = requireNotNull(ItemThumbnailLoader.load(photo.path, 40))

        assertNotSame(oldThumbnail, thumbnail)
        assertEquals(Color.BLUE, thumbnail.getPixel(20, 20))
    }

    @Test
    fun `missing and corrupt photos return no thumbnail`() = runTest {
        val corrupt = temporaryFolder.newFile("corrupt.png").apply { writeText("invalid image") }

        assertNull(ItemThumbnailLoader.load(File(temporaryFolder.root, "missing.png").path, 40))
        assertNull(ItemThumbnailLoader.load(corrupt.path, 40))
    }

    private fun writePhoto(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
