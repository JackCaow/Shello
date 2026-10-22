# Shello for iOS

Native iOS / iPadOS port of the Android app (iOS 16+, arm64 device and Simulator). Swift + SwiftUI + UIKit; the VT engine is the same pinned Ghostty `libghostty-vt`, SSH is libssh2 + OpenSSL. The computer side (`companion/`, `skills/shello-pair`, `ghostty_hook.py`) is shared unchanged, so one host serves both apps.

## Layout
| Path | Purpose |
|---|---|
| `public/native/shello_vt.{c,h}` | Platform-neutral C wrapper over libghostty-vt (port of the JNI file) |
| `scripts/build-native-ios.sh`, `build-ssh-ios.sh`, `assemble-ios-frameworks.sh` | Build `ios/Frameworks/ShelloNative.xcframework` (device + simulator) with Zig 0.16.0, OpenSSL 3.4.1, libssh2 1.11.1 (checksums pinned) |
| `ios/Sources/ShelloTerminal` | `TerminalSession`: thread-confined VT engine, key/mouse/paste encoders, checkpoints |
| `ios/Sources/ShelloTerminalUI` | `TerminalView`: Core Graphics renderer, cursor/blink, wide + emoji cells, grapheme shaping, selection with handles, gestures, soft + hardware keyboard |
| `ios/Sources/ShelloSSH` | `SSHConnection` (strict known_hosts pins, password / RSA / Ed25519 / encrypted keys), `ShellChannel` (PTY pump, resize, keepalive), `SFTPClient` |
| `ios/Sources/ShelloCore` | Session targets, tmux parsing, pairing ticket, profiles + secret store, shortcut grammar, input preferences |
| `ios/Sources/ShelloServices` | Keychain store, pairing (pinned TLS), `SessionManager` (tabs, reconnect, restore), connection verification, `AgentHub`, attachments, LAN/Bonjour/Tailscale discovery |
| `ios/Shello` | SwiftUI app; `Localizable.xcstrings` is generated from the Android strings by `scripts/android-strings-to-xcstrings.py` (same keys, en + zh-Hans) |

## Build and verify

Requires macOS with Xcode and an installed iOS Simulator runtime, XcodeGen, tmux, CMake, Python 3.9+, and Zig 0.16.0. Generated Xcode projects, native XCFrameworks and build output are ignored; regenerate them after cloning.

```sh
export ZIG_BIN=/path/to/zig-0.16.0/zig
scripts/build-native-ios.sh && scripts/build-ssh-ios.sh && scripts/assemble-ios-frameworks.sh
scripts/verify-ios.sh            # needs a booted iOS Simulator and xcodegen
```
`verify-ios.sh` starts an unprivileged sshd on 127.0.0.1:2299 and the real `companion/pair.py`, then runs: localization check, all Swift package tests, hosted app tests, the XCUITest flows, and an unsigned Release build for devices. To run on a device set a development team in Xcode (`ios/project.yml` leaves signing automatic).

## Validation recorded on 2026-10-22

The port is a source preview, not an iOS release. The earlier simulator and device runs below cover the initial port. Later focused checks cover the navigation, discovery and Agent Box changes; they do not establish camera pairing or hardware keyboard acceptance.

