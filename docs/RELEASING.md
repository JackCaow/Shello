# APK releases

The first public APK is `v0.1.0-alpha02` (versionCode 2), Android API 27+, arm64-v8a. GitHub Releases contains the R8 release APK, standalone pairing script, SHA-256 checksums and public signing-certificate information. Maven packages are not published.

## Signing and upgrade compatibility

Public APKs use a dedicated RSA-4096 release certificate. Certificate SHA-256:

```text
2a28be23a3a37b400c9ba0ad85325b4d8785efc7b396e4bb6b944045d7027ad5
```

Keep the same release key for future upgrades. Earlier development installs used a different debug certificate and cannot be overwritten by this release APK. Do not uninstall a development build if its saved connections are still needed: uninstalling removes local credentials and settings. The phone used for development remains on an updated debug build. Future public release APKs can update this first public release normally.

The local maintainer key is stored outside the repository in `~/.local/share/shello/signing/release.p12`, with its password in a separate mode-0600 `password` file. Back up both securely; neither belongs in Git, a release asset, logs or a checksum list.

## Build and package

With the native library built, Java 17 and Android SDK build-tools 35.0.0 available:

```sh
scripts/package-apk.sh
```

Use `SHELLO_SIGNING_DIR` for another private signing directory and `ANDROID_HOME` for the SDK. The script requires an existing PKCS12 key named `release.p12`, alias `shello`, and a `password` file. It builds the release variant, aligns, signs and verifies the APK, and writes assets under `build/releases/v<versionName>/`. It does not upload or publish automatically.

Before publishing, run release lint, instrumented theme/cache checks on API 27/35, `python3 tests/test_pair_launcher.py`, and verify a clean install of the signed APK. Use a dedicated emulator for release signing checks so existing debug app data is preserved. Commit the exact tested sources, tag that commit, and publish only the explicit APK/script/checksum/signature assets.
