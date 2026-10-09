package com.electricdreams.numo.core.update

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.cert.CertificateFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], application = Application::class)
class UpdateRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = mock()
    private val packageManager: PackageManager = mock()
    private lateinit var certificate: ByteArray
    private var response = "valid"
    private lateinit var archive: PackageInfo
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        // No Content-Length, as with a chunked GitHub response.
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = Buffer().writeUtf8(resource("$response.json"))
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
    }.build()

    @Before fun setUp() {
        certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(resource("signer.pem").byteInputStream()).encoded
        whenever(context.packageName).thenReturn("com.electricdreams.numo")
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.noBackupFilesDir).thenReturn(temporary.root)
        val installed = packageInfo(25)
        whenever(packageManager.getPackageInfo(eq(context.packageName), any<Int>()))
            .thenReturn(installed)
        archive = packageInfo(26)
        whenever(packageManager.getPackageArchiveInfo(any<String>(), any<Int>())).thenAnswer { archive }
    }

    @Test fun `Java signed manifest is accepted and cached across repository recreation`() {
        val repository = UpdateRepository(context, client)
        val update = requireNotNull(repository.check())
        assertEquals(26L, update.versionCode)
        assertEquals(update, UpdateRepository(context, client).cached())
    }

    @Test fun `same version and unsupported Android version are not offered`() {
        response = "old"
        assertNull(UpdateRepository(context, client).check())
        response = "incompatible"
        assertNull(UpdateRepository(context, client).check())
    }

    @Test fun `APK package version sdk and signer must all match the signed manifest`() {
        val repository = UpdateRepository(context, client)
        val update = requireNotNull(repository.check())
        val apk = File(temporary.root, "updates/${update.sha256}.apk").apply { writeText("APK") }
        assertEquals(apk, repository.verifyForInstall(update))
        archive.packageName = "com.attacker.wallet"
        assertThrows(IllegalArgumentException::class.java) { repository.verifyForInstall(update) }
        archive = packageInfo(27)
        assertThrows(IllegalArgumentException::class.java) { repository.verifyForInstall(update) }
        archive = packageInfo(26).apply { applicationInfo?.minSdkVersion = 25 }
        assertThrows(IllegalArgumentException::class.java) { repository.verifyForInstall(update) }
        archive = packageInfo(26, byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { repository.verifyForInstall(update) }
    }

    @Test fun `APK modified after download is rejected before installation`() {
        val repository = UpdateRepository(context, client)
        val update = requireNotNull(repository.check())
        File(temporary.root, "updates/${update.sha256}.apk").writeText("BAD")
        assertThrows(IllegalArgumentException::class.java) { repository.verifyForInstall(update) }
    }

    @Test fun `successful upgrade clears update artifacts while preserving other app data`() {
        val repository = UpdateRepository(context, client)
        val update = requireNotNull(repository.check())
        val apk = File(temporary.root, "updates/${update.sha256}.apk").apply { writeText("APK") }
        val wallet = File(temporary.root, "wallet-sentinel").apply { writeText("wallet data") }
        val upgraded = packageInfo(26)
        whenever(packageManager.getPackageInfo(eq(context.packageName), any<Int>()))
            .thenReturn(upgraded)
        assertNull(UpdateRepository(context, client).cached())
        assertFalse(apk.exists())
        assertEquals("wallet data", wallet.readText())
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(version: Int, signer: ByteArray = certificate) = PackageInfo().apply {
        packageName = "com.electricdreams.numo"
        versionCode = version
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 24 }
        val signatureArray = arrayOf(android.content.pm.Signature(signer))
        if (Build.VERSION.SDK_INT >= 28) {
            signingInfo = mock<SigningInfo>().also {
                whenever(it.apkContentsSigners).thenReturn(signatureArray)
            }
        } else {
            signatures = signatureArray
        }
    }

    private fun resource(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/update/$name")
    ).bufferedReader().use { it.readText() }
}
