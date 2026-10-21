# Sample: local SSH discovery

The sample provides a Moshi-inspired **New connection → discovery count → bottom sheet** interaction. Open **新建 SSH 连接** using the plus button on the minimized session home, or **新建连接 · 局域网发现** from terminal tools. Discovery starts on entry; tap the green count to see **本地 SSH 服务器**. Selecting a card fills the name, host and port in an in-memory draft.

Discovery itself does not authenticate or interrupt the current terminal. The same form now continues into [SSH authentication, fingerprint confirmation, encrypted profile storage and connection](LOCAL_SSH.md#create-a-connection-in-the-app). Only pressing **保存并连接** starts verification; a successful verified profile replaces the active sample connection. The terminal AAR has no discovery or networking dependency.

## Discovery contract

- Use an available **non-VPN Wi-Fi IPv4 network**, even if cellular or a VPN is the default network. Each socket uses that Wi-Fi `Network`'s socket factory; the process is never rebound to another network.
- Scan TCP **22** only, inside the assigned subnet. For networks broader than `/24`, scan only the device's containing `/24`, explicitly shown in the UI. Exclude the phone, network and broadcast addresses (with `/31` point-to-point semantics); at most 253 peers on an ordinary `/24`.
- At most 16 workers; 450 ms connect timeout and a 700 ms total banner-read deadline. Require a bounded SSH 2.0 or 1.99 server identification instead of accepting every open TCP listener. No SSH client identification, login attempt, password, command or key is sent.
- `_ssh._tcp` Android NSD/mDNS supplies optional display names. Only names resolving to an address in the scanned range and port 22 can decorate a verified result. For hosts without an mDNS name, query PTR records from the selected Wi-Fi DNS servers (up to two servers, 400 ms each). Validate the response ID, question, record bounds and compression pointers. Name precedence is mDNS → DNS PTR → IP; unresolved hosts still appear by IP. Discovery names are display hints, **not host-key trust**.
- Results arrive incrementally, deduplicated by IP. The scan finishes after all probes (with a short naming window), or at a 20-second ceiling. Refresh starts a new scan; there is no periodic/background subnet sweep.
- Leaving the Activity cancels pending work, closes probe sockets and stops service browsing. Wi-Fi changes clear stale results and restart discovery for the new scope. Returning to the foreground scans again. Legacy NSD resolution callbacks may arrive late; they are discarded after cancellation/completion.
- No Wi-Fi/IPv4 produces a clear retry/manual-entry state. No results suggests checking that SSH is enabled. This does not discover arbitrary custom ports, IPv6-only hosts, other VLANs or all addresses on a large enterprise LAN.

## Verification

Build (JDK 17 and the configured Android SDK):

```sh
./gradlew :sample:assembleDebug :sample:assembleRelease :sample:assembleDebugAndroidTest :sample:lintRelease
adb -s DEVICE install -r sample/build/outputs/apk/debug/sample-debug.apk
adb -s DEVICE install -r sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
adb -s DEVICE shell am instrument -w \
  -e class dev.ghostty.android.sample.LanSshDiscoveryTest \
  -e expectedSshHosts FIRST_MAC_IP,SECOND_MAC_IP \
  dev.ghostty.android.sample.test/androidx.test.runner.AndroidJUnitRunner
```

For the opt-in physical UI flow (temporarily toggles Wi-Fi and restores it), pass the same host arguments with `-e class dev.ghostty.android.sample.SshDiscoveryActivityTest`. It verifies selection, Activity recreation and network recovery. Keep the test app foreground during the run. The scanner test additionally accepts `expectedPtrHost` and `expectedPtrName` to validate a known reverse-DNS result.

The explicit host arguments enable the real Wi-Fi discovery/cancellation assertion. Without them that test is intentionally skipped; subnet, protocol and timeout tests still run. No personal LAN addresses are baked into the application.

Physical UI acceptance: enter from the plus button; open the count; verify both computers; select each and check name/IP/22; disable Wi-Fi and check the list clears; restore Wi-Fi and check both reappear; reopen the screen and repeat. Restore the original Wi-Fi state after testing. These probes can appear as unauthenticated connections in server logs.

See [validation](VALIDATION.md#local-ssh-discovery) for the actual device/build results. No Maven publication or complete business flow is part of this feature.
