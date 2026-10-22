# APK releases

The first public APK is `v0.1.0-alpha02` (versionCode 2), Android API 27+, arm64-v8a. GitHub Releases contains the R8 release APK, standalone macOS/Linux and Windows pairing scripts, SHA-256 checksums and public signing-certificate information. Maven packages are not published.

## Signing and upgrade compatibility

Public APKs use a dedicated RSA-4096 release certificate. Certificate SHA-256:

```text
2a28be23a3a37b400c9ba0ad85325b4d8785efc7b396e4bb6b944045d7027ad5
```

Keep the same release key for future upgrades. Earlier development installs used a different debug certificate and cannot be overwritten by this release APK. Do not uninstall a development build if its saved connections are still needed: uninstalling removes local credentials and settings. Future public release APKs can update this first public release normally.

The local maintainer key is stored outside the repository in `~/.local/share/shello/signing/release.p12`, with its password in a separate mode-0600 `password` file. Back up both securely; neither belongs in Git, a release asset, logs or a checksum list.

## Build and package

With the native library built, Java 17 and Android SDK build-tools 35.0.0 available:

```sh
scripts/package-apk.sh
```

Use `SHELLO_SIGNING_DIR` for another private signing directory and `ANDROID_HOME` for the SDK. The script requires an existing PKCS12 key named `release.p12`, alias `shello`, and a `password` file. It builds the release variant, aligns, signs and verifies the APK, and writes assets under `build/releases/v<versionName>/`. It does not upload or publish automatically.

Before publishing, run release lint, instrumented theme/cache checks on API 27/35, `python3 tests/test_pair_launcher.py`, and verify a clean install of the signed APK. Use a dedicated emulator for release signing checks so existing debug app data is preserved. Commit the exact tested sources, tag that commit, and publish only the explicit APK, shell/PowerShell scripts, checksum and signature assets.

## Tag-driven GitHub Actions

`.github/workflows/release.yml` builds and publishes on pushes of `v*` tags. The tag must exactly equal `v` plus `android/sample/build.gradle.kts`'s `versionName`; increment `versionCode` for each new release so Android accepts the upgrade. For example, after setting versionName to `0.1.0-alpha03` and versionCode to `3`, commit and push those changes, then:

```sh
git tag -a v0.1.0-alpha03 -m 'Shello 0.1.0-alpha03'
git push origin v0.1.0-alpha03
```

The workflow validates the launcher and release scripts, builds the pinned Ghostty VT core and R8 APK, runs release lint, signs with the existing certificate and checks its fingerprint, uploads only public release artifacts, then publishes a GitHub Release. Versions containing `-` are prereleases. No Maven publication runs. GitHub-hosted jobs do not repeat device instrumentation or the full SSH/TUI acceptance matrix; run those before tagging.

The repository Actions secrets `SHELLO_RELEASE_KEYSTORE_BASE64` and `SHELLO_RELEASE_KEYSTORE_PASSWORD` hold the existing private signing inputs. They are decoded only after build validation into a private runner-temporary directory and removed in an `always()` cleanup step. Public releases are never overwritten by reruns; an incomplete draft may be resumed. Preserve the local key backup independently of GitHub Secrets.

For a full build/signing check without creating or editing a Release:

```sh
gh workflow run release.yml --ref main
```

Download the `signed-apk` artifact from that run (retained 14 days). Manual runs deliberately skip the publish job. Regular branch/PR CI remains in `build.yml`; tag builds use `release.yml` exclusively. Trigger details follow the [GitHub Actions tag-filter documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow#using-filters-to-target-specific-branches-or-tags-for-push-events).
