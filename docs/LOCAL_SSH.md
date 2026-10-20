# Local SSH sample

The sample can run a real SSH PTY against your Mac using mwiede/JSch 2.28.8. The terminal AAR has no SSH dependency. This is a development fixture, not the full remote-session product flow.

## Run

Build/install the debug sample, unlock an authorized Android device, then run:

```sh
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r sample/build/outputs/apk/debug/sample-debug.apk
python3 scripts/local-ssh.py start --serial DEVICE_SERIAL
```

The script starts `/usr/sbin/sshd` as your user on **127.0.0.1:22222**, creates fresh ECDSA host/client keys under `~/.cache/ghostty-android-ssh-test` (directory mode 0700), and uses `adb reverse` to reach that listener from the device. It does not enable system Remote Login or edit your existing SSH keys/configuration. The fixture authenticates only the generated key; password authentication, agent forwarding and TCP forwarding are disabled.

The private client key, connection profile and pinned `known_hosts` are streamed into the debug app's private `files/ssh/` directory with mode 0600; nothing is bundled in the APK or repository. Host key verification is mandatory. The shell runs as your actual Mac user in the fixture's `workspace` directory, with a clean Bash startup and history disabled. Commands can access files available to that user; this is not a sandbox.

Try:

```sh
stty size
vim -Nu NONE -n unicode.txt
less -R unicode.txt
# Width comparison for the known macOS flag-width mismatch:
less -R unicode-no-flag.txt
```

Direct keyboard input, Ctrl combinations, Escape, Tab, direction keys and submitted drafts go to SSH. Output goes to the VT engine on the UI thread, terminal replies go back to the PTY, and view/keyboard resizes send SSH window-change requests. The transport bounds pending output to four 16 KiB chunks and queued writes to 128 operations. Offline output-injection/checkpoint tools are disabled while in SSH mode.

Use **会话工具 → 断开 SSH / 重新连接本机 SSH / 返回离线演示**. Reconnect opens a **new shell**, clears the local VT, and does not replay disconnected input. It does not resume a previous remote process. Minimizing retains the live connection only while this Activity lives; Activity recreation/process death closes it. The `connectLocalSsh` launch extra is honored only by debug builds.

## Verify and stop

With the fixture still running, install the instrumentation APK and run:

```sh
adb -s DEVICE_SERIAL install -r sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  dev.ghostty.android.sample.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/local-ssh.py stop --serial DEVICE_SERIAL
```

The two integration tests are opt-in: without a private profile they are **skipped**, not runtime acceptance. With the fixture they check PTY dimensions, resize, Unicode bytes, vim/less alternate screens, Ctrl+C, fresh reconnection and rejection of an unknown host key. Tests run against independent SSH sessions and leave the fixture file unchanged.

`stop` terminates this fixture listener, removes the USB reverse mapping, force-stops the sample and removes its SSH profile and generated keys. Test text files are retained locally for inspection. Only one fixture listener is managed by this script at a time; stop it before choosing another port/device. No Maven package is published.

## Results and boundaries

See [validation](VALIDATION.md#real-local-ssh) for device evidence. macOS Vim 9.1 renders ZWJ as literal `<200d>` in the tested configuration; less 581.2 passes the sequence through. A successful raw UTF-8/PTY test does not certify identical emoji presentation in every remote TUI. SSH resume, background service ownership, password/key-management UI and performance certification remain outside this sample.
