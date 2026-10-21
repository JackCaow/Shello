# Validation

## SSH connection creation

2026-10-21: the sample now completes manual/LAN-selected host → username/authentication → explicit SHA-256 host trust → encrypted profile → foreground SSH terminal. Saved profiles support reconnect and confirmed deletion. Optional tmux uses an exec PTY and a stable per-profile session name on the remote server. [Workflow and reproducible fixture](LOCAL_SSH.md#create-a-connection-in-the-app).

- **API 27 and API 35: 6 tests passed each** ([API 27](evidence/ssh-connection-flow/api27-tests.txt), [API 35](evidence/ssh-connection-flow/api35-tests.txt)), covering validation, AES-GCM roundtrip/tamper rejection, cancelled trust, wrong password, changed host key rejection, Ed25519 password/key authentication, passphrase-protected OpenSSH keys, real OpenSSH/tmux shell reattachment, Activity recreation, new-profile switching while a terminal exists, saved-profile reconnect and deletion.
- **API 35 R8 release:** operated the password form through fingerprint confirmation into the echo SSH shell; imported an Ed25519 key through the system file picker, enabled tmux, confirmed its fingerprint and reached the real Mac zsh prompt. UIAutomator briefly returned a null accessibility root after connecting; the captured screen independently showed the live shell. Screenshots remain local. [Build hashes and scope](evidence/ssh-connection-flow/validation.json).
- The fixture generates disposable keys and credentials. Password auth uses a loopback echo-only SSH server; public-key auth and tmux use local macOS OpenSSH. No existing user SSH configuration or remote tmux session is modified.
- Debug, instrumentation and R8 release builds passed; sample release lint reported **0 errors / 29 warnings**. Bouncy Castle is included only in the sample to supply Ed25519 support on Android; its upstream license is packaged with the APK.

This remains the sample connection flow, with one active connection and password/public-key authentication. Existing-profile editing and keyboard-interactive/MFA are not implemented. The phone was not connected for this acceptance run. No Maven publication or business integration.


## Live SSH cursor acceptance

2026-10-21: used the physical API 36 Xiaomi phone over Wi-Fi to a local macOS SSH/tmux fixture. Created separate test windows for Vim 9.1 and Claude Code 2.1.296, preserving the original unsaved Vim session. Input went through the Android app (direct key events, its floating direction pad, and actual taps on the phone's Pinyin keyboard); tmux pane captures and phone screenshots checked the result.

- **Vim:** insertion in the middle of a line, left/right/up/down movement, Chinese two-column block coverage, Esc, and normal/insert/replace mode switching passed. The isolated test vimrc explicitly configured `t_EI=ESC[2 q`, `t_SI=ESC[6 q`, and `t_SR=ESC[4 q`, giving steady block/bar/underline cursors. This is configured Vim behavior, not a claim that stock Vim automatically changes cursor shapes.
- **Claude Code:** prompt insertion, left/right editing, Pinyin composition and committing `你好`, and cursor movement across Chinese characters passed. Preedit stayed in the IME; the candidate was committed once. The same prompt survived keyboard resize and sample reinstall/reconnect. No model request was submitted.
- **Blink diagnosis:** both libghostty-vt's built-in default and this tmux client's explicit terminal capabilities can produce a steady cursor. The library now selects blinking for new sessions and default-style resets; explicit PTY steady commands still win. In particular, this `xterm-256color` client's `cnorm` sends `CSI ?12 l` and `Se` sends `CSI 2 SP q`. The fix deliberately does **not** override those instructions.
- **Live blink check:** temporarily set only the Claude test pane with `tmux set-option -p -t TEST_PANE cursor-style blinking-block`. Phone screenshot sampling confirmed both visible and hidden phases over the Chinese glyph. Unset the pane option afterward. This is a separately configured blinking check, not evidence that unconfigured tmux now blinks.
- **API 27: 15 tests passed**, including the new default/omitted-style reset, RIS, explicit steady-mode, and checkpoint-policy regression. [Runner output](evidence/cursor-ssh/api27-tests.txt). The final rebuilt test APK also passed the five focused cursor tests. [Focused runner output](evidence/cursor-ssh/api27-cursor-final.txt). Debug sample, R8 release sample, release AAR and terminal/sample release lint passed. The fixed debug sample was installed and used for the Claude/Pinyin/blink checks; the phone disconnected from USB before the standalone instrumentation rerun. [Detailed record](evidence/cursor-ssh/validation.json).

Screenshots/recordings remain local. No exact Moshi timing or input-latency benchmark, Maven publication, or business-flow integration was performed. The stock macOS Vim displays the ZWJ as `<200d>` in its own PTY output; that application-side representation is separate from the terminal's grapheme/cursor rendering.

## Cursor appearance and blink

2026-10-21: optimized the Android renderer with an opaque block cursor and contrasting redraw of the covered glyph, two-column coverage for wide characters, crisp bar/underline/outline geometry, and per-view blink timing. Input/movement/focus restores a 750 ms visible hold before 500 ms off/on intervals. Remote steady/hidden cursor modes remain authoritative; inactive windows show a non-blinking outline.

- **API 27: 14 tests passed**, including all previous API/Unicode/IME/selection regressions and four new cursor tests. [Output](evidence/cursor/api27-tests.txt).
- **Physical Android 16 / API 36: four cursor tests passed**, covering pixel opacity/text contrast, Chinese wide cells and trailing columns, remote bar/underline/visibility, inactive outline, timing reset and scrollback cursor suppression. [Output](evidence/cursor/phone-tests.txt).
- A test-only preview on the phone displayed block/bar/underline cursors over Chinese text, and screenshot sampling confirmed visible/hidden cycles. A local 5.48-second recording was captured. This is a visual acceptance check, not an exact measurement of Moshi’s timing or a new SSH/vim performance benchmark.
- Debug sample, R8 release sample, release AAR and test APK built successfully; terminal/sample release lint reported 0 errors (5/21 warnings). The updated debug sample was installed on the phone. [Build and device record](evidence/cursor/validation.json). No Maven publication or business-flow changes.

## Local SSH discovery

2026-10-21: validated the sample discovery UI on Xiaomi 25019PNF3C / Android 16 (API 36), against the two Mac computers on the phone’s Wi-Fi LAN. [Behavior and reproduction](LAN_SSH_DISCOVERY.md), [results](evidence/lan-ssh-discovery/validation.json).

- Both Macs were discovered by real Wi-Fi TCP/SSH probes; their mDNS display names appeared in the server-count/bottom-sheet picker. Selecting each filled its name, IP and port 22 without changing the saved SSH profile.
- Fixed the router showing only an IP by adding bounded Wi-Fi DNS PTR lookup as a fallback after mDNS. The real-router name assertion and malformed/truncated/compression-cycle DNS tests pass.
- **5 physical scanner tests passed** ([output](evidence/lan-ssh-discovery/phone-tests.txt)); **4 API 27 scanner tests passed / 1 intentional physical-LAN skip** ([output](evidence/lan-ssh-discovery/api27-tests.txt)). API 27 also opened the new connection UI successfully.
- **1 physical UI test passed** ([output](evidence/lan-ssh-discovery/phone-ui-tests.txt)): select both computers, recreate Activity and retain the selected draft, disable Wi-Fi and remove stale results, re-enable Wi-Fi and automatically rediscover both. The test restores Wi-Fi in cleanup. Earlier UIAutomator checks were interrupted by app switching/idle errors; the final test uses lifecycle callbacks and a launcher-first setup required by the OEM.
- Debug, R8 release, instrumentation APK and release lint passed: **0 errors / 21 warnings**. Three added warnings are the sample’s Chinese UI literals; the prior 18 warnings remain.

Discovery lives only in the sample, cancels on leaving its Activity, and does not authenticate or create a business connection. No Maven package was published.

## Physical Xiaomi / direct LAN checkpoint

2026-10-21: Xiaomi `25019PNF3C`, Android 16 / API 36, HyperOS `OS3.0.314.0.WOACNXM`, **Smart background restriction (recommended)**, no Doze whitelist. Notification permission was granted. [Observations](evidence/ssh-device-recovery/observations.json), [reproduction and limits](DEVICE_RECOVERY.md).

- SSH connected directly from the phone's Wi-Fi address to the Mac's LAN listener, with **no adb reverse**. USB remained connected for observation. A second macOS 15.3.1 ARM64 machine with tmux 3.6a was also reached over SSH and used for emulator regressions.
- A **12-minute lock interval** on the baseline build kept the app process and foreground service alive with no Activity binding, but the SSH client disappeared and HyperOS marked the wake lock `DISABLED`. The lock display reported `Dozing`; this is not proof of deep device idle. Battery unplug was simulated. [Samples](evidence/ssh-device-recovery/baseline-progress.json).
- **That long-run recovery is not accepted:** secure authentication needed manual unlock; a subsequent observation was an offline terminal, and its cause was not captured. The early ad-hoc helper lacked the target-package/connected-state guard now included in the reusable script. A later 40-second diagnostic lock did produce retryable EOF and a connected UI after unlock, but it is not a substitute for the full matrix.
- Code review identified a separate deterministic teardown race: JSch closes channel streams before clearing `Session.isConnected`. EOF without a remote exit result now retries; EOF arriving ahead of normal exit-status gets a bounded wait. A regression closes a real SSH channel while its Session still reports connected and verifies retryability. Normal remote tmux detach still stops the service and cancels reopen intent. See [upstream teardown ordering](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/Session.java) and the transport regression test.
- The service now reacts to default-network changes and accelerates a pending retry on visible return. API 35 verified no-network waiting, automatic recovery (**6.12 s including UI polling**), normal remote detach cancellation, and explicit Disconnect surviving a network change. [Results](evidence/ssh-device-recovery/api35-network.json).
- **后台运行设置** was opened on the phone: the explanation and this app's OEM settings page worked, with the original smart policy unchanged. [Result](evidence/ssh-device-recovery/settings.json). This is a user control, not an automatic exemption.
- Final **API 27 and API 35: 5 instrumentation passes / 1 intentional plain-shell skip each**. On API 36 before the test-harness fix: 4 passes / 1 skip / 1 Activity launch idle-timeout. The harness now waits for Activity lifecycle callbacks instead of global UI idle; that harness change was rerun on the emulators, **not yet on the phone**. Debug/release/test builds and release lint passed (0 errors / 18 warnings). [Hashes and exact scope](evidence/ssh-device-recovery/build.json).

The phone was handed back for interactive **Claude Code 2.1.296 over LAN SSH in a separate tmux window**. Final-build long-lock shell/vim, physical network-switch recovery and OEM process-kill recovery remain **incomplete**. No seamless cellular access to LAN-only hosts, unattended overnight survival, energy result, business integration or Maven release is claimed.


The library is an extracted alpha, not a shipped package. Validation results below apply to this standalone source tree only. This page separates initial validation from later compatibility changes; it is not a statement of current CI status. See [GitHub Actions](https://github.com/JackCaow/ghostty-android/actions) for remote build results. No Maven package release has occurred.


## Android 15 notification permission and foreground-service restrictions

Validated on 2026-10-21 with a clean **Android 15 / API 35 ARM64 Google Play emulator**, target SDK **35**, and the existing Mac SSH + tmux fixture. No root, battery exemption or background-start bypass was used. [Reproduction](LOCAL_SSH.md#verify-android-15-notification-and-foreground-service-restrictions), [full results](evidence/ssh-modern-android/result.json).

Fixed a reproduced notification lifecycle gap: if SSH connected before notification permission was granted, the connected foreground service could have **no notification after Allow**. The sample now refreshes notification state on permission completion, view rebind and return from settings, and requests immediate foreground notification display on API 31+. Automatic tmux recovery no longer invokes the explicit-connect permission request; a denied state is shown in the terminal and the tools menu opens notification settings. [Before fix](evidence/ssh-modern-android/before-refresh-fix-no-notification.png), [after fix](evidence/ssh-modern-android/allowed-notification.png). The old auto-resume path called `requestPermissions`, but this emulator suppressed a repeated dialog in the observed baseline run; that finding is not presented as a reproduced repeated-popup bug. [Baseline observations](evidence/ssh-modern-android/before-fix.json).

- **Permission denied and allowed:** actual system dialogs were tapped. Both states passed shell/vim Home, **30-second screen-off each**, process SIGKILL and ordinary launcher recovery without reconnect taps or permission dialogs. Home/lock retained the same app and SSH client; background service dumps had no Activity connection bindings. Each service reported **`types=0x40000000` (`specialUse`)**. [Denied screen-off](evidence/ssh-modern-android/denied-shell-lock-service.txt), [allowed screen-off](evidence/ssh-modern-android/allowed-vim-lock-service.txt).
- The original shell PID **59117**, environment and directory survived every interruption. Vim PIDs **45104** (denied) and **54054** (allowed) survived, and their unsaved text saved through the UI matched exactly. Both permission states also automatically reconnected after an abrupt SSH handler drop while Home was visible. [Denied restored vim](evidence/ssh-modern-android/denied-vim-recovered.png), [allowed restored vim](evidence/ssh-modern-android/allowed-vim-recovered.png).
- Allowed notifications opened the same connection and their **Disconnect** action stopped it. With permission denied, the notification drawer had no session notification and **会话工具 → 断开 SSH** stopped it instead. After both stop paths, process stop/reopen remained disconnected. The additional Android Task Manager equivalent **`am stop-app`** killed the app during unsaved vim; ordinary reopening reattached the same vim process. [Restored vim](evidence/ssh-modern-android/allowed-vim-task-manager-stop.png).
- A custom debug-only receiver, with no Activity/FGS or instrumentation privileges, hit the real **`ForegroundServiceStartNotAllowedException`** restriction. The production start helper handled it without crashing or losing the tmux resume intent; ordinary visible reopening recovered the shell. [Exception](evidence/ssh-modern-android/background-start-probe.txt). The asynchronous `startForeground` promotion path also handles policy/security rejection; that separate catch path was not fault-injected. The probe component is absent from the release APK.

- Revoking `POST_NOTIFICATIONS` during an active connection killed the Android app process on this image. Ordinary reopening restored tmux without an authorization prompt. The new **通知设置** entry opened system settings; granting via ADB while settings was open, then returning, refreshed the notification **without replacing the SSH connection**. [Settings results](evidence/ssh-modern-android/settings-result.json), [restored notification](evidence/ssh-modern-android/notification-settings-restored-notification.png).

- **API 35 and API 27 each passed 4 instrumentation tests / skipped 1**: session recreation/ownership, persisted-profile invalidation and transport/key error checks. The destructive plain-shell test is intentionally skipped for tmux. [API 35](evidence/ssh-modern-android/instrumentation-api35.txt), [API 27](evidence/ssh-modern-android/instrumentation-api27.txt). Debug, R8 release, test builds and release lint passed with **0 errors / 18 existing warnings**. The installed APK matched the final debug build; the debug restriction probe was verified absent from the release manifest. [Build hashes and scope](evidence/ssh-modern-android/build.json).

Reopen-to-connected observations were **5.13–7.08 seconds**, including UI polling, not a timing guarantee. This covers one API 35 OS image and ordinary screen-off; the API 27 deep-Doze test below remains a separate checkpoint. Physical devices, OEM battery policies and Google Play approval for `specialUse` are not certified. No business integration or Maven publication was added.

## Deep Doze and process-death recovery

Validated on 2026-10-21 using the rooted Android 8.1 / API 27 ARM64 emulator and the existing Mac SSH + tmux fixture. Fixed the gap where ordinary launcher open after process death stayed in the offline demo. The sample now persists a successful tmux profile fingerprint and reattaches on visible Activity open; explicit stop and changed profile/trust/key files cancel recovery. [Reproduction](LOCAL_SSH.md#verify-deep-doze-and-process-death-recovery).

- Shell and unsaved vim each stayed in confirmed **deep `IDLE` for 70 seconds**, without a Doze whitelist exemption. A simultaneous port-scoped silent TCP drop forced genuine SSH loss despite the ADB loopback path; counters recorded 24 dropped packets in each run. After network/idle restoration and normal launcher open, automatic reattachment was observed in **2.51 s / 3.41 s**. These include UI polling, not a latency guarantee. [Shell idle](evidence/ssh-doze-recovery/shell-doze-idle.txt), [vim idle](evidence/ssh-doze-recovery/vim-doze-idle.txt), [results](evidence/ssh-doze-recovery/result.json).
- Shell and vim each survived Android app **SIGKILL**, and vim additionally survived **force-stop**. No app process remained before normal launcher open; app and SSH client PIDs changed on recovery, while remote shell PID **59117** and vim PID **93792** remained the same. Reopen-to-connected observations were **1.58 s, 2.66 s and 2.72 s**. No debug connection extra, provisioning or reconnect tap was used. [Restored vim after force-stop](evidence/ssh-doze-recovery/vim-force-stopped.png).
- The shell's directory/exported variable remained unchanged. Vim's unsaved text survived every interruption and saved through the Android UI to the exact expected file. Notification **Disconnect** followed by force-stop and ordinary reopen left zero SSH clients and no foreground service. [Disconnected reopen](evidence/ssh-doze-recovery/explicit-stop-reopened.png).

- **4 instrumentation tests passed / 1 skipped**: profile/key/trust mutation invalidates resume, plain SSH cannot resume, explicit stop clears intent, Activity recreation retains the service VT, and existing host-key/network classification checks pass. The destructive plain-shell test remains skipped for tmux. [Output](evidence/ssh-doze-recovery/instrumentation.txt).
- Debug, R8 release, test APK and release lint passed: **0 errors / 18 warnings** (16 prior warnings and two intentional synchronous preference commits). Installed APK hash matches the final debug build. [Build and cleanup record](evidence/ssh-doze-recovery/validation.json).

This confirms forced emulator Doze plus an injected network outage and process-death recovery on user reopen. It does not establish physical CPU suspend, OEM battery-killer behavior, host-reboot persistence or continuous connectivity during Doze. Local scrollback/selection/draft persistence is outside this change. No business flow or Maven publication was added.

## Background SSH session ownership

Validated on 2026-10-21 in the Android 8.1 / API 27 ARM64 emulator. `SshSessionService` owns the live connection, retry timers, partial CPU wake lock and main-thread VT. The Activity only binds the view/input while started. [Behavior and boundaries](LOCAL_SSH.md#background-lifetime-and-notification).

- **Shell and vim each passed Home/background, 45 seconds of normal screen-off, and configuration-driven Activity recreation/restoration.** App PID `9455`, service record `4964cf4` and SSH client PID `12112` stayed unchanged across those operations. Shell PID `59117`, its directory and exported variable survived; vim PID `19015` and its unsaved buffer survived and the file saved through the UI matched exactly. Background shell output was processed while the UI was absent. [Results](evidence/ssh-background/result.json), [recreated vim](evidence/ssh-background/vim-recreated.png).
- Service dumps during Home and screen-off showed an active foreground service **without Activity connection bindings**. Screen-off assertions confirmed `mWakefulness=Asleep` and a held `ghostty:SSHSession` partial wake lock. [Background service](evidence/ssh-background/shell-background-service.txt), [screen-off service](evidence/ssh-background/shell-lock-service.txt). Configuration changes produced actual `am_relaunch_resume_activity`/destroy lifecycle events: [shell](evidence/ssh-background/shell-recreated-lifecycle.txt), [vim](evidence/ssh-background/vim-recreated-lifecycle.txt).
- An additional abrupt SSH drop **while backgrounded with no Activity observer** reconnected through the service; returning to the UI restored the original unsaved vim buffer. [Service](evidence/ssh-background/background-reconnected-service.txt), [restored buffer](evidence/ssh-background/vim-background-reconnected.png).
- **3 instrumentation tests passed / 1 skipped.** The new test explicitly called `Activity.recreate()`, asserted a new Activity instance, retained the same service-owned `TerminalSession` object and connected state, then verified explicit disconnect. The plain-shell destructive test remains skipped in tmux mode. [Output](evidence/ssh-background/instrumentation.txt).
- The notification **断开** action was tapped while the app was backgrounded. It removed the foreground service, released the wake lock and left zero SSH clients. [Notification](evidence/ssh-background/notification.png), [cleanup assertions](evidence/ssh-background/disconnect.json).
- Debug, R8 release, test builds and release lint passed: **0 errors / 16 warnings**, including the deliberately service-scoped non-timeout wake lock. [Hashes and scope](evidence/ssh-background/validation.json).

These are emulator tests of ordinary screen-off and Activity lifetime. Deep Doze and process-death recovery were subsequently tested in the section above. Vendor battery restrictions, real phones and long-duration battery usage are not certified. API 35 notification/foreground-service runtime coverage is recorded in the newer-platform section above. Force-stop/process death ends the service; the remote tmux process can survive independently. No business integration or Maven publication was added.

## Automatic SSH + tmux recovery

Validated on 2026-10-21 in the Android 8.1 / API 27 ARM64 emulator. Only the sample owns retry behavior; the terminal AAR remains transport-independent. [Reproduction](LOCAL_SSH.md#verify-automatic-recovery-with-silent-packet-loss).

- Two **silent TCP outages** used emulator `iptables OUTPUT DROP` for the fixture SSH port, held for **66.9 s in the shell** and **66.2 s in vim**. Packet counters confirmed actual dropped traffic. The sample detected lost keepalives and retried while the rule remained active; after removing the rule, both runs reconnected in **3.1 s as observed by UI polling** without a reconnect tap. These timings describe this run, not a recovery guarantee. [Results and packet counters](evidence/ssh-auto-reconnect/result.json).
- Shell PID **59117**, exported token and working directory remained unchanged. Vim PID **97338** survived; its unsaved buffer reappeared after automatic attachment and saving through the UI produced the exact expected file. [Retrying with retained screen](evidence/ssh-auto-reconnect/vim-retry.png), [automatically restored vim](evidence/ssh-auto-reconnect/vim-restored.png).
- Manual **断开 SSH** during recovery canceled reconnects. A normal remote `tmux detach-client` also stopped retries. Each was observed for 20 seconds after connectivity was available, with no tmux clients remaining. [Cancellation checks](evidence/ssh-auto-reconnect/cancel-result.json).
- **2 instrumentation tests passed / 1 skipped**: host-key rejection also asserted non-retryability; transport failure classification covered refused/timed-out/EOF connections versus invalid profile/auth/config errors. The existing plain-shell test is intentionally skipped for a persistent tmux fixture. [Output](evidence/ssh-auto-reconnect/instrumentation.txt).
- Debug/release/test APK builds and release lint passed: **0 errors, 17 warnings** (including the sample's untranslated UI strings). [Hashes and scope](evidence/ssh-auto-reconnect/validation.json).

Retry delays are 1, 2, 4, 8 and at most 15 seconds, reset after a connection stays open for 10 seconds. Normal exit, connection-setup authentication/host-key/configuration failures, explicit disconnect, offline mode and Activity destruction stop retries. The screen/draft remain during a detected outage; direct input is not replayed into the next transport. This tests a silent TCP outage on the `adb reverse` path, not device-wide Wi-Fi/cellular roaming. At this checkpoint retry ownership was limited to the Activity; the background-service section above supersedes that limitation. Process-death auto-launch and host-reboot persistence remain outside this sample.

## SSH + tmux recovery

Updated on 2026-10-21. The sample can attach to an isolated persistent tmux session via real SSH. [Reproduction and cleanup](LOCAL_SSH.md). **This iteration is accepted on the API 27 emulator, per the requested scope.** Physical Android was not tested for this change and is not a remaining acceptance requirement. The earlier phone results below cover plain SSH, not tmux.

- **Mac OpenSSH client:** two abrupt SSH drops retained shell PID `59117`, its exported variable and working directory. Vim PID `59192` survived the second drop; its unsaved buffer was redrawn on reattach and saved with an exact file-content comparison. [Result](evidence/ssh-tmux/mac-result.json). `scripts/verify-tmux-local.py` reproduces these assertions.
- **Android 8.1 / API 27 ARM64 emulator UI:** commands were entered through the sample composer. Two abrupt SSH drops and manual menu reconnects retained shell PID `59117`, its variable/directory and vim PID `64237`. Force-stopping/relaunching the app while vim was still unsaved also reattached to that same process. The restored text was saved through the UI and checked on the Mac. [Result](evidence/ssh-tmux/api27-result.json), [before](evidence/ssh-tmux/api27-vim-before.png), [reconnected](evidence/ssh-tmux/api27-vim-restored.png), [after app force-stop](evidence/ssh-tmux/api27-vim-after-process-death.png).
- **Disconnected draft fix:** Send retains text instead of clearing a draft when SSH is disconnected. Verified in the emulator UI. [Evidence](evidence/ssh-tmux/api27-disconnected-draft.png).
- Debug/release/instrumentation builds pass; release lint has 0 errors / 15 warnings. In tmux mode the host-key rejection instrumentation test passed and the destructive plain-shell test was **skipped**, not counted as recovery coverage. [Test output](evidence/ssh-tmux/api27-instrumentation.txt), [artifact hashes and scope](evidence/ssh-tmux/validation.json).

The injected failure kills the fixture's SSH handler processes while preserving its listener and tmux server. It is not Wi-Fi/cellular roaming or silent packet loss. At this checkpoint reconnection was manual; the automatic-recovery section above supersedes that limitation. tmux recreates the visible screen while keeping remote processes alive. Android drafts/selection/full local scrollback after process death, server reboot recovery are not provided; background ownership was added later as described above. Existing emoji-width compatibility findings below remain open.

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
