# Shello

Shello was previously named `ghostty-android`. The Android application ID, Java API packages, pairing protocol and existing connection storage remain unchanged for upgrade compatibility. The terminal engine is still Ghostty; Shello is an independent project.

Experimental Android terminal library: **libghostty-vt + native Canvas rendering + touch selection + IME input**. Unofficial; not affiliated with the Ghostty project. Installable alpha APKs are available on [GitHub Releases](https://github.com/JackCaow/Shello/releases). No Maven Central coordinates are published.

[Source repository](https://github.com/JackCaow/Shello) · [中文说明](README.zh-CN.md) · [API contract](docs/API.md) · [Validation](docs/VALIDATION.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

[Direct LAN testing and OEM battery-policy limitations](docs/DEVICE_RECOVERY.md)

## Scope

- `TerminalSession`: ordered VT byte stream, key encoding, query replies, scrollback, and opaque in-memory checkpoints.
- `TerminalView`: colors/styles, cursor, grapheme shaping, long-press selection, two handles, and edge autoscroll.
- `TerminalInputView`: optional direct-input bridge. Composing text stays local; committed text is sent as UTF-8.
- `sample`: offline echo by default, with an optional real SSH shell using mwiede/JSch. A USB setup script connects to a temporary loopback-only OpenSSH server on your Mac; optional `--tmux` preserves the remote shell and running programs across automatic SSH reconnects after transport failures. A notified foreground service owns independent SSH/VT sessions across backgrounding and Activity recreation. Long-press a session thumbnail to reorder, close it, or close all sessions; saved connection profiles remain available. After process death, normal app reopen reattaches the previously connected tmux profile; explicit disconnect or changed credentials/profile cancels that intent. Notification denial does not block SSH, and automatic recovery never prompts for notification permission; system-rejected foreground-service starts are handled without crashing. No business/account integration; the terminal AAR stays transport-independent.

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

The sample uses a Moshi-inspired mobile terminal layout (visual parity is still in progress): a dark rounded session sheet, a compact Ctrl/Esc/Tab toolbar, a draggable/lockable direction pad, direct IME input and a separate text draft. Tap Send to append Return; long-press Send for text only. Paste opens the local draft, and long-press selection exposes Copy. The history button restores recent drafts from RAM. Voice and attachment entries are disabled in this prototype. Minimize opens a half-width local session card with a viewport preview; resume retains the same in-memory session and draft. Session thumbnails persist privately on-device across process death and upgrades, with a 24 MiB disk limit. Visible terminals checkpoint every five seconds and on minimize/background; closing a tab removes its thumbnail. A cached image is the last captured view, not a live connection indicator. Control keys show feedback in offline mode and operate the real PTY in SSH mode. See [local SSH setup and limitations](docs/LOCAL_SSH.md). UI components live in `sample`, separate from the embeddable terminal library.

[Local SSH auto-discovery](docs/LAN_SSH_DISCOVERY.md): the sample scans the current Wi-Fi subnet, displays a server-count entry and a bottom-sheet picker, then fills a connection draft. Continue with a username and password or imported private key (including passphrase-protected keys), verify the server fingerprint, then save and connect. Credentials are encrypted with Android Keystore. Tap saved profiles to connect; long-press for the Moshi-style Edit/Delete menu. Edits verify authentication before atomically replacing the encrypted profile; optional tmux preserves remote sessions. See the [connection workflow](docs/LOCAL_SSH.md#create-a-connection-in-the-app).

[UI screenshots and validation](docs/VALIDATION.md#moshi-inspired-sample-ui)

## Install and pair

Download the signed Android 8.1+ **arm64-v8a** APK from [Releases](https://github.com/JackCaow/Shello/releases). On your SSH-enabled Mac/Linux computer with Python 3.9+, OpenSSL and tmux, run:

```sh
curl -fsSL https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.sh | bash
```

Then open **+ → 扫码轻松配对** in Shello. See [pairing options](docs/HOME_AND_AGENTS.md#pairing) and [APK signing/upgrade notes](docs/RELEASING.md).

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

Twenty-one instrumentation tests cover the public stream/lifecycle/IME contract, the pinned 85-case Unicode regression corpus, UTF-16 grapheme boundaries, real-window long-press selection, scrollback handle dragging, edge autoscroll and selection callbacks, cursor pixel geometry/contrast, blink timing, default resets and explicit remote steady modes. Color regressions also cover erased RGB/palette backgrounds, independent default colors, inverse and Canvas pixels. These pass on local API 27 and API 35 ARM64 emulators. Hosted CI checks builds, lint and separate AAR consumer compilation; its emulator runtime limitation is recorded in the validation notes. Device UI and artifact validation are recorded separately in [validation](docs/VALIDATION.md). Historical prototype performance is not a benchmark of this extracted library.

## License

MIT, with retained source copyright and third-party notices. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Source is hosted at [JackCaow/Shello](https://github.com/JackCaow/Shello). No Maven package has been remotely published; CI uses Maven Local only to test the packaged AAR.

### Home and agent sessions

The sample includes a remote Tmux / Recent agent picker, QR-assisted SSH key pairing, independent settings, and a Claude Code Hook inbox. Recent groups Claude Code / Codex history by project and resumes the selected session in its original directory. See [protocols, setup and validation boundaries](docs/HOME_AND_AGENTS.md). These features are sample-only.
