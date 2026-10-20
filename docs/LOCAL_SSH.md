# Local SSH sample

The sample can run a real SSH PTY against your Mac using mwiede/JSch 2.28.8. The terminal AAR has no SSH dependency. This is a development fixture, not the full remote-session product flow.

## Run

Build/install the debug sample, unlock an authorized Android device, then run:

```sh
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r sample/build/outputs/apk/debug/sample-debug.apk
python3 scripts/local-ssh.py start --serial DEVICE_SERIAL --tmux
```

Install `tmux` on the Mac first (tested with tmux 3.6a). Omit `--tmux` to use the earlier plain-shell fixture.

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

Use **会话工具 → 断开 SSH / 重新连接本机 SSH / 返回离线演示**. In **tmux mode**, the fixture starts one persistent session on its own private socket. Every SSH connection runs `tmux attach-session -d -t SESSION`: it attaches to the existing shell and detaches an older client. It never creates a replacement shell during reconnect. A missing/exited session fails attachment. The sample displays `已连接 · tmux` when its SSH channel opens; this label is not a separate tmux readiness acknowledgment.

Reconnect clears the local VT and tmux redraws the current screen, including a running vim's unsaved buffer. The remote shell, current directory and environment survive SSH disconnection. Android Activity/process destruction still closes SSH, but the tmux server owns the remote processes independently. Reopen the sample and reconnect manually. This does not restore Android drafts, local selection or complete local scrollback after process death. Disconnected direct input is dropped; pressing Send while disconnected retains the draft. No disconnected keystrokes are replayed.

Without `--tmux`, reconnect still opens a **new shell**. The `connectLocalSsh` launch extra is honored only by debug builds. No SSH credentials or tmux behavior have been added to the terminal AAR.

### Exercise a dropped connection

With shell state or an unsaved vim buffer on screen:

```sh
python3 scripts/local-ssh.py status --serial DEVICE_SERIAL
python3 scripts/local-ssh.py drop --serial DEVICE_SERIAL
# In the app: 会话工具 → 重新连接本机 SSH
```

`drop` abruptly kills only this fixture listener's SSH connection handlers. It keeps the listener, tmux server and shell/vim alive. This exercises a broken SSH connection; it does not simulate Wi-Fi/cellular roaming or a silent network blackhole. Verify that the shell PID, exported variable and working directory remain the same; for vim verify the process PID and save the previously unsaved text after reconnect.

After USB reconnection, `python3 scripts/local-ssh.py connect --serial DEVICE_SERIAL` reinstalls the private profile and reverse mapping and reopens the existing session. It does not rotate keys or create another session. `start --tmux --host-only` can prepare the fixture before Android is available.

For a repeatable **Mac OpenSSH client** check, run against an idle fixture shell (it detaches other clients):

```sh
python3 scripts/verify-tmux-local.py --output /tmp/tmux-resume.json
```

This performs two abrupt connection drops, asserts shell PID/environment/directory preservation, checks vim remains alive and its unsaved buffer is redrawn, then saves and compares exact file contents. Results are host-client evidence, not Android UI acceptance.

## Verify and stop

With the fixture still running, install the instrumentation APK and run:

```sh
adb -s DEVICE_SERIAL install -r sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  dev.ghostty.android.sample.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/local-ssh.py stop --serial DEVICE_SERIAL
```

The two integration tests are opt-in: without a private profile they are **skipped**, not runtime acceptance. With the fixture they check PTY dimensions, resize, Unicode bytes, vim/less alternate screens, Ctrl+C, fresh reconnection and rejection of an unknown host key. The plain-shell test is skipped in tmux mode because its `exit` would terminate the persistent fixture shell. Host-key rejection remains applicable. Do not count that skip as tmux recovery coverage.

`stop` terminates this fixture listener **and its dedicated tmux server, shell and running programs**, removes the USB reverse mapping, force-stops the sample and removes its SSH profile and generated keys. Test text files are retained locally for inspection. Only one fixture listener is managed by this script at a time; stop it before choosing another port/device. No Maven package is published.

## Results and boundaries

See [validation](VALIDATION.md#real-local-ssh) for device evidence. macOS Vim 9.1 renders ZWJ as literal `<200d>` in the tested configuration; less 581.2 passes the sequence through. A successful raw UTF-8/PTY test does not certify identical emoji presentation in every remote TUI. Automatic retries, silent-network failure timing, background service ownership, server-reboot recovery, password/key-management UI and performance certification remain outside this sample. tmux recovery depends on the server and its processes staying alive; tmux is not a durable checkpoint across host reboots.
