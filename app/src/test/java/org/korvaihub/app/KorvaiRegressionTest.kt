package org.korvaihub.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class KorvaiRegressionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val session = Session("test-access", "test-secret")

    @Test fun appLaunchesWithoutCredentials() {
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertNotNull(activity.get().findViewById<android.view.View>(android.R.id.content)) }
        finally { activity.pause().stop().destroy() }
    }

    @Test fun syncPreservesClassificationsAndBackupRoundTrips() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, KorvaiDb::class.java).build()
        val file = File.createTempFile("backup", ".sqlite", context.cacheDir)
        val original = Korvai("audio/one", talam = "Adi", nadai = "Tisra", mathirai = "32", purvangaMathirai = "8", madhyangaMathirai = "8", utarangaMathirai = "16", tag = "practice", favorite = true)
        try {
            db.dao().insertNew(listOf(original))
            db.dao().insertNew(listOf(Korvai(original.key)))
            assertEquals(original, db.dao().snapshot().single())
            assertEquals(1, DatabaseBackup.export(context, db, Uri.fromFile(file)))
            db.dao().update(original.copy(talam = "Changed"))
            db.dao().insertNew(listOf(Korvai("new-recording")))
            assertEquals(1, DatabaseBackup.restore(context, db, Uri.fromFile(file)))
            assertEquals(original, db.dao().snapshot().first { it.key == original.key })
            assertEquals(2, db.dao().snapshot().size)
            file.writeText("invalid database")
            try {
                DatabaseBackup.restore(context, db, Uri.fromFile(file))
                fail("Invalid backup must be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(2, db.dao().snapshot().size)
        } finally { db.close(); file.delete() }
    }

    @Test fun listingPaginatesAndHandlesXmlEscapes() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (calls++ == 0) {
                "<ListBucketResult><Contents><Key>audio/a &amp; b.mp3</Key></Contents><Contents><Key>ignore.txt</Key></Contents><NextContinuationToken>token+/=</NextContinuationToken></ListBucketResult>"
            } else {
                assertEquals("token+/=", request.url.queryParameter("continuation-token"))
                "<ListBucketResult><Contents><Key>uuid-without-extension</Key></Contents></ListBucketResult>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        assertEquals(listOf("audio/a & b.mp3", "uuid-without-extension"), S3Client("korvaibucket", "us-west-2", session, http).listKeys())
        assertEquals(2, calls)
    }

    @Test fun uploadCannotOverwriteAndUsesRestrictedPrefix() = runBlocking {
        val file = File(context.cacheDir, "recording.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var called = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("PUT", request.method)
            assertEquals("*", request.header("If-None-Match"))
            assertTrue(request.url.encodedPath.startsWith("/korvaibucket/uploads/"))
            called = true
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        try {
            assertTrue(S3Client("korvaibucket", "us-west-2", session, http).uploadAudio(context, Uri.fromFile(file)).startsWith("uploads/"))
            assertTrue(called)
        } finally { file.delete() }
    }

    @Test fun presignedPlaybackPreservesSpecialCharactersAndTemporaryToken() {
        val key = "audio/a +&?# தமிழ்.mp3"
        val url = S3Client("korvaibucket", "us-west-2", session.copy(token = "temporary+/=")).audioUrl(key).toHttpUrl()
        assertEquals(listOf("korvaibucket", "audio", "a +&?# தமிழ்.mp3"), url.pathSegments)
        assertEquals("temporary+/=", url.queryParameter("X-Amz-Security-Token"))
        assertEquals("900", url.queryParameter("X-Amz-Expires"))
        assertEquals(64, url.queryParameter("X-Amz-Signature")!!.length)
        assertFalse(url.toString().contains(session.secret))
    }
}
