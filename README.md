# KorvaiHub Android (native Kotlin / Jetpack Compose)

**Source-code prototype, not a tested APK.** No hosted application or database is required. Your existing S3 bucket still incurs normal S3 charges.

## Features in this first build
- Native Android Compose list, text search, talam/nadai filters, favorites, classification editor
- Local Room/SQLite database; S3 sync inserts new keys without overwriting edited metadata
- Handles original KorvaiHub UUID object names without extensions
- S3 listing and 15-minute audio playback URLs using AWS Signature V4
- Audio player via Android Media3; Android Keystore-encrypted IAM credentials
- No keys embedded in source or APK; private S3 bucket; one-time setup on device
- **Upload audio** from Android file picker to S3 (up to 50 MB), then edit classification

## Build
1. Install Android Studio (JDK 17), open this folder, and let Gradle sync.
2. Build > Build APK(s). The resulting debug APK can be installed on your Android device with USB debugging or a local file transfer. **This environment has not built or device-tested the APK.**
3. The app defaults to bucket `korvaibucket`, region `us-west-2` as an **editable placeholder**. Enter your bucket's actual AWS region.

## One-time IAM key setup (no hosting)
1. In AWS IAM, use a **dedicated** IAM user/key restricted to `s3:ListBucket` on `arn:aws:s3:::korvaibucket` and `s3:GetObject` and `s3:PutObject` on `arn:aws:s3:::korvaibucket/*`. Avoid admin or broad keys. Confirm any bucket policy and KMS permissions separately.
2. On Android, enter bucket, region, Access Key ID, and Secret Access Key in the setup screen. **Do not paste keys into ChatGPT, a Git repository, or a screenshot.**
3. Tap **Test connection & save**. The app lists S3 objects to verify access before encrypting the credentials and bucket settings locally with Android Keystore AES-GCM.
4. Tap **Sync S3**, then play recordings and edit metadata. Next launches reuse saved credentials. **Disconnect** erases saved credentials but not the local classifications.

### Security limitations
- This is a convenience/security tradeoff: a long-lived key on a phone can be abused if the phone or app is compromised. Keystore protects data at rest, not a compromised running process. Rotate/revoke the IAM key if exposed or the phone is lost.
- The app uploads new S3 objects when requested; it does not delete or overwrite existing recordings. Restrict PutObject to the `uploads/*` prefix where possible. Keep the bucket private and AWS permissions least-privileged.
- The key is entered into a password-masked field and is not compiled into the APK. Android application backup is disabled.
- Test connection currently checks `ListBucket`, not object read permission; verify playback separately.
- Region must match the bucket region; objects encrypted with SSE-KMS may need `kms:Decrypt`.

## Data recovery and backup
Your former MongoDB data is absent; the app creates blank classifications when discovering S3 keys. The metadata is stored in Android's app-private SQLite database. **Do not uninstall the app or clear its data without backing up the database.** This prototype does not yet include a backup/export screen. A CSV/JSON export and import is the highest-priority next feature before entering substantial new classifications.

## Known first-version limitations
- Not yet compiled or tested against your actual S3 account or Android device.
- Uploading existing audio files is supported; recording directly inside the app, bulk editing, backup/import, and AI search are not yet supported.
- S3 list uses a region-specific endpoint; enter the actual bucket region. Object keys with unusual XML characters or uncommon S3 URL encodings need testing.
- Presigned URLs expire after 15 minutes; restart playback after expiration.
- Android build dependencies require network access in Android Studio.

## Uploading recordings
1. Ensure the IAM identity has `s3:PutObject` permission for `arn:aws:s3:::YOUR_BUCKET/uploads/*`. The existing ListBucket and GetObject permissions remain necessary.
2. Record audio using the Android voice recorder or another recording app, then tap **Upload audio** in KorvaiHub.
3. Select an audio file. Supported extensions: mp3, wav, m4a, aac, ogg, flac, aif, aiff; limit 50 MB.
4. On success, KorvaiHub opens the classification editor. Add the tala, nadai, and other fields, then save locally.
5. The recording is stored under a random `uploads/<uuid>.<extension>` key, so existing objects are not overwritten. Metadata is currently stored **only on this device**, not in S3.

The upload uses an AWS Signature V4 presigned PUT URL generated locally with your saved IAM key, then sends the audio directly over HTTPS. There is no application server. The app does not require broad S3 write permissions.

## Backup and restore (v0.4)

The **Backup SQLite** button uses Android's file picker to save a consistent SQLite
snapshot (`korvaihub-backup.sqlite`) anywhere your Android device supports, such
as Downloads or a connected cloud drive. It does **not** contain AWS credentials
or audio. The **Restore SQLite** button selects that snapshot and imports all
korvai classifications by S3 object key. Matching entries are overwritten by the
backup, while entries absent from the backup remain. A failed validation leaves
the current database unchanged. Back up your current classifications before
restoring an older snapshot. Keep backups somewhere other than the phone to
protect against device loss.

Audio is still uploaded to and streamed from the configured S3 bucket. Backing up
SQLite does not back up S3 recordings. Keep S3 versioning or separate S3 backups
if you need protection from accidental loss of audio.

## Build on GitHub without Android Studio

1. Create a **private** GitHub repository, e.g. `KorvaiHubAndroid`.
2. Upload the **contents** of this `KorvaiHubAndroid` folder to the repository root (so `.github/workflows/android-apk.yml` and `app/` are at the top level). GitHub's web upload may not preserve hidden folders reliably; use Git on your Mac for the `.github` directory if necessary.
3. On GitHub, select **Actions → Build KorvaiHub Android APK → Run workflow** (choose `main`). Alternatively, a push to `main` triggers a build.
4. When the run finishes successfully, open its page and download the **KorvaiHub-debug-apk** artifact. Unzip the artifact to obtain `app-debug.apk`.
5. Transfer `app-debug.apk` to Android and open it to install. Android may ask you to allow installation from your browser or file manager. Only install an APK from your own trusted build.

This workflow uses JDK 17, Gradle 8.10.2, Android SDK 35 and uploads the debug APK. **It has not been run or verified to compile in this environment.** If it fails, open the failed workflow step and copy its error log for diagnosis. No AWS keys belong in GitHub or the APK; enter restricted IAM credentials only on the phone after installation. Debug APKs are for personal testing, not Play Store distribution. For updates, keep the same application ID and signing key; GitHub's default debug signing key may change between builds, so a later debug APK may require uninstalling the prior app, which can erase local SQLite data. **Back up classifications before uninstalling or replacing the app.**
