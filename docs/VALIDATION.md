# Validation

The library is an extracted alpha, not a shipped package. Validation results below apply to this standalone source tree only. This page separates initial validation from later compatibility changes; it is not a statement of current CI status. See [GitHub Actions](https://github.com/JackCaow/ghostty-android/actions) for remote build results. No Maven package release has occurred.

## Android 8.1 emulator acceptance and grapheme fix

Tested on 2026-10-20 using Android Emulator 35.6.11 on macOS ARM64, Pixel 2 profile, API 27 `google_apis/arm64-v8a` revision 3, Android 8.1.0. This executes the actual old Android framework, ICU and font stack; it is not an API 36 phone running an API 27-targeted binary. [Environment, artifact hashes and results](evidence/api27-emulator/validation.json).

**Reproduced and fixed:** long-pressing the last legacy column of `👩‍❤️‍👩` selected only `👩`. The API 27 system ICU split the row's emoji sequence. Shaping/selection now obtain UTF-16 cluster boundaries from the pinned `ghostty_unicode_grapheme_width` API through JNI. VT columns, legacy mode and mode 2027 behavior are unchanged. [Failing run before the fix](evidence/api27-emulator/before-fix.txt), [fixed selection](evidence/api27-emulator/couple-selection.png).

- **9/9 instrumentation tests pass**, including the original 85 Unicode assertions, UTF-16 boundaries (empty/ASCII/surrogates/flags/long combining runs), Chinese composing/commit/cancel/delete, and real-window gestures. [JUnit XML](evidence/api27-emulator/tests.xml).
- Long-pressing the final legacy column selects the complete family, skin-tone, flag, keycap, couple, astronaut, mixed-skin holding-hands/handshake, phoenix, head-shake and combining-character sequences. These assertions verify selection boundaries, not the availability of every modern glyph in Android 8.1's font.
- Scrolling through 180 lines reveals earlier history; dragging the end handle across CJK selects the expected text. Dragging the start handle to the top edge expands selection while scrolling, and releasing stops the timer. [Scrollback selection](evidence/api27-emulator/scrollback-selection.png).
- Actual taps in [Trime 3.3.12](https://github.com/osfans/trime/releases/tag/v3.3.12), using its bundled `luna_pinyin` scheme, keep `zhongwen` uncommitted, send “中文” once on candidate selection, then cancel `ceshi` without sending it or deleting committed content. [Composing](evidence/api27-emulator/composing.png), [committed](evidence/api27-emulator/committed.png), [cancelled](evidence/api27-emulator/cancelled.png). The IME is installed only in the test emulator; no IME binary/dependency is bundled in this library.
- The sample grid changes **49×27 ↔ 49×11** as the keyboard hides/shows; the input stays visible using `adjustResize`. [Legacy/cluster render](evidence/api27-emulator/unicode.png).
- Release AAR, sample debug/R8 release, instrumentation APK and independent R8 MavenLocal consumer build. The consumer runs on API 27 and displays [AAR smoke OK](evidence/api27-emulator/consumer.png). Library/sample lint remain at 0 errors (6/14 warnings). No remote Maven publication occurs.
- CI boots an API 27 ARM64 emulator with CPU acceleration disabled (hosted ARM Macs do not expose nested HVF virtualization) and runs the instrumentation suite, including the emoji regression. CI does not install Trime or claim to automate the manual Pinyin candidate taps.

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
