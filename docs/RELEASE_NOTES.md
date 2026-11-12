Persistent release signing begins with v1.2.2 (version code 5).

Future releases use the same protected signing key and can update this release in place.
Old debug-signed installations need a one-time uninstall/reinstall; first record NAS
settings and credentials because uninstall deletes them. No app behavior changes.

The release workflow verifies the certificate, APK signature, package/version and
non-debuggable status and publishes checksums. NAS/device integration is not tested by CI.
