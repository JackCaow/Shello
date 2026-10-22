# Build and embed Shello

[Project overview](../README.md) · [API contract](API.md) · [Validation](VALIDATION.md)

Shello is an Android / iOS monorepo. The Android application (`android/sample`) and transport-independent terminal library (`android/terminal`) use the pinned Ghostty VT engine with Android Canvas rendering. The iOS app and Swift packages live in `ios/`, with their C bridge in `public/native/`; see [iOS build and verification](IOS_MIGRATION.md). Both apps share `companion/`, the pairing skill and the bundled agent hook. Neither app embeds the Ghostty desktop renderer. Maven packages are not publicly published.

## Build

Install JDK 17, Android SDK platform 35, NDK `28.2.13676358`, and Zig **0.16.0**. The wrapper pins Gradle 8.11.1. Native source and dependencies are downloaded from public upstream repositories on the first build. Native builds currently target ARM64 Android from macOS or Linux x86_64 hosts; see validation for actually tested hosts.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ZIG_BIN=/path/to/zig-0.16.0/zig
./scripts/build-native.sh
./android/gradlew -p android :terminal:assembleRelease :sample:assembleDebug :sample:assembleRelease
./android/gradlew -p android :terminal:publishReleasePublicationToMavenLocal
```

`GHOSTTY_SOURCE=/path/to/pinned/ghostty` optionally reuses a checkout. The script builds from a disposable `git archive` and does not patch that checkout. Generated native files and build outputs are ignored by Git. Gradle fails early if the native library has not been built.

Outputs:

- `android/terminal/build/outputs/aar/terminal-release.aar`
- `android/sample/build/outputs/apk/debug/sample-debug.apk`
- Local-only Maven coordinate `dev.ghostty.android:terminal:0.1.0-alpha01` (not registered or remotely published).

For another Android project, use `mavenLocal()` **for local evaluation** and `implementation("dev.ghostty.android:terminal:0.1.0-alpha01")`, or include the `android/terminal` module. The AAR includes the JNI library, consumer R8 rules, and license texts.

## Integrate

Create and use a session on one thread; when attached to a view, that must be the main thread. The caller owns the session. This sketch uses your own `transport` implementation:

```java
TerminalSession session = new TerminalSession(80, 24);
TerminalView view = new TerminalView(context);
view.setListener(new TerminalView.Listener() {
    public void onInput(byte[] bytes) { transport.write(bytes); }
    public void onResize(int columns, int rows) { transport.resize(columns, rows); }
});
view.setSession(session);
TerminalInputView input = new TerminalInputView(context);
input.setTerminalView(view);

// On ordered PTY output, on the main thread:
byte[] replies = session.write(outputBytes);
if (replies.length != 0) transport.write(replies);

// When disposing; cancel pending transport callbacks first:
input.setTerminalView(null);
view.setSession(null);
session.close();
```

Callbacks run synchronously on the session/UI thread. Do not block them. Transport implementations must preserve ordering, apply backpressure, handle failures, and decide reconnection semantics. The library never retries or queues disconnected input. A view without a listener is read-only. Selection text is returned by `getSelectedText()`; clipboard writes remain an application action.

## Unicode and limits

VT columns are authoritative. Grapheme boundaries come from the pinned Ghostty engine rather than the system ICU version. Legacy mode keeps the application's expected widths while Android shapes adjacent same-style cells as one grapheme. For example, a family emoji occupies 8 legacy columns, with unused space, versus 2 after the application explicitly enables DEC mode 2027. We do not force mode 2027 for Vim/less.

System font/glyph differences (new emoji artwork may be absent on old Android), cross-row or cross-style emoji, arbitrary long clusters, mouse reporting, accessibility, hardware dead keys and modifiers, and all IME variants are not fully supported/validated. Scrollback is currently capped at 4 MiB, pending control sequences at 64 KiB. Grid size is bounded to 1,000 per axis and 65,536 cells. Checkpoints are trusted RAM objects, not persisted/network-importable data and not a promise of cross-version compatibility.

## Tests

```sh
# Connect an explicitly chosen ARM64 Android device/emulator.
ANDROID_SERIAL=your-device ./android/gradlew -p android :terminal:connectedDebugAndroidTest
```

`./scripts/check-artifact.sh` builds a separate R8-enabled consumer against the local Maven AAR, with no project/source dependency. Its debug-signed release APK can be installed to verify JNI under shrinking.

Twenty-one instrumentation tests cover the public stream/lifecycle/IME contract, the pinned 85-case Unicode regression corpus, UTF-16 grapheme boundaries, real-window long-press selection, scrollback handle dragging, edge autoscroll and selection callbacks, cursor pixel geometry/contrast, blink timing, default resets and explicit remote steady modes. Color regressions also cover erased RGB/palette backgrounds, independent default colors, inverse and Canvas pixels. These pass on local API 27 and API 35 ARM64 emulators. Hosted CI checks builds, lint and separate AAR consumer compilation; its emulator runtime limitation is recorded in the validation notes. Device UI and artifact validation are recorded separately in [validation](VALIDATION.md). Historical prototype performance is not a benchmark of this extracted library.

## Localization

See [language resources and switching](LOCALIZATION.md) for English/Chinese strings, plural rules and validation.
