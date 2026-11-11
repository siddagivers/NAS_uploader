# Send to NAS — Android Share target

Android app that appears as **Send to NAS** in Android's standard **Share** dialog. Works with photos, videos, documents, arbitrary binary files, webpage URLs and text.

## What is saved

| Source | Destination in NAS folder |
|---|---|
| Gallery share (one or multiple photos/videos) | Original binary file(s), byte-for-byte |
| Files share (PDF, ZIP, etc.) | Original binary file(s) |
| Chrome / browser Share page | UTF-8 `shared_link.txt`, containing the shared webpage URL and title, when provided |
| Selected text / message | UTF-8 `shared_text.txt`, containing the shared text |
| Image with a shared text caption | Image plus a separate `shared_text.txt` |

Each filename is prefixed with a timestamp and random identifier to avoid overwriting similarly named files. The folder defaults to `\\NAS-IP\hdd\copy`.

### Setup

1. Open the **NASShareUploader** directory in **Android Studio** (Android SDK 35, JDK 17, Gradle 8.14). Allow Gradle to download dependencies. If your Android Studio cannot find the Gradle wrapper, see the note below.
2. Select **Build > Build APK(s)**, then install `app/build/outputs/apk/debug/app-debug.apk` on your Android phone (USB or transfer the APK).
3. On the phone, open **Send to NAS > NAS settings** and enter:
   * **NAS IP/host:** e.g. `192.168.1.100` (do **not** enter `\\` or `smb://`)
   * **Username/password:** SMB login with write permission
   * **Share:** `hdd`
   * **Folder:** `copy`
   * **Domain:** leave blank unless required by your NAS
   * **Public link base:** optional, see below
4. Tap **Test NAS connection**, then **Save settings**.
5. Open **Gallery**, select photos, tap **Share**, select **Send to NAS**. The app uploads to `\\192.168.1.100\hdd\copy`.
6. After completion it can open **Telegram** with the uploaded file's address; choose **Saved Messages** in Telegram and tap Send.

Repeat step 5 from Chrome, a file manager, PDF viewer, text selection, etc. You can also manually pick files from the app's home screen.

### Browse and open files

Tap **Open folder** to browse photos and files in an icon grid (default) or detailed list. The **List / Icons** toggle remembers your choice. Photos, supported videos and small PDFs show cached previews where possible; other files display type icons. Large videos use icons to avoid downloading large files for thumbnails. Tap a folder to navigate, or a file to open it.\n\nTap **Open folder** to list the configured NAS destination. Tap a subfolder to browse it, use **Up one folder** to return, or **Refresh** to reload. Tap a file to download a copy into the app cache and choose an installed Android app to open it. Opening large files requires enough free phone storage and a working NAS connection. Cached copies remain until Android clears the cache or you clear it in Android app settings. Changes made by another app are not uploaded back to the NAS. Windows programs cannot run on Android; supported files open with compatible installed apps.

### Important: NAS path vs Telegram download link

A Windows SMB path like `\\192.168.1.100\hdd\copy\photo.jpg` is **not** a web link. If you do not configure an HTTP(S) base URL, the app shares the UNC path as plain text. It generally works only on devices with direct SMB access to your LAN/VPN.

To get clickable links in Telegram, first arrange for a **web server or NAS sharing service** to serve the *same directory*. Example: if `https://files.example.com/copy/photo.jpg` truly serves `\\nas\hdd\copy\photo.jpg`, configure the link base as `https://files.example.com/copy`. The app will append the percent-encoded uploaded file name. **The app does not create HTTP sharing, grant access, or expose SMB outside your LAN.** A private URL may only work over your LAN or VPN. Use HTTPS, authentication, and access control for anything exposed publicly.

For Telegram, the app invokes the standard Telegram share intent. Android doesn't allow a third-party app to silently select or post to your personal **Saved Messages** chat via this integration; you choose it yourself. A Telegram bot cannot directly post into your personal Saved Messages chat.

### Security and networking

* SMB2/SMB3 direct TCP connection to NAS port **445**. Android phone must reach the NAS over Wi-Fi or VPN; no root, storage permission, or server-side app needed.
* Password encrypted with **Android Keystore AES-256-GCM**, not stored in plaintext preferences. Android backup is disabled; settings don't migrate automatically.
* No password in filenames or upload messages. Use a **dedicated NAS account** with permission limited to the upload folder if possible.
* SMB authentication can be NTLM. Encryption in transit depends on SMB server negotiation; don't expose TCP 445 to the internet. For remote use, connect over VPN.
* `INTERNET` permission is only for network access. Android automatically grants permission to the item being shared.

### Current limitations

* **Uploads run while the upload Activity stays alive.** Keep the screen open until upload completes (the screen is kept awake). Android may cancel transfers if it kills the app or the user swipes it away. Failed transfers can be retried; partially written files are deleted on a normal failure. There is no persistent offline queue or background resumable transfer in this v1.
* No recursive folder uploading — the Share dialog provides files. Webpage sharing stores the title/URL as text, **not** an offline HTML snapshot of the website.
* A malicious/broken third-party content provider may deny access to a URI even when sharing it. App displays the error.
* Some OEM share menus may require selecting **More** to show all available share targets.
* Very large files require reliable Wi-Fi and enough time with the upload screen active.

### Android APK build

A GitHub Actions workflow named **Android APK** runs on each push to `main` and on manual dispatch. Open the repository's **Actions** tab, select the latest successful **Android APK** run, and download the `Send-to-NAS-v1.2.0-debug` artifact. Extract the ZIP to obtain `app-debug.apk`. An uploaded workflow definition is not proof of a successful build; check the run's result before installing.

### Gradle wrapper

The archive contains the Gradle wrapper properties, but not the generated `gradle-wrapper.jar` binary. If your Android Studio needs the wrapper, install Gradle 8.14 and run the following once in this directory, or use Android Studio's Gradle distribution settings:

```sh
gradle wrapper --gradle-version 8.14 --distribution-type bin
```

Then use `./gradlew assembleDebug` (or `gradlew.bat assembleDebug` on Windows). Android Studio may run its own configured Gradle distribution directly.

### Source layout

* `MainActivity.kt`: Android Share target, multiple-item queue, progress, Retry and Telegram
* `SettingsActivity.kt`: UI for IP, login, share path, link base and connection test
* `SettingsStore.kt`: validated NAS settings and Keystore AES/GCM encrypted password
* `IncomingShare.kt`: EXTRA_STREAM / ClipData / EXTRA_TEXT parsing and document metadata
* `SmbUploader.kt`: SMBJ client; folder creation, upload, cleanup and URL creation
* `FileNaming.kt`: filename sanitization, unique naming and link construction

This source package is provided for building in Android Studio. It has **not** been built or run against a NAS in this environment (no Android SDK/NAS is available here).
