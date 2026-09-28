package com.electricdreams.numo.core.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import java.security.PublicKey
import java.security.Signature

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val releaseNotes: String,
)

/** The installed APK's signer is the trust anchor; network metadata cannot supply a key. */
class UpdateManifestVerifier(private val trustedKeys: List<PublicKey>) {
    fun verify(envelope: String): UpdateManifest {
        require(envelope.length <= MAX_MANIFEST_BYTES) { "Update manifest is too large" }
        val wrapper = JsonParser.parseString(envelope).asJsonObject
        val payload = requireNotNull(wrapper.text("payload").decodeBase64()).toByteArray()
        val signature = requireNotNull(wrapper.text("signature").decodeBase64()).toByteArray()
        require(trustedKeys.any { key ->
            val algorithm = when (key.algorithm) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                else -> return@any false
            }
            Signature.getInstance(algorithm).run {
                initVerify(key)
                update(SIGNING_CONTEXT.toByteArray(Charsets.UTF_8))
                update(payload)
                verify(signature)
            }
        }) { "Invalid update manifest signature" }

        val json = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
        require(json.number("schemaVersion") == 1L)
        require(json.text("packageName") == "com.electricdreams.numo")
        require(json.text("channel") == "stable")
        val url = json.text("apkUrl").toHttpUrl()
        require(url.isHttps && url.host == "github.com" && url.port == 443)
        require(url.username.isEmpty() && url.password.isEmpty())
        require(url.query == null && url.fragment == null)
        val segments = url.pathSegments
        require(segments.size == 6 && segments.take(4) ==
            listOf("cashubtc", "Numo", "releases", "download"))
        require(segments[4].isNotBlank() && segments[5].endsWith("-universal.apk"))

        val versionCode = json.number("versionCode")
        val versionName = json.text("versionName")
        val minSdk = json.number("minSdk")
        val size = json.number("size")
        val hash = json.text("sha256")
        val notes = json.text("releaseNotes")
        require(versionCode in 1..Int.MAX_VALUE.toLong())
        require(versionName.isNotBlank() && versionName.length <= 100)
        require(minSdk in 1..Int.MAX_VALUE.toLong())
        require(size in 1..MAX_APK_BYTES)
        require(hash.matches(Regex("[a-f0-9]{64}")))
        require(notes.length <= 16_000)
        return UpdateManifest(versionCode, versionName, minSdk.toInt(), url.toString(), size,
            hash, notes)
    }

    private fun JsonObject.text(name: String): String {
        val value = get(name)?.asJsonPrimitive
        require(value != null && value.isString) { "Missing string: $name" }
        return value.asString
    }

    private fun JsonObject.number(name: String): Long {
        val value = get(name)?.asJsonPrimitive
        require(value != null && value.isNumber) { "Missing number: $name" }
        return value.asString.toLong()
    }

    companion object {
        const val SIGNING_CONTEXT = "Numo update manifest v1\n"
        const val MAX_MANIFEST_BYTES = 128 * 1024
        const val MAX_APK_BYTES = 1024L * 1024 * 1024
    }
}
