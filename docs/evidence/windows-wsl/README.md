# Windows / WSL2 pairing validation

This change supports WSL Linux sessions, not native PowerShell terminals. No Windows computer was available. Windows UAC, actual WSL setup, real Windows/Hyper-V networking and a phone-to-Windows end-to-end run remain unverified; CI mocks do not replace those checks.

Local validation (2026-10-22):

- `:sample:assembleDebug`, `:sample:assembleDebugAndroidTest`, `:sample:lintRelease`, `:terminal:lintRelease`: passed.
- API 27 ARM64 disposable emulator, `PairingWslTest`: 6/6 passed. Windows/Tailscale command copy, platform/network retention after Activity recreation, in-flight state retention, retry deduplication response/port/pin rejection and bounded certificate clock skew.
- `PairingForwardedDeviceTest`: 1/1 passed with `scripts/test-forwarded-pairing.py`. Real HTTPS with bad certificate pin rejected, RSA public-key append, strict SSH host-key verification through an advertised non-22 port, tmux availability, encrypted saved profile, reconnect to the same tmux pane PID and missing SSH pin rejection. Loopback macOS SSH fixture plus adb forwarding; not Windows networking.
- `python3 -m unittest discover -s tests -p 'test_pair_*.py' -v`: 18/18 passed. Includes actual TLS through an independent TCP proxy with different advertised/listening ports and existing authorized_keys preservation.
- PowerShell 7.6.7 on macOS: 84 assertions passed. Native argv round-trip (including Unicode and spaces), embedded helper consistency, LAN/Tailscale choice, CIDR scoping, localized WSL2 listings, foreign mapping/rule preservation, finite lease and HTTPS-only cleanup, read-only check and resumable prerequisites. Windows machine operations mocked.
- `skill-creator/scripts/quick_validate.py skills/shello-pair`: passed. `actionlint` checked the final workflow syntax.

The Linux CI backend fixture passed after adding cleanup for its temporary PAM user session. CI runs the same PowerShell assertions in Windows PowerShell 5.1 and PowerShell 7, the Python integration suite on Linux, and an opt-in isolated Linux root fixture exercising the actual `wsl-host.sh`, OpenSSH and tmux while preserving normal sshd configuration. See the PR checks for their outcome.

To reproduce Android forwarding acceptance, build and install the debug app/test APK on a dedicated emulator, then run:

```sh
python3 scripts/test-forwarded-pairing.py --serial <dedicated-emulator>
```

The fixture uses loopback-only listeners, private generated host keys and authorized_keys, temporary adb mappings on 33442/33443, a temporary encrypted profile and an isolated tmux socket. It refuses existing mappings at those ports. Host SSH configuration, ordinary authorized_keys, existing profiles and other tmux sessions are untouched. Private QR/key material stays under ignored temporary build paths and is removed on exit. Result logs contain test outcomes, not tickets.
