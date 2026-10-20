# Validation

The library is an extracted alpha, not a shipped package. Validation results below apply to this standalone source tree only. This page separates initial validation from later compatibility changes; it is not a statement of current CI status. See [GitHub Actions](https://github.com/JackCaow/ghostty-android/actions) for remote build results. No Maven package release has occurred.


## SSH + tmux recovery

Updated on 2026-10-21. The sample can attach to an isolated persistent tmux session via real SSH. [Reproduction and cleanup](LOCAL_SSH.md). **Physical Android acceptance is pending:** the phone disappeared from USB/ADB before installation. The earlier phone results below cover plain SSH, not this change.

- **Mac OpenSSH client:** two abrupt SSH drops retained shell PID `59117`, its exported variable and working directory. Vim PID `59192` survived the second drop; its unsaved buffer was redrawn on reattach and saved with an exact file-content comparison. [Result](evidence/ssh-tmux/mac-result.json). `scripts/verify-tmux-local.py` reproduces these assertions.
- **Android 8.1 / API 27 ARM64 emulator UI:** commands were entered through the sample composer. Two abrupt SSH drops and manual menu reconnects retained shell PID `59117`, its variable/directory and vim PID `64237`. Force-stopping/relaunching the app while vim was still unsaved also reattached to that same process. The restored text was saved through the UI and checked on the Mac. [Result](evidence/ssh-tmux/api27-result.json), [before](evidence/ssh-tmux/api27-vim-before.png), [reconnected](evidence/ssh-tmux/api27-vim-restored.png), [after app force-stop](evidence/ssh-tmux/api27-vim-after-process-death.png).
- **Disconnected draft fix:** Send retains text instead of clearing a draft when SSH is disconnected. Verified in the emulator UI. [Evidence](evidence/ssh-tmux/api27-disconnected-draft.png).
- Debug/release/instrumentation builds pass; release lint has 0 errors / 15 warnings. In tmux mode the host-key rejection instrumentation test passed and the destructive plain-shell test was **skipped**, not counted as recovery coverage. [Test output](evidence/ssh-tmux/api27-instrumentation.txt), [artifact hashes and scope](evidence/ssh-tmux/validation.json).

The injected failure kills the fixture's SSH handler processes while preserving its listener and tmux server. It is not Wi-Fi/cellular roaming or silent packet loss. Reconnection remains manual; tmux recreates the visible screen while keeping remote processes alive. Android drafts/selection/full local scrollback after process death, server reboot recovery, automatic retry and background service ownership are not provided. Existing emoji-width compatibility findings below remain open.

## Real local SSH

Validated on 2026-10-21 against a temporary loopback-only macOS OpenSSH fixture, using the Android 16/API 36 phone and Android 8.1/API 27 ARM64 emulator. [Setup and cleanup](LOCAL_SSH.md). No system Remote Login or Maven publication.

- **2/2 opt-in SSH integration tests passed on each Android version**: 80×24 PTY, window-change to 50×12, UTF-8 Chinese/emoji bytes, vim/less alternate-screen entry/exit, less navigation to line 240, Ctrl+C interruption, a new shell after disconnect, and rejection of an unknown host key. These are transport/protocol assertions, not a blanket visual TUI acceptance. [API 36](evidence/local-ssh/api36-tests.txt), [API 27](evidence/local-ssh/api27-tests.txt).
- Phone UI verified shell commands, keyboard-induced grid resize, vim direction-pad input, less ASCII paging, and selection handles on actual SSH output. Doubao Pinyin `zhongwen` stayed uncommitted until choosing “中文”; saving from vim produced `0中文01 中文输入 …` in the Mac fixture file, confirming one real IME commit reached disk. [Composing](evidence/local-ssh/phone-pinyin-composing.png), [committed](evidence/local-ssh/phone-pinyin-committed.png), [vim/pad](evidence/local-ssh/phone-vim-pad.png).
- Fixed release shrinking of JSch's reflection-loaded authentication/KEX/JCE classes with sample-only keep rules. The R8 release APK, signed locally for testing, connected and executed commands on API 27. [Release shell](evidence/local-ssh/api27-r8-shell.png). Sample debug/release/test builds pass; release lint has 0 errors and 15 warnings. [Artifact hashes and scope](evidence/local-ssh/validation.json).
- Fixed command-draft autocorrection: the API 27 Latin keyboard changed `printf` to `print` and `stty` to `St`. The draft now uses `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, matching the direct terminal input field. Re-ran the command unchanged with the final R8 APK. The final phone build also retained Chinese Pinyin candidates in the draft and executed `echo 中文` correctly: [final shell](evidence/local-ssh/phone-final-shell.png). During an earlier keyboard transition, external Trime 3.3.12 crashed in its `PreeditDelegate` with `BadTokenException`; release command checks used the system Latin IME. This run does not certify Trime stability.

### Open compatibility findings

The Mac's Vim 9.1 displays ZWJ as literal `<200d>` in the tested configuration. Less 581.2 passes the Unicode sequence through, but its host width calculation disagrees with the VT legacy mode for regional indicators:

| Text | Mac `wcwidth` sum | Ghostty legacy columns |
| --- | ---: | ---: |
| 👨‍👩‍👧‍👦 | 8 | 8 |
| 👍🏽 | 4 | 4 |
| 🇨🇳 | 2 | 4 |
| 1️⃣ | 1 | 1 |

At the phone's 44-column width, the fixture line is 43 columns to the host and 45 to VT. The unexpected wrap leaves stale characters when less updates the screen. [Failure](evidence/local-ssh/phone-less-flag-width-mismatch.png). Replacing only `🇨🇳` with `CN` removes this mismatch: [same fixture without flags](evidence/local-ssh/phone-less-no-flag.png); [ASCII control](evidence/local-ssh/phone-less-ascii.png). The script includes both comparison files. This remains an open host/terminal width-compatibility issue; this change does not silently switch all sessions to mode 2027 or alter the library's established legacy widths.

At this plain-SSH checkpoint, reconnect created a fresh shell. The optional tmux recovery section above supersedes that limitation for tmux mode. Activity destruction still closes SSH; automatic network recovery, background service ownership, long-duration performance and full Moshi UI parity remain unvalidated/unimplemented.

## Moshi-inspired sample UI

Updated on 2026-10-20 after inspecting the installed Moshi Android app. A subsequent visual alignment pass matches its compact single-line header, yellow/green header controls, shortcut spacing, rounded-square direction keys with Backspace/Ctrl+L, composer action order and half-width session thumbnails. The sample keeps a LOCAL badge and its own identity. Empty drafts disable Send; the small history button restores one of the last 20 submitted drafts held only in RAM. Voice and attachment entries are visibly unavailable until a host implements them. Opening input after resize returns the local sample to the prompt; the renderer no longer paints a debug Scrollback badge. The sample now uses a dark rounded terminal sheet, bottom shortcut capsule, draggable/lockable direction pad, draft/direct input switching, selection copy controls and a minimizable local session card with a viewport preview. The sample code and line icons are implemented here; no Moshi source or bundled assets are included. The library remains transport-independent.

- API 27 ARM64 emulator: **10/10 instrumentation tests pass**, including the new selection callback test (gesture selects text, output clears it, redundant clears do not emit duplicate callbacks). [JUnit XML](evidence/moshi-ui/tests.xml).
- Actual Trime Pinyin key taps produced “中文” in the local draft; the terminal changed only after Send. Direct-input Pinyin confirmation also echoed “中文” once. Long-press Send inserted copied “滚” without a newline, while normal Send appended Return. Keyboard show/hide resized the terminal and kept the controls visible.
- Manual gestures on API 27 verified direction-pad movement, locked position, selecting/copying Chinese text, pasting it into the draft, and minimizing/resuming with the draft preserved. The aligned UI was also checked for Pinyin draft submission, history recall, long-press Send without Return and the disabled empty-draft Send state. Selection dragging/edge scrolling remain covered by the instrumentation suite.
- Installed the updated debug sample on the API 36 phone and inspected the initial toolbar layout. The detailed interaction checks and screenshots in this section are from the API 27 emulator.
- Release AAR, sample debug/R8 release and separate R8 Maven Local consumer compile successfully. Lint: library 0 errors / 6 warnings, sample 0 errors / 8 warnings. No remote Maven publication.

Screenshots: [terminal](evidence/moshi-ui/api27-terminal.png), [direction pad](evidence/moshi-ui/api27-pad.png), [Chinese draft](evidence/moshi-ui/api27-composer.png), [direct input](evidence/moshi-ui/api27-direct-input.png), [selection](evidence/moshi-ui/api27-selection.png), [session card](evidence/moshi-ui/api27-session.png). [Build hashes and scope](evidence/moshi-ui/validation.json).

At this earlier checkpoint this was an offline interaction sample. Special/control keys show local feedback rather than controlling a real shell; Ctrl+L clears the local viewport. It has one in-memory session; Activity/process recreation resets it. SSH was not implemented at that checkpoint; the local SSH section above supersedes that limitation. Attachments, voice, remote session management and Moshi's wider settings/gesture system remain unimplemented. Hosted CI remains build-only for the emulator limitation described below.

## Android 8.1 emulator acceptance and grapheme fix

Tested on 2026-10-20 using Android Emulator 35.6.11 on macOS ARM64, Pixel 2 profile, API 27 `google_apis/arm64-v8a` revision 3, Android 8.1.0. This executes the actual old Android framework, ICU and font stack; it is not an API 36 phone running an API 27-targeted binary. [Environment, artifact hashes and results](evidence/api27-emulator/validation.json).

**Reproduced and fixed:** long-pressing the last legacy column of `👩‍❤️‍👩` selected only `👩`. The API 27 system ICU split the row's emoji sequence. Shaping/selection now obtain UTF-16 cluster boundaries from the pinned `ghostty_unicode_grapheme_width` API through JNI. VT columns, legacy mode and mode 2027 behavior are unchanged. [Failing run before the fix](evidence/api27-emulator/before-fix.txt), [fixed selection](evidence/api27-emulator/couple-selection.png).

- **9/9 instrumentation tests pass**, including the original 85 Unicode assertions, UTF-16 boundaries (empty/ASCII/surrogates/flags/long combining runs), Chinese composing/commit/cancel/delete, and real-window gestures. [JUnit XML](evidence/api27-emulator/tests.xml).
- Long-pressing the final legacy column selects the complete family, skin-tone, flag, keycap, couple, astronaut, mixed-skin holding-hands/handshake, phoenix, head-shake and combining-character sequences. These assertions verify selection boundaries, not the availability of every modern glyph in Android 8.1's font.
- Scrolling through 180 lines reveals earlier history; dragging the end handle across CJK selects the expected text. Dragging the start handle to the top edge expands selection while scrolling, and releasing stops the timer. [Scrollback selection](evidence/api27-emulator/scrollback-selection.png).
- Actual taps in [Trime 3.3.12](https://github.com/osfans/trime/releases/tag/v3.3.12), using its bundled `luna_pinyin` scheme, keep `zhongwen` uncommitted, send “中文” once on candidate selection, then cancel `ceshi` without sending it or deleting committed content. [Composing](evidence/api27-emulator/composing.png), [committed](evidence/api27-emulator/committed.png), [cancelled](evidence/api27-emulator/cancelled.png). The IME is installed only in the test emulator; no IME binary/dependency is bundled in this library.
- The sample grid changes **49×27 ↔ 49×11** as the keyboard hides/shows; the input stays visible using `adjustResize`. [Legacy/cluster render](evidence/api27-emulator/unicode.png).
- Release AAR, sample debug/R8 release, instrumentation APK and independent R8 MavenLocal consumer build. The consumer runs on API 27 and displays [AAR smoke OK](evidence/api27-emulator/consumer.png). Library/sample lint remain at 0 errors (6/14 warnings). No remote Maven publication occurs.
- Hosted CI checks native/AAR/sample/test APK builds, lint and separate R8 AAR consumer compilation. Runtime instrumentation remains a local emulator check: Android Emulator 37.2.12 on the hosted `macos-14` ARM runner failed before tests with `HVF error: HV_UNSUPPORTED`, both with [default acceleration](https://github.com/JackCaow/ghostty-android/actions/runs/39444104562) and with [`-accel off`](https://github.com/JackCaow/ghostty-android/actions/runs/39445213832). That flag did not prevent HVF initialization on the hosted runner, so the unsupported emulator CI step was removed. The 9/9 result above comes from the local API 27 emulator; Pinyin candidate taps were performed manually with Trime.

To reproduce on an ARM64 Mac with the build prerequisites installed:

```sh
sdkmanager 'system-images;android-27;google_apis;arm64-v8a'
echo no | avdmanager create avd -n Ghostty_API27 -k 'system-images;android-27;google_apis;arm64-v8a' -d pixel_2
emulator -avd Ghostty_API27 -port 5580 -no-snapshot -gpu swiftshader_indirect
# In a second terminal, after Android has booted:
ANDROID_SERIAL=emulator-5580 ./scripts/verify.sh
./scripts/check-artifact.sh
```

The test-only activity and screenshots are not included in the release AAR. Android 8.1 physical devices, other IMEs, missing modern font glyphs and long-duration performance remain outside this run's acceptance.

## Historical prototype evidence (not library acceptance)

The preceding Android prototype on Android 16 / model 25019PNF3C passed 85 Unicode checks and a ten-minute synthetic load: 36,000 ticks, 583/583 PTY probes, no errors/timeouts. Native allocated heap in the second half stayed around 72.14–72.19 MiB. Input-to-frame-commit P95 was 204.2 ms with a 160 ms HTTP polling transport. Canvas P95 was 10 ms. That harness, transport and timing instrumentation are not included here, so these are not performance claims for this extracted AAR.

## Initial standalone library validation (API 31 build)

Validated locally on 2026-10-20 with macOS ARM64, JDK 17, Gradle wrapper 8.11.1, Zig 0.16.0 and NDK 28.2.13676358. Physical device: Android 16 / API 36, model 25019PNF3C, Doubao IME. These initial artifacts used an API 31 build minimum; the API 27 update below supersedes that build configuration.

- Release AAR, debug example, R8 release example and test APK build successfully. Local Maven publication succeeds. No public package upload occurred.
- Four instrumentation tests pass on the physical phone, including all 85 Unicode assertions. Public coverage includes split UTF-8 checkpoint recovery, query replies, application cursor keys, resize, thread ownership, closed/attached resource protection, and IME commit/cancel/delete callbacks. [Runner output](evidence/instrumentation.txt).
- A completely separate application builds against the Maven AAR (no source/project dependency), with R8 enabled. It runs on the phone and reports **AAR smoke OK**, exercising JNI, Unicode selection and checkpoint restore. [Screenshot](evidence/consumer.png).
- The example shows complete legacy/cluster emoji, copies a whole family emoji even from its legacy blank columns, and copies keycap VS16 exactly. [Selection assertions](evidence/selection.json), [Unicode screenshot](evidence/unicode.png).
- Actual taps on the Doubao Pinyin keyboard confirm uncommitted candidates remain local, choosing “中文” emits it once, and cancelling the next candidate leaves the terminal unchanged. [Composing](evidence/composing.png), [committed](evidence/committed.png), [cancelled](evidence/cancelled.png).
- Keyboard changes the example grid 46×35 ↔ 46×18. Android edge-to-edge IME insets are applied by the host example. Saving/restoring the opaque RAM checkpoint returns the previous display. [Restored](evidence/restored.png). This is not a test of remote reconnection.
- AAR inspection confirms one ARM64 JNI library, consumer keep rules and all six license/notice entries. ELF LOAD alignment is 16 KiB; this inspection alone is not device validation on a 16 KiB-page OS.
- Lint: **0 errors, 6 warnings**. Remaining warnings concern pinned test-dependency updates (2), ARM64-only support (1), allocations during drawing (2), and touch `performClick` accessibility integration (1). These are retained openly; accessibility and render allocation optimization remain alpha limitations.

Build/artifact hashes and the exact result boundary are in [validation.json](evidence/validation.json). The evidence JSON predates the first GitHub push and records no remote CI run at that time. The first automated device install was blocked by the phone's USB-install confirmation, and the original test runner lacked a test-only Kotlin runtime; after confirming installation and adding that test dependency, the actual four-test run passed. Product runtime dependencies remain empty.

## API 27 compatibility update

The library, sample, and separate AAR consumer now declare Android 8.1 / API 27 as their minimum. Both Zig (`aarch64-linux-android.27`) and the NDK JNI compiler (`aarch64-linux-android27-clang`) explicitly target API 27. The native link rejects unresolved symbols. The sample uses decor fitting and `adjustResize` below API 30, and guards its modern system-bar/IME insets code at API 30.

- Final configuration builds the release AAR, debug/release sample, test APK, and separate R8 consumer against Maven Local. All three application APKs and the AAR declare minimum API 27. No remote Maven publication occurred.
- ELF Android API note is 27. All 37 imported dynamic symbols are available in the NDK's API 27 libc/libm stubs. This is a link compatibility check, not an Android 8.1 runtime test.
- Library lint: 0 errors / 6 warnings; sample lint: 0 errors / 14 warnings. The verification script now includes sample lint to catch unguarded newer Android APIs.
- The final API 27-targeted build passes all four instrumentation tests on the API 36 phone (model 25019PNF3C), including 85 Unicode assertions, stream/checkpoint/query/key behavior, ownership, and IME composing/commit/delete callbacks. Initial installation was blocked by the USB confirmation; the successful retry used the same final artifacts. [Runner output](evidence/api27-instrumentation.txt).
- The separate R8-enabled MavenLocal AAR consumer runs on the API 36 phone and displays **AAR smoke OK**, verifying JNI loading, Unicode selection and checkpoint restore with the final artifact.
- At this earlier checkpoint, no API 27 device/emulator had been exercised. The emulator acceptance section above supersedes that limitation for the tested scenarios.

Current artifact hashes and compatibility checks are recorded in [api27-validation.json](evidence/api27-validation.json). The original screenshots and `validation.json` above describe the earlier API 31 artifacts.

## Unvalidated scope

Linux build hosts, x86/32-bit ABIs, physical Android 8.1 devices, untested IMEs, process-death persistence, production transport/reconnection, hardware keyboard layout/dead-key coverage, accessibility, GPU/presentation latency and long-duration performance of the extracted library are not certified.
