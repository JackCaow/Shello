# Signed APK acceptance probe

This optional, test-only instrumentation APK targets the installed Shello APK. It uses public Android APIs and has no dependency on application classes or R8 names. It is not compiled into Shello and is never a release asset.

Use a **disposable ARM64 emulator only**. Sign the probe with the same existing release certificate as the APK under test. Do not uninstall or replace an existing development-phone installation. Private signing inputs stay outside the repository.

```sh
JAVA_HOME=/path/to/jdk17 scripts/release-probe/build.sh
adb -s emulator-SERIAL install build/stable-release/probe/probe.apk
adb -s emulator-SERIAL forward tcp:38765 tcp:38765
adb -s emulator-SERIAL shell am instrument -w \
  dev.shello.releaseprobe/dev.shello.releaseprobe.Probe
```

The runner binds a temporary loopback socket on the emulator; it accepts one JSON object per line and returns one JSON object. Supported operations:

- `launch`: open Shello's normal launcher Activity.
- `tree`: inspect accessibility text, descriptions, bounds and control state.
- `text`, `match`, `value`: set an editable field by its exact accessibility description or text. This is injected input, not physical IME acceptance.
- `snapshot`: SHA-256 of existing preferences, files and preview storage. No file contents or credentials are returned. Save before/after results privately for upgrade comparisons.
- `memory`: current process PSS, native allocation and Java heap use. Instrumentation adds overhead; report the environment with measurements.
- `ime`: inspect the focused editor’s public input type and composition bounds. Optional `value` commits test text through its public InputConnection. `inputType` is a diagnostic override for the current view only; do not use an override for final acceptance. No focused editor returns a diagnostic error without crashing the target.
- `clipboard`, `value`: set test clipboard content while the app is focused.
- `finish`: end instrumentation.

The probe does not provision profiles, skip host verification, invoke private application methods, grant agent approval, change trust, or simulate a successful upload. Business acceptance still uses actual application controls and an isolated real SSH/tmux host. For process-death recovery, first finish the probe, kill/reopen Shello through its normal launcher, and restart the probe only after independently observing recovery.

After testing, remove the ADB forwarding and delete the disposable AVD. Keep snapshots, QR codes, endpoint details and credentials out of Git. Only sanitized pass/fail results belong in release evidence.
