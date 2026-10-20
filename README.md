# ghostty-android

Experimental Android terminal library: **libghostty-vt + native Canvas rendering + touch selection + IME input**. Unofficial; not affiliated with the Ghostty project. No published release or Maven Central coordinates yet.

[Source repository](https://github.com/JackCaow/ghostty-android) · [中文说明](README.zh-CN.md) · [API contract](docs/API.md) · [Validation](docs/VALIDATION.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

## Scope

- `TerminalSession`: ordered VT byte stream, key encoding, query replies, scrollback, and opaque in-memory checkpoints.
- `TerminalView`: colors/styles, cursor, grapheme shaping, long-press selection, two handles, and edge autoscroll.
- `TerminalInputView`: optional direct-input bridge. Composing text stays local; committed text is sent as UTF-8.
- `sample`: offline echo by default, with an optional real SSH shell using mwiede/JSch. A USB setup script connects to a temporary loopback-only OpenSSH server on your Mac. No business/account integration; the terminal AAR stays transport-independent.

```text
PTY / SSH / WebSocket (your application)
       │ ordered output       ▲ input + VT replies + resize
       ▼                      │
TerminalSession ─────── TerminalView
                           ▲
                    TerminalInputView
```

Android **8.1+ (API 27) / arm64-v8a only**, Java 17. Runtime regression covers an Android 8.1 / API 27 ARM64 emulator and the earlier API 36 phone checks; see the versioned [validation evidence](docs/VALIDATION.md). This is an alpha API. Ghostty is pinned to `b58ef1d678be2747c830bc1b1e6c0a086afecf9a`, with the documented [keycap-selector patch](patches/README.md). Graphics protocols are disabled. Android Canvas is used; this does not embed the Ghostty desktop renderer.

## Sample UI

The sample uses a Moshi-inspired mobile terminal layout (visual parity is still in progress): a dark rounded session sheet, a compact Ctrl/Esc/Tab toolbar, a draggable/lockable direction pad, direct IME input and a separate text draft. Tap Send to append Return; long-press Send for text only. Paste opens the local draft, and long-press selection exposes Copy. The history button restores recent drafts from RAM. Voice and attachment entries are disabled in this prototype. Minimize opens a half-width local session card with a viewport preview; resume retains the same in-memory session and draft. Control keys show feedback in offline mode and operate the real PTY in SSH mode. See [local SSH setup and limitations](docs/LOCAL_SSH.md). UI components live in `sample`, separate from the embeddable terminal library.

[UI screenshots and validation](docs/VALIDATION.md#moshi-inspired-sample-ui)

## Build

Install JDK 17, Android SDK platform 35, NDK `28.2.13676358`, and Zig **0.16.0**. The wrapper pins Gradle 8.11.1. Native source and dependencies are downloaded from public upstream repositories on the first build. Native builds currently target ARM64 Android from macOS or Linux x86_64 hosts; see validation for actually tested hosts.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ZIG_BIN=/path/to/zig-0.16.0/zig
./scripts/build-native.sh
./gradlew :terminal:assembleRelease :sample:assembleDebug :sample:assembleRelease
./gradlew :terminal:publishReleasePublicationToMavenLocal
```

`GHOSTTY_SOURCE=/path/to/pinned/ghostty` optionally reuses a checkout. The script builds from a disposable `git archive` and does not patch that checkout. Generated native files and build outputs are ignored by Git. Gradle fails early if the native library has not been built.

Outputs:

- `terminal/build/outputs/aar/terminal-release.aar`
- `sample/build/outputs/apk/debug/sample-debug.apk`
- Local-only Maven coordinate `dev.ghostty.android:terminal:0.1.0-alpha01` (not registered or remotely published).

For another Android project, use `mavenLocal()` **for local evaluation** and `implementation("dev.ghostty.android:terminal:0.1.0-alpha01")`, or include the `terminal` module. The AAR includes the JNI library, consumer R8 rules, and license texts.

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
ANDROID_SERIAL=your-device ./gradlew :terminal:connectedDebugAndroidTest
```

`./scripts/check-artifact.sh` builds a separate R8-enabled consumer against the local Maven AAR, with no project/source dependency. Its debug-signed release APK can be installed to verify JNI under shrinking.

Ten instrumentation tests cover the public stream/lifecycle/IME contract, the pinned 85-case Unicode regression corpus, UTF-16 grapheme boundaries, real-window long-press selection, scrollback handle dragging, edge autoscroll and selection callbacks. These pass on a local API 27 ARM64 emulator. Hosted CI checks builds, lint and separate AAR consumer compilation; its emulator runtime limitation is recorded in the validation notes. Device UI and artifact validation are recorded separately in [validation](docs/VALIDATION.md). Historical prototype performance is not a benchmark of this extracted library.

## License

MIT, with retained source copyright and third-party notices. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Source is hosted at [JackCaow/ghostty-android](https://github.com/JackCaow/ghostty-android). No Maven package has been remotely published; CI uses Maven Local only to test the packaged AAR.
