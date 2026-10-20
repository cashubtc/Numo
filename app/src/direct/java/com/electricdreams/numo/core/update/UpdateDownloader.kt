package com.electricdreams.numo.core.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Resumes an immutable, signed artifact. Partial files survive process death. */
class UpdateDownloader(private val client: OkHttpClient, private val directory: File) {
    fun download(update: UpdateManifest, progress: (Int) -> Unit = {}): File {
        check(directory.isDirectory || directory.mkdirs())
        val ready = File(directory, "${update.sha256}.apk")
        if (ready.exists() && verify(ready, update)) return ready
        ready.delete()
        val partial = File(directory, "${update.sha256}.part")
        if (partial.length() > update.size) partial.delete()
        if (partial.length() == update.size) {
            if (verify(partial, update)) {
                check(partial.renameTo(ready))
                return ready
            }
            partial.delete()
        }
        val offset = partial.length()
        val request = Request.Builder().url(update.apkUrl)
            .header("Accept-Encoding", "identity")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        client.newCall(request).execute().use { response ->
            val append = response.code == 206 && offset > 0
            if (append) {
                val expected = "bytes $offset-${update.size - 1}/${update.size}"
                if (response.header("Content-Range") != expected) {
                    throw IOException("Unexpected download range")
                }
            } else if (response.code != 200) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty download")
            var received = if (append) offset else 0L
            if (body.contentLength() >= 0 && body.contentLength() != update.size - received) {
                throw IOException("Unexpected download size")
            }
            FileOutputStream(partial, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        received += count
                        if (received > update.size) throw IOException("Download exceeds signed size")
                        output.write(buffer, 0, count)
                        progress((received * 100 / update.size).toInt())
                    }
                }
                output.fd.sync()
            }
        }
        if (!verify(partial, update)) {
            // Keep a short download for retry, but never retry a complete corrupt artifact.
            if (partial.length() >= update.size) partial.delete()
            throw IOException("Incomplete download or checksum mismatch")
        }
        check(partial.renameTo(ready))
        return ready
    }

    fun verify(file: File, update: UpdateManifest): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
}
