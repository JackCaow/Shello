# Physical-device SSH recovery

The sample can recover a still-running remote tmux session. It cannot guarantee an uninterrupted SSH connection under a vendor's battery policy. A foreground service and a held partial wake lock are not an exemption from every OEM restriction. A remote machine shutting down or tmux exiting also ends the recoverable session.

Current measured results and incomplete physical-device acceptance are recorded in [VALIDATION.md](VALIDATION.md#physical-xiaomi--direct-lan-checkpoint).

## Direct LAN fixture

Use a private network you control. Prepare the existing dedicated Mac fixture without provisioning a USB route, then expose an additional listener on one explicit address assigned to that Mac:

```sh
python3 scripts/local-ssh.py start --serial DEVICE --tmux --host-only
# If the fixture already exists, skip start. Do not replace a session during recovery tests.
python3 scripts/lan-ssh.py connect --serial DEVICE --address MAC_LAN_IPV4
```

The extra listener uses the fixture's existing host key, generated client key, key-only authentication and forced attachment to the same dedicated tmux socket. It does not enable macOS Remote Login or bind all interfaces. The app receives a pinned host key for that LAN address. The fixture's `adb reverse` mapping is removed; USB remains available for observation. Verify the host's established socket has the phone's Wi-Fi address as peer before treating a run as LAN evidence.

`connect` provisions a profile and explicitly starts the app. It is setup, **not** a recovery test. An actual reopen uses the launcher, without the `connectLocalSsh` extra. A profile address or trust-file change invalidates the saved resume intent by design.

Stop the extra listener with `python3 scripts/lan-ssh.py stop`. Existing accepted SSH connections remain until disconnected; keys and tmux are retained. `local-ssh.py stop` stops both managed listeners and destroys the dedicated fixture. Neither command touches the user's default tmux server.

## Repeatable phone test

Install the debug APK, connect it to the dedicated idle Bash fixture, leave Wi-Fi enabled, and unlock the phone. Do not interact with the phone during the lock interval:

```sh
python3 scripts/verify-device-recovery.py --serial DEVICE --case shell \
  --lock-seconds 720 --output /tmp/phone-shell
python3 scripts/verify-device-recovery.py --serial DEVICE --case vim \
  --lock-seconds 720 --output /tmp/phone-vim
```

The test sends commands through the actual app input UI, validates the complete draft before submission, records the original shell PID/environment/directory, and for vim retains an unsaved buffer. It backgrounds and locks the phone, turns Wi-Fi off for 90 seconds one quarter of the way through the lock, then restores Wi-Fi while still locked. Samples record process/client identities, foreground-service state, Activity bindings, wakefulness, idle state and whether the OEM disabled the app wake lock. A sampled wake invalidates continuous-lock acceptance.

After the timed lock, it opens the launcher normally and checks recovery, then kills only the debug app process and repeats ordinary reopen. It checks the original remote shell state and saves/compares the previously unsaved vim text. It never creates a replacement remote session during those checks.

The test temporarily uses `dumpsys battery unplug` and disables stay-awake while USB remains physically connected. A `finally` block restores those settings and Wi-Fi. This exercises the vendor policy seen with a simulated battery state, **not** unplugged overnight standby, energy consumption or proof of deep Doze. `mWakefulness=Dozing` can be the always-on lock display; report `deviceidle` separately. See [Android's Doze testing guidance](https://developer.android.com/training/monitoring-device-state/doze-standby).

For a second Mac, first provision a dedicated remote tmux fixture/profile with a temporary test identity and pinned host key. Keep personal SSH private keys off the phone. Point observation at that same remote fixture:

```sh
python3 scripts/verify-device-recovery.py --serial DEVICE --case vim \
  --ssh-alias PEER_MAC_ALIAS --fixture-dir /absolute/path/to/dedicated/fixture \
  --tmux /opt/homebrew/bin/tmux --lock-seconds 720 --output /tmp/peer-vim
```

The observer SSH login runs only fixture observations; terminal input goes through the Android app's own SSH transport. Remove only the temporary authorized-key line and dedicated fixture when finished, preserving unrelated SSH/tmux configuration.

## Recovery and user controls

In tmux mode the foreground service observes changes to Android's default network. Loss of that network cancels the old transport and waits; a new default network starts a fresh connection immediately, retaining the remote-session resume marker and local VT. Callback ownership and connection generations prevent stale events reviving an explicitly disconnected session. A cellular network can be available even when a LAN-only destination is unreachable; keepalive and bounded backoff still handle that case. VPN-underlay changes may leave the default network identity unchanged, so they still rely on the transport path.

Returning to a visible Activity accelerates an already scheduled retry. This does not restart a healthy SSH connection, replay input, request a foreground-service exemption, or bypass a vendor freeze. Explicit Disconnect unregisters monitoring and cancels retry/resume intent.

**会话工具 → 后台运行设置** explains the limitation and opens this app's system information page, where vendor-specific battery controls may be available. It neither changes the policy nor requests an exemption automatically. Users can choose a policy appropriate for their battery/availability requirements. Even an unrestricted policy does not guarantee continuous networking.

For a LAN-only host, Wi-Fi → cellular means the host may be unreachable until Wi-Fi returns. Do not report seamless cellular roaming merely because cellular service was enabled. A reachable public/VPN endpoint is a separate test.
