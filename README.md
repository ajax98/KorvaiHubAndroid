# KorvaiHub Android

Native Kotlin / Jetpack Compose app for a personal mridangam korvai library.
Audio stays in your existing private S3 bucket (`korvaibucket` by default).
Classifications stay in Room/SQLite on the phone. No application server,
MongoDB, or external app hosting is used.

## Features

- List and stream S3 recordings, including original UUID keys without extensions.
- Edit talam, nadai, kaalam, edam, akshara, mathirai, purvanga, madhyanga,
  uttaranga, tags, notes, and favorites. Search and filter the local library.
- Sync discovers new recordings without replacing existing classifications.
- Upload an existing audio file through the Android picker, up to 50 MB.
  Supported formats: mp3, wav, m4a, aac, ogg, flac, aif, aiff.
- Uploads use random `uploads/<uuid>.<extension>` keys and `If-None-Match: *`
  so an existing object cannot be overwritten. The app never deletes S3 objects.
- Export a consistent SQLite metadata snapshot using the Android file picker.
  Restore merges by S3 key, replaces matching classifications, and retains rows
  absent from the backup. Invalid backups leave current classifications intact.
- User-provided credentials are encrypted with Android Keystore AES-GCM.
  Credentials and recordings are excluded from metadata exports; application
  backup is disabled.

## Build and checks

Use JDK 17, Android SDK platform 35 and build-tools 34.0.0. The committed Gradle
8.10.2 wrapper includes a distribution checksum. AGP is 8.7.3; Kotlin, its Compose
compiler plugin, and KAPT are 2.0.21; Room is 2.6.1. JVM compilation targets 17.
KAPT's language 1.9 fallback warning with Kotlin 2.0 is expected.

```sh
./gradlew clean :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Tests use Robolectric on
Android API 26 and 35 to exercise Room sync preservation, SQLite export/restore,
invalid backup rejection, S3 pagination/XML decoding, upload overwrite protection,
and presigned URL encoding. S3 tests intercept HTTP requests; no credentials or
real bucket access are needed. These tests do not establish real-device behavior
or live AWS authorization/playback.

## Build and download on GitHub

The workflow `.github/workflows/android-apk.yml` runs on pushes to `main`, pull
requests targeting `main`, and manual dispatch. It installs JDK 17 and the Android
SDK, validates the wrapper, performs a clean debug build, runs tests and lint,
and uploads the APK only after all checks succeed.

1. Open the repository's **Actions** tab and select **Build KorvaiHub Android APK**.
2. Open a successful run. For a manual build, use **Run workflow** on `main`
   after this change is merged.
3. Under **Artifacts**, download **KorvaiHub-debug-apk** (GitHub sign-in required).
4. Extract the ZIP to obtain `app-debug.apk`.
5. Transfer it to your Android 8.0+ phone, open the APK in your file manager,
   allow installation from that source if prompted, and tap **Install**.

Debug APKs are signed automatically for personal testing. A fresh CI runner can
use a different debug signing key, so later builds may require uninstalling the
previous app. **Export and retain your SQLite backup before uninstalling or
clearing app data.** Signing keys are never committed. A stable release signing
identity is a separate setup.

## Connect to S3 on the phone

1. Enter the bucket and its actual region; `us-west-2` is an editable default.
2. Enter a dedicated IAM access key and secret key on the phone. Never put them
   in source, Actions secrets, logs, screenshots, or this chat.
3. Tap **Test connection & save**, then **Sync S3**. The connection test checks
   listing permission; test playback and upload separately on the device.
4. Select **Play**, **Edit**, or the favorite star for a recording. Use **Upload
   audio** to select a recording made with your preferred recording app.
5. **Disconnect** removes stored credentials while retaining local metadata.

Use this least-privilege policy for the existing bucket:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::korvaibucket"},
    {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::korvaibucket/*"},
    {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::korvaibucket/uploads/*"}
  ]
}
```

Do not grant DeleteObject. Keep the bucket private. A bucket policy can also
require conditional writes for the uploads prefix. SSE-KMS encrypted objects may
require separately scoped KMS permissions. Keystore protects credentials at rest;
rotate/revoke the dedicated key if the phone is compromised or lost.

## Backup and limitations

**Backup SQLite** saves metadata through Android's file picker and works on the
minimum supported Android 8. **Restore SQLite** imports a selected backup. Keep a
copy off the phone and back up current classifications before restoring older
metadata. Neither operation backs up or modifies audio in S3. Existing MongoDB
classifications are not imported automatically.

Playback URLs expire after 15 minutes; restart playback to refresh them. The app
supports uploading existing recordings rather than recording inside the app.
Real AWS connectivity and physical-device audio playback require a device with
your bucket credentials and have not been verified by automated mock tests.
