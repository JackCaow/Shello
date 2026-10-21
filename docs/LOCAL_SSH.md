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

In tmux mode, transport failures automatically retry with delays of 1, 2, 4, 8, then at most 15 seconds between attempts. A connection that stays open for 10 seconds resets the delay. The last screen remains visible during an outage; the first output from the new connection resets the local VT and tmux redraws the current screen, including a running vim's unsaved buffer. The remote shell, current directory and environment survive SSH disconnection. The sample foreground service owns SSH, retries and the main-thread VT. Backgrounding, normal screen-off and Activity recreation detach/rebind the view without closing the connection or parser. App process death/force-stop still ends the service; tmux independently retains remote processes, and the user can reopen and reconnect. This does not restore Android drafts, local selection or complete local scrollback after process death. Disconnected direct input is dropped; pressing Send while disconnected retains the draft. No disconnected keystrokes are replayed. Delivery of input sent just before failure detection remains uncertain; it is never resubmitted on the next SSH connection.

**会话工具 → 断开 SSH**, the notification’s **断开** action, returning to the offline demo, and service destruction cancel pending retries and invalidate callbacks from earlier attempts. Activity destruction only detaches its observer and unbinds. Authentication, host-key, configuration and protocol failures detected during connection setup stop automatic retries. Normal remote shell exit/tmux detach also stops retries. Plain SSH mode keeps manual reconnection so it cannot silently create a replacement shell. The existing 10-second SSH keepalive with three unanswered probes detects silent outages; detection is not immediate and depends on socket/OS timing. The service continues retrying while the Activity is absent; no connectivity-monitor permission is used.

Without `--tmux`, reconnect still opens a **new shell**. The `connectLocalSsh` launch extra is honored only by debug builds. No SSH credentials or tmux behavior have been added to the terminal AAR.

### Background lifetime and notification

A user connection starts `SshSessionService` as a foreground service with a low-importance ongoing notification. Tapping it opens the terminal; **断开** stops the transport/retries and releases the CPU wake lock. The service retains the VT and processes bounded SSH output even with no Activity. The view attaches in `onStart` and detaches/unbinds in `onStop`; no Activity observer is retained in the background. A recreated Activity reuses the service's parser, screen and scrollback. A local text draft is saved in Activity instance state, while selection and UI history are not durable session storage.

A partial CPU wake lock is held only for the explicitly started, notified session (including retry waits) and is released on disconnect, terminal failure or service destruction. It does not keep the screen on. No battery-optimization exemption, boot receiver or automatic process restart is requested. The service uses `START_NOT_STICKY`. System force-stop/process death, deep Doze and vendor battery restrictions are not bypassed; network suspension may require automatic tmux reattachment after the device wakes. This prototype is not a guarantee of indefinite background connectivity or battery performance.

For API 34+, the sample declares `specialUse` and an explanatory service subtype for the user-started interactive SSH session. This is a prototype declaration, not Google Play approval; [Android requires review of special-use cases](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use). API 33+ requests notification permission on an explicit Connect action. These newer-platform paths build but are not covered by the API 27 runtime acceptance.

To reproduce the emulator background cycle with an idle Bash fixture already connected:

```sh
python3 scripts/verify-background-session.py --serial emulator-5580 --output /tmp/ssh-background
```

The UI test covers shell/vim separately through Home, 45-second screen-off and configuration recreation, then an SSH drop while backgrounded. It restores display/stay-awake settings in `finally`. The opt-in `SshSessionServiceTest` additionally uses an explicit `Activity.recreate()` and compares the retained service VT object.

### Exercise a dropped connection

With shell state or an unsaved vim buffer on screen:

```sh
python3 scripts/local-ssh.py status --serial DEVICE_SERIAL
python3 scripts/local-ssh.py drop --serial DEVICE_SERIAL
# In tmux mode, observe automatic reconnection; no reconnect tap is required.
```

`drop` abruptly kills only this fixture listener's SSH connection handlers. It keeps the listener, tmux server and shell/vim alive. This exercises a broken SSH connection; it does not simulate Wi-Fi/cellular roaming or a silent network blackhole. Verify that the shell PID, exported variable and working directory remain the same; for vim verify the process PID and save the previously unsaved text after reconnect.

After USB reconnection, `python3 scripts/local-ssh.py connect --serial DEVICE_SERIAL` reinstalls the private profile and reverse mapping and reopens the existing session. It does not rotate keys or create another session. `start --tmux --host-only` can prepare the fixture before Android is available.

For a repeatable **Mac OpenSSH client** check, run against an idle fixture shell (it detaches other clients):

```sh
python3 scripts/verify-tmux-local.py --output /tmp/tmux-resume.json
```

This performs two abrupt connection drops, asserts shell PID/environment/directory preservation, checks vim remains alive and its unsaved buffer is redrawn, then saves and compares exact file contents. Results are host-client evidence, not Android UI acceptance.

### Verify automatic recovery with silent packet loss

On a rooted Android 8.1 ARM64 emulator, install the debug APK, keep the screen awake/unlocked, then attach the tmux fixture. `adb root` restarts adbd, so provision the connection after it:

```sh
adb -s emulator-5580 root
python3 scripts/local-ssh.py connect --serial emulator-5580
python3 scripts/verify-auto-reconnect.py --serial emulator-5580 --output /tmp/auto-reconnect
```

The fixture pane must be an idle Bash shell. The script enters commands through the sample UI, temporarily drops outgoing packets to the fixture's loopback SSH port using an emulator-only `iptables` rule, then removes that rule. It holds each outage for about 65 seconds to exercise keepalive failure detection and failed connection retries, first in the shell and then in vim with unsaved text. It asserts original process/environment/directory state and saved text after automatic recovery, without tapping Reconnect. It writes JSON and screenshots; a `finally` block removes the exact firewall rule on a test failure or keyboard interrupt. This is a controlled silent TCP outage over `adb reverse`, not a Wi-Fi/cellular roaming test.

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

See [validation](VALIDATION.md#real-local-ssh) for device evidence. macOS Vim 9.1 renders ZWJ as literal `<200d>` in the tested configuration; less 581.2 passes the sequence through. A successful raw UTF-8/PTY test does not certify identical emoji presentation in every remote TUI. Server-reboot recovery, password/key-management UI and performance certification remain outside this sample. Automatic recovery requires a live foreground service in tmux mode. tmux recovery depends on the server and its processes staying alive; tmux is not a durable checkpoint across host reboots.
