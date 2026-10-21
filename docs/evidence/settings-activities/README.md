# Independent settings Activity acceptance

2026-10-21. Sample-only navigation refactor; terminal AAR and storage formats are unchanged.

- [Xiaomi 15 Ultra / API 36](phone-api36-tests.txt), [API 27 emulator](api27-tests.txt), [API 35 emulator](api35-tests.txt): twelve tests each across SettingsNavigationTest, SettingsActivityTest and LocalizationTest. Covers independent destinations, nested system/header Back, direct inbox return, legacy intent migration, parent scroll, page recreation, text wrapping, font bounds, toolbar/theme persistence and language selection.
- A thumbnail lifetime regression keeps a previous window's ImageView alive after closing a disposable tab, then draws it. This reproduces the recycled-bitmap failure exposed during API 27 navigation without touching saved profiles.
- Physical gesture navigation, with system animation scale unchanged at 1.0: opened Font settings, cancelled a short edge swipe, then committed a full edge swipe back to the same settings index. [Observed destinations](gesture-results.json). The private recording remains local under build/settings-refactor/.
- Replacement install and final acceptance preserved all fourteen original settings/encrypted-profile files byte-for-byte. [Sanitized comparison](data-preservation.json). Raw hashes, host identifiers, screenshots and recordings are not included here.
- Debug APK, instrumentation APK, R8 release APK and debug lint assembled successfully. The physical device has the compatible replacement debug APK installed. No new tag, GitHub release or Maven publication.

The instrumentation launch harness waits for the destination Activity's lifecycle and focus instead of global UI idleness: a live terminal can redraw continuously on the OEM device. Follow-system language assertions use LocaleManager system locales on Android 13+, and tests restore both the original language value and whether its preference key existed.
