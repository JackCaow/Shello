# Shared sources and resources

Both platform projects consume these files directly; generated app bundles keep the existing resource names.

- `agent/ghostty_hook.py`: the shared computer-side Claude/Codex hook, bundled as an Android asset and iOS app resource. Update this source and run `python3 -m unittest discover -s companion -p 'test_*.py'`.
- `native/`: C VT bridge used by the iOS Swift packages.
- `patches/`: pinned Ghostty engine patches used by both native builds.
- `assets/`: source branding and README media. Platform app icons remain in each app's asset catalog/resource tree.
- `resources/META-INF/ghostty-android/`: common VT third-party notices, packaged in the Android AAR and the iOS app. Android-only dependency notices stay in the Android app; OpenSSL/libssh2 notices stay in the iOS app.

The stable `companion/` download endpoints and root `skills/` entry points remain outside this directory to preserve published pairing commands and skill links. Build and release automation lives in root `scripts/` and `.github/`.
