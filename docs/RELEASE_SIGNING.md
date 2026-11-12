# Persistent Android release signing

The only distributed APK is the release APK from **Signed Android release**.
Version 1.2.2 (code 5) begins the persistent signing lineage. The package ID stays
`sk.nas.shareuploader`. Every future release must use the same keystore and increase
both versionCode and versionName. This reproduces the signing identity; it does
not promise byte-for-byte reproducible APK builds.

## One-time private setup

The keystore and Windows-protected password are delivered privately outside this
repository. Never attach them to an issue, commit, release, workflow artifact or chat.
The connected GitHub integration cannot configure Actions secrets.

1. Keep `nas-uploader-release.jks`, `password.dpapi`, and `Configure-Signing.ps1`
   together on the Windows account/computer that generated them.
2. Install GitHub CLI if needed: `winget install --id GitHub.cli --exact`.
   Open a new PowerShell window, run `gh auth login`, and authenticate to github.com
   as a repository administrator. For classic tokens, repository access is needed;
   for fine-grained tokens, grant this repository Secrets write permission.
3. From the private folder run `powershell -NoProfile -ExecutionPolicy Bypass -File .\Configure-Signing.ps1`.
   It sets these **repository Actions secrets** using standard input, without printing values:
   - ANDROID_KEYSTORE_BASE64: Base64 of the original JKS bytes
   - ANDROID_KEYSTORE_PASSWORD: generated keystore password
   - ANDROID_KEY_ALIAS: nas-uploader
   - ANDROID_KEY_PASSWORD: same generated password
4. Confirm all four names at https://github.com/siddagivers/NAS_uploader/settings/secrets/actions.
   Base64 is encoding, not encryption; only store it as an encrypted Actions secret.
5. Open Actions > Signed Android release > Run workflow on main. Alternatively push
   a new version tag matching versionName, e.g. v1.2.2. A missing secret stops the job
   before checkout/build. A wrong key fails against `signing-certificate.sha256`.
6. Confirm the run is green, download the release APK, SHA256SUMS.txt and
   SIGNING-CERTIFICATE.txt. Run `sha256sum -c SHA256SUMS.txt` and
   `apksigner verify --verbose --print-certs NAS-uploader-v1.2.2-release.apk`.
   The certificate digest must match `signing-certificate.sha256`.
7. Keep two encrypted offline backups of the JKS and its password. DPAPI recovery
   requires the original Windows profile/machine; it is not a portable password backup.
   Use the private protection instructions to save the password in a password manager.

## Existing installations

Previous published APKs used disposable CI debug keys. Without the original private
key they cannot receive an in-place update signed by this new key. Record NAS settings
and credentials before uninstalling the old app (uninstall deletes its settings).
Install the first persistent release, re-enter settings, then install subsequent
release APKs over it normally. Do not install local debug APKs over release installs.

## Release safeguards

Unit tests and release lint run before signing. The workflow aligns the unsigned APK,
checks the private keystore's public certificate against the pinned SHA-256, signs via
password environment variables, verifies APK signatures and signer identity, checks
package/version/non-debuggable flags, and publishes only explicit public files.
Temporary signing files are removed even on failure. No signing key is generated in CI.
Existing release assets are never overwritten. Release publication is serialized.

Only trusted maintainers should edit or manually run the release workflow: repository
secrets are accessible to authorized workflow code. Restrict repository write access,
review workflow changes and protect main. Do not replace the keystore or change the
pinned certificate to bypass a signing failure. Losing this key breaks future updates.

References: https://developer.android.com/studio/publish/app-signing,
https://developer.android.com/tools/apksigner,
https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets.
