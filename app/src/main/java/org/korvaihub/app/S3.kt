package org.korvaihub.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

// A dedicated, read-only IAM key may be stored locally; never ship keys in the APK.
data class Session(val access: String, val secret: String, val token: String = "", val expiration: String = "") {
    companion object {
        fun parse(raw: String): Session {
            val j = JSONObject(raw)
            val s = Session(j.getString("AccessKeyId").trim(), j.getString("SecretAccessKey").trim(),
                j.optString("SessionToken"), j.optString("Expiration"))
            require(s.access.isNotBlank() && s.secret.isNotBlank()) { "Both access key and secret key are required" }
            return s
        }
    }
}
class SecureSession(private val context: Context) {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("korvaihub_session", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("korvaihub_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun save(raw: String, bucket: String, region: String) {
        Session.parse(raw)
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) { "Invalid S3 bucket" }
        require(region.matches(Regex("[a-z]{2}-[a-z]+-\\d"))) { "Invalid AWS region" }
        val payload = JSONObject().put("credentials", JSONObject(raw)).put("bucket", bucket).put("region", region).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString("data", Base64.encodeToString(cipher.iv + cipher.doFinal(payload.toByteArray()), Base64.NO_WRAP)).commit()
    }
    fun loadConfig(): Triple<Session, String, String>? = try {
        val data = Base64.decode(prefs.getString("data", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val j = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        Triple(Session.parse(j.getJSONObject("credentials").toString()), j.getString("bucket"), j.getString("region"))
    } catch (_: Exception) { null }
    fun clear() { prefs.edit().clear().commit() }
}
class S3Client(private val bucket: String, private val region: String, private val session: Session) {
    private val http = OkHttpClient()
    private val host = "s3.$region.amazonaws.com"
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hmac(key: ByteArray, message: String): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(message.toByteArray(Charsets.UTF_8)) }
    private fun sha(value: String): String = hex(java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun signature(date: String, toSign: String): String {
        val kDate = hmac(("AWS4" + session.secret).toByteArray(), date)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, "s3")
        return hex(hmac(hmac(kService, "aws4_request"), toSign))
    }
    private fun query(params: Map<String, String>): String = params.entries.sortedWith(compareBy({ encode(it.key) }, { encode(it.value) })).joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    private fun signedUrl(key: String?, params: Map<String, String>, expires: Int, method: String = "GET"): String {
        require(expires in 1..3600)
        val now = Date()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val scope = "$day/$region/s3/aws4_request"
        val path = "/${encode(bucket)}" + (key?.let { "/" + it.split('/').joinToString("/") { part -> encode(part) } } ?: "")
        val q = params + mapOf("X-Amz-Algorithm" to "AWS4-HMAC-SHA256", "X-Amz-Credential" to "${session.access}/$scope", "X-Amz-Date" to stamp, "X-Amz-Expires" to expires.toString(), "X-Amz-SignedHeaders" to "host") + (if (session.token.isNotBlank()) mapOf("X-Amz-Security-Token" to session.token) else emptyMap())
        val canonicalQuery = query(q)
        val request = "$method\n$path\n$canonicalQuery\nhost:$host\n\nhost\nUNSIGNED-PAYLOAD"
        val stringToSign = "AWS4-HMAC-SHA256\n$stamp\n$scope\n${sha(request)}"
        return "https://$host$path?$canonicalQuery&X-Amz-Signature=${signature(day, stringToSign)}"
    }
    fun audioUrl(key: String): String = signedUrl(key, emptyMap(), 900)
    suspend fun uploadAudio(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "recording.m4a"
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        val allowed = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aif", "aiff")
        require(extension in allowed) { "Choose an audio file (mp3, wav, m4a, aac, ogg, flac, aif, aiff)" }
        val maxBytes = 50 * 1024 * 1024
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                total += count
                require(total <= maxBytes) { "Audio must be 50 MB or smaller" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Cannot open selected recording")
        require(bytes.isNotEmpty()) { "Audio file is empty" }
        val mime = when (extension) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
        // A random object key prevents overwriting an existing recording.
        val key = "uploads/${UUID.randomUUID()}.$extension"
        val url = signedUrl(key, emptyMap(), 900, method = "PUT")
        val request = Request.Builder().url(url).put(bytes.toRequestBody(mime.toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Upload failed (HTTP ${response.code}): ${response.body?.string()?.take(250)}")
        }
        key
    }
    suspend fun listKeys(): List<String> = withContext(Dispatchers.IO) {
        val keys = mutableListOf<String>()
        var continuation: String? = null
        do {
            val args = mutableMapOf("list-type" to "2", "max-keys" to "1000")
            continuation?.let { args["continuation-token"] = it }
            val request = Request.Builder().url(signedUrl(null, args, 60)).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("S3 returned HTTP ${response.code}: ${response.body?.string()?.take(350)}")
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(response.body!!.bytes()))
                val contents = doc.getElementsByTagName("Contents")
                for (i in 0 until contents.length) {
                    val node = contents.item(i) as Element
                    val k = node.getElementsByTagName("Key").item(0)?.textContent ?: continue
                    val suffix = k.substringAfterLast('/').lowercase(Locale.US)
                    if (!k.endsWith('/') && ('.' !in suffix || listOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aif", "aiff").any { suffix.endsWith(".$it") })) keys.add(k)
                }
                continuation = doc.getElementsByTagName("NextContinuationToken").item(0)?.textContent
            }
        } while (!continuation.isNullOrBlank())
        keys
    }
}
