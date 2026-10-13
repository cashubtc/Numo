package com.electricdreams.numo.feature.insights

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Shared by Activity and Sales, with all file access and image decoding on IO. */
internal object ItemThumbnailLoader {

    private const val TAG = "ItemThumbnailLoader"
    private const val CACHE_BYTES = 4 * 1024 * 1024

    private data class Key(
        val path: String,
        val size: Int,
        val modified: Long,
        val length: Long,
    )

    private val cache = object : LruCache<Key, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }
    private val mutex = Mutex()

    suspend fun load(path: String, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            // Item photos can be replaced at the same path. Check their version on IO too.
            val key = Key(path, size, file.lastModified(), file.length())
            mutex.withLock {
                cache.get(key)?.let { return@withLock it }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@withLock null

                var sample = 1
                while (options.outWidth / (sample * 2) >= size &&
                    options.outHeight / (sample * 2) >= size
                ) {
                    sample *= 2
                }
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val bitmap = BitmapFactory.decodeFile(path, options) ?: return@withLock null
                ThumbnailUtils.extractThumbnail(
                    bitmap, size, size, ThumbnailUtils.OPTIONS_RECYCLE_INPUT,
                ).also { cache.put(key, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unable to load item thumbnail", e)
            null
        }
    }
}
