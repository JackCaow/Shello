# Validation

The library is an extracted alpha, not a shipped package. Validation results below apply to this standalone source tree only. This page separates initial validation from later compatibility changes; it is not a statement of current CI status. See [GitHub Actions](https://github.com/JackCaow/ghostty-android/actions) for remote build results. No Maven package release has occurred.

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
- An intermediate build passed all four instrumentation tests on the API 36 phone. After explicitly lowering the Zig target to API 27, the final instrumentation install was blocked with `INSTALL_FAILED_USER_RESTRICTED`; no final-build device test pass is claimed.
- No API 27 device/emulator has been exercised. Old-system ICU/font rendering, complex emoji selection, and IME/window resize still need runtime acceptance.

Current artifact hashes and compatibility checks are recorded in [api27-validation.json](evidence/api27-validation.json). The original screenshots and `validation.json` above describe the earlier API 31 artifacts.

## Unvalidated scope

Linux build hosts, x86/32-bit ABIs, older Android, other IMEs, process-death persistence, production transport/reconnection, hardware keyboard layout/dead-key coverage, accessibility, GPU/presentation latency and long-duration performance of the extracted library are not certified.