### Initial port (iOS Simulator, iOS 26)
| # | Area | Android reference | Evidence | Status |
|---|---|---|---|---|
| 1 | Engine | `libghostty_android.so` | xcframework for device / sim arm64 / sim x86_64; 12 engine tests (cells, colors, wide, DSR replies, keys, paste, scroll, selection, checkpoint, mouse, graphemes) | done |
| 2 | Terminal view | `TerminalView` | 11 view tests (render pixels, grid resize, font clamp, input bytes, modifiers, selection, alt-screen, cursor, session swap); screenshot reviewed | done |
| 3 | Input | `TerminalInputView` | soft keyboard via UIKeyInput, hardware keys via UIPress (arrows, F-keys, Ctrl/Alt/Shift, Option-as-Meta), bracketed paste; UI test types commands and Ctrl+C | done |
| 4 | SSH transport | `SshTransport` | 10 integration tests vs real sshd: pin / mismatch, Ed25519, RSA, encrypted key, wrong user, refused, exec + stdin, SFTP, PTY resize, tmux reattach. Password auth (correct, wrong, first-contact trust, command execution) against a paramiko test server | done |
| 5 | Profiles / secrets | `SshProfileStore` | Keychain (`AfterFirstUnlockThisDeviceOnly`) round trip in hosted tests; metadata file with data protection | done |
| 6 | Sessions / tabs | `SshSessionService` | multi-tab, restore order, backoff auto-reconnect (2, 4, 8, 16, 30 s), foreground reconnect, tmux redraw after reconnect; UI test closes a tab and re-attaches the same tmux session | done |
| 7 | tmux picker | `TmuxPicker` | list / new / attach / plain shell; UI tests | done |
| 8 | Pairing | `PairingActivity` | end-to-end with `companion/pair.py`: RSA-3072 key, pinned TLS, key install, token replay and wrong pin rejected, then SSH with the new key; UI test completes pairing to a shell. Camera scanning itself is not exercised on the Simulator (payload injected); image import uses CIDetector, untested | done* |
| 9 | Discovery | `LanSshDiscovery`, Tailscale | banner probe, /24 bounds, Tailscale JSON parsing and peer lookup over SSH from a saved host, real Bonjour `_ssh._tcp` advertise + browse on the Simulator; Local Network permission strings in Info.plist. The permission prompt and a live tailnet need a device | done* |
| 10 | Agent hub | `AgentHub`, `AgentInbox` | state machine tests; real hook script over SSH: install, poll, respond, uninstall, isolated from the developer's Claude / Codex settings | done |
| 11 | Attachments | `AttachmentTransfer` | SFTP upload exact bytes, 0600 files in a private random folder, no partial file, 50 MiB limit, bad pin; PHPicker / files / camera sources wired | done* |
| 12 | Settings | Settings*Activity | theme, language, font, advanced, hook, notifications, draft, hardware keyboard, toolbar editor, shortcuts + custom buttons + prefixes, gesture mapping | done* |
| 13 | Localization | `strings.xml` en / zh-CN | 617 keys, both languages, format arguments verified, every key used by Swift exists (`check-ios-localization.py`); in-app language switch tested | done |
| 14 | iPad | `AdaptiveLayout` | the whole UI flow suite passes on an iPad simulator (screenshot reviewed: full-width terminal, toolbar, keyboard); adaptive grids and all orientations | done |
| 15 | Release | `docs/RELEASING.md` | unsigned Release build for arm64 devices passes (9 MB); signing, TestFlight and App Store review not performed | partial |

`verify-ios.sh` runs the package tests, the hosted app tests (Keychain, resources, QR image decode, Bonjour), the UI flows on iPhone and on iPad, and the unsigned Release build; all passed.

### Initial real-device flows (iPhone 17, iOS 26.6.1)
Signed with the personal development team and installed with `xcodebuild ... -allowProvisioningUpdates`. The UI flow suite ran on the phone against the Mac's sshd and `pair.py` over Wi-Fi: saved connection and commands, tmux re-attach, composer / Ctrl, manual form with the first-contact fingerprint prompt, pairing end to end (the real Local Network permission prompt appeared and was accepted), settings, session overview, and a test that the QR scanner opens the real camera preview. Running on hardware found one real bug (the keyboard covered the form's Save button; fixed with a keyboard Done button and interactive dismissal). After the final UI alignment (home, terminal chrome, composer, toolbar editor, shortcuts, gestures, Zen ring) the whole suite, 14 flows including the settings sub-pages, was run again on the phone and passed (an earlier attempt failed only because the phone was locked: "Timed out while enabling automation mode"). Simulator suites (iPhone, iPad) and the unsigned Release build passed on the same build.

