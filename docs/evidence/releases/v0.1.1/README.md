# Shello v0.1.1 release acceptance

Sources include merged [PR #5](https://github.com/JackCaow/Shello/pull/5) (Windows/WSL2 pairing) and [PR #6](https://github.com/JackCaow/Shello/pull/6) (SSH connection form). Android versionCode 11, versionName 0.1.1, API 27+, arm64-v8a. No Maven publication.

Local checks on 2026-10-22:

| Check | Result |
| --- | --- |
| Android app/test APK build; app/library release lint | Passed |
| Pairing Python integration tests | 18/18 passed |
| Release metadata/publication/checksum tests | 7/7 passed; corrupt Windows asset stops publication |
| PowerShell checks, machine mutations mocked | 87 assertions passed; Windows 5.1/7 also run in CI |
| API 27: theme (4), pairing (6), preview cache (4), connection layout/IME (1), encrypted profiles/migration (2) | 17/17 passed |
| API 35: same test groups | 17/17 passed |
| Published signed v0.1.0 → signed v0.1.1 replacement install, API 27 | Passed; Light theme preference retained |
| Signed R8 APK clean install and cold launch, API 35 | Passed; Windows pairing selector updates copied-command text |
| Signing certificate | Matches existing public release certificate |

The API 27 layout test now waits for the actual keyboard viewport reduction rather than input focus; cold IME startup can lag behind focus. This fixes a test timing issue without changing the app's layout behavior.

The upgrade smoke check verifies the Android replacement install and an existing preference. Encrypted profile and legacy migration behavior is separately covered by the storage tests. These emulator results do not replace every vendor/physical-device acceptance case. The user's physical phone and existing data were not changed during this release task.

Real Windows + WSL2 + phone end-to-end acceptance remains pending; no Windows computer was available. Windows machine setup/network mutations are mocked in CI; real Linux SSH/tmux and Android forwarding are covered by the [Windows/WSL2 validation record](../../windows-wsl/README.md).

The version tag must be created only after the final source CI passes. The tag-triggered Release APK workflow signs and publishes the APK, both standalone pairing scripts, SHA256SUMS and certificate details. Downloaded public assets must be checked again after publication.
