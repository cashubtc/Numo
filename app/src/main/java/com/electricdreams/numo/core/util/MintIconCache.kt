package com.electricdreams.numo.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manages downloading and caching of mint icons.
 */
object MintIconCache {
    private const val TAG = "MintIconCache"
    private const val CACHE_DIR_NAME = "mint_icons"
    
    private lateinit var cacheDir: File
    private var initialized = false
    
    /**
     * Initialize the icon cache with the application context.
     */
    fun initialize(context: Context) {
        if (!initialized) {
            cacheDir = File(context.filesDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            initialized = true
            Log.d(TAG, "Initialized mint icon cache at: ${cacheDir.absolutePath}")
        }
    }
    
    /**
     * Get the cached icon file for a mint URL.
     * Returns null if the icon is not cached.
     */
    fun getCachedIconFile(mintUrl: String): File? {
        if (!initialized) {
            Log.w(TAG, "Icon cache not initialized")
            return null
        }
        
        val fileName = getIconFileName(mintUrl)
        val file = File(cacheDir, fileName)
        return if (file.exists()) file else null
    }
    
    /**
     * Download and cache an icon from the given URL.
     * Returns the cached file on success, null on failure.
     */
    suspend fun downloadAndCacheIcon(mintUrl: String, iconUrl: String): File? = withContext(Dispatchers.IO) {
        if (!initialized) {
            Log.w(TAG, "Icon cache not initialized")
            return@withContext null
        }
        
        try {
            Log.d(TAG, "Downloading icon for $mintUrl from $iconUrl")
            
            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url(iconUrl)
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to download icon: HTTP ${response.code}")
                    return@withContext null
                }
                
                val bytes = response.body?.bytes()

                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Empty response when downloading icon")
                    return@withContext null
                }

                // Decode to bitmap to validate it's a valid image
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode icon as bitmap")
                    return@withContext null
                }

                // Save to cache
                val fileName = getIconFileName(mintUrl)
                val file = File(cacheDir, fileName)

                FileOutputStream(file).use { out ->
                    // Save as PNG for consistent format
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                
                bitmap.recycle()
                
                Log.d(TAG, "Successfully cached icon for $mintUrl at ${file.absolutePath}")
                return@withContext file
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/caching icon for $mintUrl: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Get or download an icon for a mint.
     * Returns the cached file if available, otherwise downloads it.
     */
    suspend fun getOrDownloadIcon(mintUrl: String, iconUrl: String): File? {
        // Check cache first
        val cachedFile = getCachedIconFile(mintUrl)
        if (cachedFile != null) {
            return cachedFile
        }
        
        // Download and cache
        return downloadAndCacheIcon(mintUrl, iconUrl)
    }
    
    /**
     * Generate a unique filename for a mint's icon based on the mint URL.
     */
    private fun getIconFileName(mintUrl: String): String {
        // Use MD5 hash of the mint URL to create a unique filename
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(mintUrl.toByteArray())
        val hexString = hash.joinToString("") { "%02x".format(it) }
        return "$hexString.png"
    }
    
    /**
     * Clear all cached icons.
     */
    fun clearCache() {
        if (!initialized) {
            Log.w(TAG, "Icon cache not initialized")
            return
        }
        
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cleared icon cache")
    }
}