## UI alignment with Android
Reference: `docs/design/UI.md` and the screenshots under `docs/evidence/moshi-ui`. Matched: palette (`#161719` surfaces, `#111315` fields, `#303337` borders, `#00D86C` accent), home backdrop glows, round header buttons, dashed empty cards, Sessions tiles with preview and `›_ host` line, Connections cards, "Discover Shello n/6" tiles, green add button, terminal sheet with grab handle, yellow minimize and green sessions buttons and SSH chip, pill toolbar with round mono keys and bare history / chat / keyboard icons, black composer pill (plus, shortcuts, close, mic, send), grouped settings cards with icons and values, session overview tiles. The settings sub-pages follow the accepted Android designs (live toolbar preview with Toolbar / Zen switch, enabled / available shortcut groups, per-gesture action pickers, icon-modifier toggle, Zen ring with double-tap-and-hold). Platform differences include iOS system fonts and switches and native sheet transitions. Toolbar and session ordering use drag handles.

## Known differences from Android (platform, not bugs)
- iOS has no foreground service or wake lock. SSH sockets are suspended in the background; the app relies on tmux and reconnects on foreground. Agent events and notifications therefore update while the app is open; there is no push relay (keeps the "no account, no server" design).
- The sidebar action opens a tools sheet. Dictation uses the system keyboard microphone; the in-app action reports that it is unavailable.
- Marked-text IME composition (e.g. pinyin) goes through the text draft composer, as on Android; the raw terminal view takes committed characters.
- The pairing QR uses RSA-3072 keys because the shared pairing server accepts only `ssh-rsa`.

## Focused navigation and Agent Box checks

On the physical iPhone, the latest fixes were installed over the app without deleting normal profiles or settings. Test profiles and hook configuration were isolated from normal app data and the computer's agent settings.

- All nine visible settings pages, a nested toolbar editor, cancelled short edge swipes, root-page swipes followed by reopening, and returning from the terminal content area to the originating inbox were verified. Terminal pans defer to native interactive back, and cancelled pans do not execute configured shortcuts. An unsaved connection form resisted pull-down dismissal and showed the discard confirmation when closed.
- Real Claude Code 2.1.19 and Codex 0.163.0-alpha.10.2 commands produced the expected final replies. Both replies, provider filtering, per-provider usage and resuming each original tmux session were verified on the phone.
- Approval allow / deny and a two-question Chinese / emoji answer round trip were verified through isolated hook-protocol producers. These are protocol checks, not acceptance of an actual interactive CLI approval prompt. Incomplete answers remain disabled and successful replies show confirmation in the detail sheet.
- Hook registration now accounts for older Claude installations sharing settings. Missing legacy Stop reply payloads fall back to a bounded tail of that session's transcript without retaining the transcript path or thinking blocks.
- The focused iOS model regression passed 10 tests; the Python hook suite passed 18 tests. CI additionally runs the full iOS harness and the shared host tests.

## CI

`.github/workflows/build.yml` checks Android builds/lint and the AAR consumer, shared host and Windows pairing tests, and an iPhone Simulator iOS job. The iOS job builds the native XCFramework, checks the shared localization catalog, runs package, app and UI tests against isolated SSH/pairing fixtures, and compiles an unsigned device Release build. It does not sign or distribute an iOS app. Local verification also runs on an available iPad Simulator; set `SHELLO_SKIP_IPAD=1` for an iPhone-only run.

## Not verified

Physical iPad, a physical hardware keyboard, live camera QR decoding through final pairing, a live tailnet from iOS, Windows / WSL2 pairing from iOS, interactive Claude/Codex approval prompts, TestFlight/App Store distribution and encryption-export self-classification. The Local Network prompt and starting the physical camera were observed in initial device runs, but these do not prove every permission/reinstallation path. `ITSAppUsesNonExemptEncryption` is currently NO and must be reviewed before store distribution.
