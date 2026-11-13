NAS Uploader v1.2.3 (version code 6)

Removes automatic Telegram launch, the upload-screen Telegram button and the
"Offer Telegram after successful upload" setting. Successful uploads stay in the app.
NAS SMB uploads, progress, retry, file browsing and Copy uploaded addresses remain.
Existing NAS settings and encrypted credentials are preserved; the legacy Telegram
preference is ignored.

Uses the existing persistent-signing workflow and pinned certificate. Older
debug-signed installations still require a one-time reinstall; record NAS settings
and credentials first. Persistent release installations can update in place.

CI runs unit tests, release lint and APK assembly. The release workflow verifies
the signing identity, APK signature, package/version and non-debuggable status,
and publishes checksums. NAS/device integration is not tested by CI.
