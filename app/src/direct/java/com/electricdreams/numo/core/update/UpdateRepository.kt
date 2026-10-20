package com.electricdreams.numo.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.io.IOException
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit

class UpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = secureClient(),
) {
    private val directory = File(context.noBackupFilesDir, "updates")
    private val metadata = AtomicFile(File(directory, "update.json"))
    private val packageManager = context.packageManager
    private val installed = packageManager.getPackageInfo(context.packageName, signatureFlags())
    private val signerBytes = signatures(installed)
    private val verifier = UpdateManifestVerifier(signerBytes.map {
        CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()).publicKey
    })
    private val downloader = UpdateDownloader(client, directory)

    fun cached(): UpdateManifest? {
        if (!metadata.baseFile.exists()) return null
        val update = verifier.verify(metadata.openRead().use {
            val bytes = it.readBytes()
            require(bytes.size <= UpdateManifestVerifier.MAX_MANIFEST_BYTES)
            bytes.toString(Charsets.UTF_8)
        })
        if (update.versionCode <= PackageInfoCompat.getLongVersionCode(installed)) {
            clearArtifacts()
            return null
        }
        return eligible(update)
    }

    fun check(): UpdateManifest? {
        val request = Request.Builder().url(MANIFEST_URL).build()
        val envelope = client.newCall(request).execute().use { response ->
            if (response.code == 404) throw IOException("No signed update manifest published yet")
            if (!response.isSuccessful) throw IOException("Update check: HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("Empty manifest")
            val buffer = Buffer()
            val limit = UpdateManifestVerifier.MAX_MANIFEST_BYTES + 1L
            while (buffer.size < limit && source.read(buffer, limit - buffer.size) != -1L) {
                // Stop at EOF even for chunked responses without a Content-Length header.
            }
            val bytes = buffer.readByteArray()
            require(bytes.size <= UpdateManifestVerifier.MAX_MANIFEST_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
        val verified = verifier.verify(envelope)
        if (verified.versionCode <= PackageInfoCompat.getLongVersionCode(installed)) {
            clearArtifacts()
            return null
        }
        val update = eligible(verified) ?: return null
        check(directory.isDirectory || directory.mkdirs())
        val output = metadata.startWrite()
        try {
            output.write(envelope.toByteArray(Charsets.UTF_8))
            metadata.finishWrite(output)
        } catch (e: Exception) {
            metadata.failWrite(output)
            throw e
        }
        // Discard artifacts from older candidates without touching wallet data.
        directory.listFiles()?.filter {
            (it.extension == "apk" || it.extension == "part") && it.nameWithoutExtension != update.sha256
        }?.forEach { it.delete() }
        return update
    }

    fun readyFile(update: UpdateManifest): File? = File(directory, "${update.sha256}.apk")
        .takeIf { downloader.verify(it, update) }

    fun download(update: UpdateManifest, progress: (Int) -> Unit): File =
        downloader.download(update, progress)

    fun verifyForInstall(update: UpdateManifest): File {
        // Re-authenticate persisted metadata and re-hash the APK immediately before installation.
        require(cached() == update) { "The update candidate changed" }
        val apk = requireNotNull(readyFile(update)) { "The update APK is incomplete" }
        val archive = requireNotNull(packageManager.getPackageArchiveInfo(
            apk.absolutePath, signatureFlags()
        )) { "Invalid APK" }
        require(archive.packageName == context.packageName)
        require(PackageInfoCompat.getLongVersionCode(archive) == update.versionCode)
        require(archive.applicationInfo?.minSdkVersion == update.minSdk)
        val archiveSigners = signatures(archive)
        require(archiveSigners.size == signerBytes.size && signerBytes.all { installedSigner ->
            archiveSigners.any { it.contentEquals(installedSigner) }
        }) { "APK signer does not match the installed app" }
        return apk
    }

    private fun eligible(update: UpdateManifest): UpdateManifest? = update.takeIf {
        it.versionCode > PackageInfoCompat.getLongVersionCode(installed) && it.minSdk <= Build.VERSION.SDK_INT
    }

    private fun clearArtifacts() {
        metadata.delete()
        directory.listFiles()?.filter { it.extension == "apk" || it.extension == "part" }
            ?.forEach { it.delete() }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): List<ByteArray> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toByteArray() }?.also { require(it.isNotEmpty()) }
            ?: error("Missing APK signing certificate")

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    companion object {
        const val MANIFEST_URL = "https://github.com/cashubtc/Numo/releases/latest/download/update.json"

        private fun secureClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            // Redirects to GitHub's artifact CDN are allowed, but never to cleartext HTTP.
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                if (!chain.request().url.isHttps) throw IOException("HTTPS required for updates")
                chain.proceed(chain.request())
            }
            .build()
    }
}
