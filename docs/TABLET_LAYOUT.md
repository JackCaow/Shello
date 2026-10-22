# Tablet and landscape layouts

The sample app sizes content against its current window, not the physical display. Rotation and a narrower window can therefore select different layouts on the same device. The terminal library's rendering API is unchanged.

- Home uses a centered column up to 1200 dp. Sessions and saved connections sit side by side when their container has at least 840 dp; smaller windows stack them. Session previews remain bounded, and short landscape windows put the context menu beside the selected preview.
- Settings, SSH forms and the agent inbox share a centered 640 dp reading column. Header actions align with that column. The pairing page allows 1040 dp and places the scanner beside the instructions when at least 600 dp is available.
- New-connection choices share a row in wide sheets. Tmux and LAN discovery sheets have bounded widths and scroll their lists within the available height. Tablet sheets are centered; compact devices retain bottom placement. Long confirmation messages and actions can scroll together.
- The terminal retains the full available width. Its toolbar is centered and capped at 600 dp, and the draft editor at 800 dp. A short, wide window uses an inline draft editor and a single-row direction pad. System bars, cutouts and keyboard insets remain outside terminal content.
- Connection passwords, passphrases and imported keys survive configuration recreation in memory only. They are not added to saved-state Bundles. Terminal drafts, the new-connection choice sheet and agent answer drafts are restored after recreation.

## Regression checks

Build the sample and instrumentation APKs:

```sh
./android/gradlew -p android :sample:assembleDebug :sample:assembleDebugAndroidTest
adb -s "$TEST_SERIAL" install -r android/sample/build/outputs/apk/debug/sample-debug.apk
adb -s "$TEST_SERIAL" install -r android/sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
```

On a disposable emulator, grant camera access and, on API 33+, notifications before running unattended UI/SSH checks. Enable the emulator's software keyboard. The notification permission prompt otherwise pauses Activity-resume assertions; it is not a connection failure.

```sh
adb -s "$TEST_SERIAL" shell pm grant dev.ghostty.android.sample android.permission.CAMERA
# API 33+ only:
adb -s "$TEST_SERIAL" shell pm grant dev.ghostty.android.sample android.permission.POST_NOTIFICATIONS
adb -s "$TEST_SERIAL" shell settings put secure show_ime_with_hard_keyboard 1
adb -s "$TEST_SERIAL" shell am instrument -w \
  -e class dev.ghostty.android.sample.AdaptiveLayoutTest,dev.ghostty.android.sample.SettingsActivityTest \
  -e layoutScreenshots true -e settingsScreenshots true \
  dev.ghostty.android.sample.test/androidx.test.runner.AndroidJUnitRunner
```

`AdaptiveLayoutTest` defaults to landscape; use `-e layoutOrientation portrait` for a narrow portrait window. It checks sheet bounds and scrolling, menu accessibility, credentials after rotation, pairing/inbox widths, retained answers and terminal drafts, keyboard layout and the direction pad. `SettingsActivityTest` covers eleven settings pages, font controls and preference persistence. Screenshots are written to the app's `cache/adaptive-review` and `cache/settings-review` directories.

Use the existing disposable OpenSSH/tmux fixture for connection creation/editing, picker bounds, session ordering and retained remote sessions:

```sh
uv run --with paramiko==5.1.0 --with qrcode==8.2 python \
  scripts/verify-connection-flow.py --serial "$TEST_SERIAL" --home-features
```

When changing emulator density, size, orientation or locale, record and restore the previous values. A narrower simulated display verifies responsive breakpoints; OEM keyboards and physical tablets still need device-specific acceptance.

## Verified on 2026-10-21

- Android 15 Pixel Tablet emulator, 2560 × 1600 at 320 dpi: 10 UI/state checks passed, plus 13 disposable SSH/tmux/home-flow checks.
- Android 8.1 phone emulator, 1920 × 1080 at 420 dpi in landscape: 10 UI/state checks passed.
- Android 15, 1200 × 1600 override at 320 dpi with Simplified Chinese: 10 UI/state checks passed, including rotation and software-keyboard cases.
- Actual Android split-screen with Settings alongside Shello: manually checked the home page at roughly half width, resized to one-third width with the new-connection sheet open, then opened a terminal and its software keyboard. The sheet survived resizing and remained within the app pane; terminal controls stayed above the keyboard.

These are emulator results, not physical-tablet acceptance. No Maven package or release tag was published for this change.
