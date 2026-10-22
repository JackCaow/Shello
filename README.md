<p align="center">
  <img src="assets/readme/icon.png" width="112" height="112" alt="Shello app icon">
</p>
<h1 align="center">Shello</h1>
<p align="center"><strong>Your remote terminal, in your pocket.</strong></p>
<p align="center">Native Android terminal · SSH + tmux · QR pairing · Claude Code / Codex sessions</p>
<p align="center">
  <a href="https://github.com/JackCaow/Shello/releases/download/v0.1.1/Shello-0.1.1-arm64-v8a.apk">Download APK</a> ·
  <a href="#quick-pairing">Quick pairing</a> ·
  <a href="skills/shello-pair/SKILL.md">Agent skill</a> ·
  <a href="docs/DEVELOPMENT.md">Build & embed</a> ·
  <a href="README.zh-CN.md">简体中文</a>
</p>

Shello is an open-source Android terminal for working on your computer from your phone. Run shell tools or Claude Code / Codex, and return to your remote sessions.

## Quick pairing

**Install → Copy to your agent → Scan.** Keep your phone and computer on the same Wi-Fi, or connect both to the same Tailscale network.

### 1. Install Shello on your phone

[**Download Shello APK · v0.1.1**](https://github.com/JackCaow/Shello/releases/download/v0.1.1/Shello-0.1.1-arm64-v8a.apk) — Android 8.1+, arm64-v8a. Already installed? Continue to step 2. If updating an older debug build, read the [signing and data notes](docs/RELEASING.md) before uninstalling it.

### 2. Copy this prompt to an agent on your computer

Open an agent that can run terminal commands **on the computer you want to connect to**, then copy and send:

```text
Help me pair this computer with Shello.
Get https://github.com/JackCaow/Shello and follow
skills/shello-pair/SKILL.md.
Install missing dependencies, configure SSH and tmux,
then display the pairing QR code.
Preserve existing SSH configuration and sessions.
```

The agent prepares the computer and displays a QR code. If system authentication is needed, complete it in your own terminal or system settings.

### 3. Scan with Shello

Open **+ → 扫码轻松配对** (QR pairing), allow camera access, and scan the code from your own computer. Confirm the address and wait for SSH verification. Once the connection is saved, select an existing session or create a new one.

The agent's QR code is valid for **5 minutes** by default. If it expires, ask the agent to generate another one. For setup without an agent or more options, expand the sections below.

<details>
<summary>Pair a Windows 11 / WSL2 computer</summary>

Run in your ordinary Windows user's PowerShell:

```powershell
$shelloPairScript = Join-Path $env:TEMP 'shello-pair.ps1'
Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.ps1' -OutFile $shelloPairScript
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $shelloPairScript
```

The script prepares WSL2, Python, OpenSSL, SSH and tmux, then displays a QR code. First setup may require Windows UAC, a restart and initializing an ordinary Linux user; rerun afterwards. Connections open **WSL Linux**, not native PowerShell. Existing SSH configuration, keys and tmux sessions are preserved.

Defaults: Windows-host SSH **2222**, temporary HTTPS **8765**. Add `-Network tailscale` or `-Network lan`; `-Check` only inspects prerequisites. SSH remains available after pairing; temporary pairing forwarding is removed. Rerun after Windows/WSL restarts or address changes. The v0.1.1 pairing screen includes a Windows command selector. Windows machine operations are mocked in CI; a real Windows end-to-end run is still pending. [Setup details and limits](skills/shello-pair/references/windows-wsl.md)

</details>

<details>
<summary>Manual pairing and dependencies</summary>

### 1. Prepare your computer

Use a Mac or Linux computer reachable from the phone, normally on the same LAN. You need SSH enabled, Python **3.9+**, OpenSSL, tmux and curl. On macOS, enable Remote Login. Allow the phone to reach the SSH port and the temporary HTTPS pairing port.

Run the command as the user you intend to log into over SSH, without `sudo`. The launcher does not enable SSH or install system packages. Some Linux distributions also require `python3-venv`.

### 2. Run the launcher

```sh
curl -fsSL https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.sh | bash
```

The script prepares a private Python environment under `~/.local/share/shello/pair`, installs its QR dependency and displays a QR code. The code expires after **3 minutes** by default. Keep that terminal open while pairing.

For multiple network interfaces or a custom SSH port, replace these example values:

```sh
curl -fsSL https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.sh \
  | bash -s -- --host 192.168.1.10 --ssh-port 2222
```

Add `--port 8765` to use a fixed HTTPS pairing port when configuring a firewall. Pass `--help` for all options. To use the launcher shipped with the current APK, replace `main` in the URL with `v0.1.1`.

### 3. Scan from Shello

1. Open **+ → 扫码轻松配对** (QR pairing).
2. Allow camera access and scan the QR code on your own computer.
3. Confirm the computer's address. Wait for public-key installation, SSH host-key verification and the tmux check.
4. Once saved, select an existing remote tmux session or create a new one.

The phone generates and stores its private key; only the public key goes to the computer. Existing `authorized_keys` entries are retained. The QR code contains a temporary token—do not share it publicly. After pairing, **Ctrl+C** closes the temporary service; the installed SSH public key remains. [Protocol and options](docs/HOME_AND_AGENTS.md#pairing)

</details>

<details>
<summary>Install the Agent skill separately</summary>

You can also copy the complete `skills/shello-pair/` folder into your agent's skills directory. Include its `scripts/`, `references/` and `agents/` resources, not only `SKILL.md`; outside this repository the Unix skill downloads and verifies the pinned official launcher, while the Windows launcher and helpers are bundled.

- **Hosts:** macOS, Debian/Ubuntu, Fedora/RHEL and Windows 11 + WSL2; native PowerShell sessions are not supported.
- **Existing setup:** SSH keys, access configuration and tmux sessions are retained. Administrator authentication stays in your own terminal or system settings.
- **QR display:** a temporary PNG, or a terminal QR when images are unavailable; the default skill session expires after 5 minutes and cleans up its temporary files.
- **Verification:** a public key being installed is reported separately from a phone successfully connecting over SSH.

</details>

<details>
<summary>Tailscale and network options</summary>

The current APK and pairing launcher support Tailscale discovery and QR pairing. Keep both devices connected to the same tailnet and use the matching release launcher when pinning a version.

With Tailscale connected on both devices, the launcher automatically prefers the computer’s Tailscale IPv4 address. Select **Auto / LAN / Tailscale** on the pairing page, or pass `--network tailscale` / `--network lan` to the script. Network access rules must allow SSH and the temporary HTTPS pairing port.

Discovery combines explicit VPN host routes with peer lists read through up to four saved, trusted SSH connections. It probes at most 128 known Tailscale addresses, never the whole address range. If the VPN exposes only broad routes and there is no saved connection yet, pair one computer first. [Details and validation](docs/TAILSCALE.md)

</details>

<details>
<summary>Connect with your own SSH credentials</summary>

Choose **+ → 使用自己的 SSH**, enter the host, port and username, or select a host from LAN discovery. Use password or private-key authentication, verify the host fingerprint, then save the connection.

Tapping a saved connection authenticates over SSH before opening the **Tmux / 最近** (Recent) picker. Long-press a saved connection to edit or delete it. [Connection workflow](docs/LOCAL_SSH.md#create-a-connection-in-the-app)

</details>

<p align="center">
  <img src="assets/readme/preview.png" width="960" alt="Shello running Claude Code over SSH">
</p>
<p align="center"><sub>Claude Code over SSH in Shello.</sub></p>

## Features

- **SSH connections:** passwords or private keys, host-key verification, saved connection editing and LAN discovery.
- **QR pairing:** run one command on your computer, scan the QR code and verify SSH access.
- **Persistent remote sessions:** tmux reattachment, automatic reconnection and a background connection service.
- **Touch terminal:** Chinese IME, text selection, scrolling, direction controls and Ctrl / Esc / Tab shortcuts.
- **Session home:** thumbnails, reordering and closing; cached previews survive app restarts.
- **Remote agents:** project-grouped Claude Code / Codex history and a Claude Code / Codex Hook inbox for events and approvals.
- **Languages:** English and Simplified Chinese. Follow the system or choose a language in Settings.
- **Appearance:** Shello, black and light themes, font size and configurable toolbar controls.

## Download

| Item | Details |
| --- | --- |
| Current release | [v0.1.1](https://github.com/JackCaow/Shello/releases/tag/v0.1.1) |
| APK | [Download Shello · arm64-v8a](https://github.com/JackCaow/Shello/releases/download/v0.1.1/Shello-0.1.1-arm64-v8a.apk) |
| Android | Android 8.1 / API 27 or newer |
| Architecture | arm64-v8a only; no 32-bit ARM or x86 APK |
| Checksums | [SHA256SUMS](https://github.com/JackCaow/Shello/releases/download/v0.1.1/SHA256SUMS) |
| Future versions | [All releases](https://github.com/JackCaow/Shello/releases) |

Open the downloaded APK on your phone and follow Android's installation prompts. Public releases use a different certificate from early debug builds and **cannot overwrite those debug installs**. Uninstalling removes local connections and credentials; preserve development installs containing important data. See [signing and upgrade notes](docs/RELEASING.md).

## Claude Code and Codex

These CLIs run on the remote computer. Install them and complete their login there; Shello does not include model access or a subscription.

Run `claude` or `codex` in the terminal, or use **最近** to find and resume existing sessions by project. Claude Code and Codex events and approvals require installing or updating the helper through **设置 → Hook**. Start a new agent session afterwards. In Codex, review and trust the Shello hooks through `/hooks`; explicitly disabled hooks must be enabled in Codex. The inbox groups sessions by state, supports search and agent/host/project filters, last-turn user/reply previews, individual approvals, archive/restore and attachment to the existing tmux terminal. Private snapshots survive app restarts; cached or offline requests cannot grant approval. The usage sheet reads real signed-in Claude/Codex account quota windows from the host, with reset times and explicit unavailable/stale states. Codex interactive question replies remain unsupported. [Agent session details](docs/HOME_AND_AGENTS.md)

## FAQ

**Cannot find or pair with a computer?** Check SSH, your username, network reachability and firewall rules. Guest Wi-Fi isolation, VPNs or multiple interfaces can interfere; use `--host` to select a LAN IPv4 address. Run the script again if the QR code expires.

**Will connections survive backgrounding or screen lock?** Vendor battery policies may interrupt SSH. tmux preserves remote processes for reattachment when networking returns or the app reopens. A stopped tmux session or powered-off host cannot preserve those processes. [Device recovery limits](docs/DEVICE_RECOVERY.md)

**Why does a card say “暂无预览” (No preview)?** Open the terminal first. Visible terminals checkpoint every five seconds and on minimize/background; closing a tab deletes its preview. A thumbnail shows the last captured screen, not live connection status.

**Are all emoji and terminal apps supported?** Glyph availability depends on Android's fonts. Older devices may lack newer emoji, and not every IME, complex cluster or TUI combination is validated. See [validation and limitations](docs/VALIDATION.md).

## Compatibility and limits

- USB/Bluetooth keyboard hotplug, dead keys and OS-level shortcuts are experimental; injected key tests do not replace real hardware acceptance.
- Draft text and staged attachments are retained for the running service, but are not restored after process death. Uploaded files are not automatically deleted from the host.
- Remote recovery depends on the host and tmux remaining alive. Vendor battery restrictions can interrupt SSH; reopen the app to reconnect.
- Codex interactive question replies and cloud push are not supported. Account usage adapters depend on the installed CLI/provider and may report unavailable.
- See [validation](docs/VALIDATION.md) and [stable release acceptance](docs/evidence/releases/v0.1.1/README.md) for tested scope.

## Development

| Topic | Documentation |
| --- | --- |
| Build from source and embed the library | [Developer guide](docs/DEVELOPMENT.md) |
| Threading, input and lifecycle | [API contract](docs/API.md) |
| Test evidence and limitations | [Validation](docs/VALIDATION.md) |
| APK signing and releases | [Release guide](docs/RELEASING.md) |
| Report an issue | [GitHub Issues](https://github.com/JackCaow/Shello/issues) |

The embeddable terminal library API remains experimental and may change.

`terminal` contains VT, native views and IME input; `sample` contains the Shello app, SSH and tmux integration. Library consumers supply their own transport. **No Maven package is publicly published.**

Shello is an independent project powered by the Ghostty terminal engine. It is not an official Ghostty client.

Licensed under the [MIT License](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for dependency licenses.
