<p align="center">
  <img src="assets/readme/icon.png" width="112" height="112" alt="Shello app icon">
</p>
<h1 align="center">Shello</h1>
<p align="center"><strong>Your remote terminal, in your pocket.</strong></p>
<p align="center">Native Android terminal · SSH + tmux · QR pairing · Claude Code / Codex sessions</p>
<p align="center">
  <a href="https://github.com/JackCaow/Shello/releases">Download APK</a> ·
  <a href="#quick-pairing">Quick pairing</a> ·
  <a href="docs/DEVELOPMENT.md">Build & embed</a> ·
  <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <img src="assets/readme/preview.png" width="960" alt="Shello running Claude Code over SSH">
</p>
<p align="center"><sub>Claude Code over SSH in Shello.</sub></p>

Shello is an open-source Android SSH terminal powered by **Ghostty VT and native Android rendering**. Connect to your computer or server, use shell tools and terminal applications, and keep remote work in tmux. You can also browse and resume remote Claude Code / Codex sessions.

The project includes an embeddable terminal library. It is currently **Alpha**: APIs and interactions are still evolving.

## Download

| Item | Details |
| --- | --- |
| Current prerelease | [v0.1.0-alpha05](https://github.com/JackCaow/Shello/releases/tag/v0.1.0-alpha05) |
| APK | [Download Shello · arm64-v8a](https://github.com/JackCaow/Shello/releases/download/v0.1.0-alpha05/Shello-0.1.0-alpha05-arm64-v8a.apk) |
| Android | Android 8.1 / API 27 or newer |
| Architecture | arm64-v8a only; no 32-bit ARM or x86 APK |
| Checksums | [SHA256SUMS](https://github.com/JackCaow/Shello/releases/download/v0.1.0-alpha05/SHA256SUMS) |
| Future versions | [All releases](https://github.com/JackCaow/Shello/releases) |

Open the downloaded APK on your phone and follow Android's installation prompts. Public releases use a different certificate from early debug builds and **cannot overwrite those debug installs**. Uninstalling removes local connections and credentials; preserve development installs containing important data. See [signing and upgrade notes](docs/RELEASING.md).

## Features

- **SSH connections:** passwords or private keys, host-key verification, saved connection editing and LAN discovery.
- **QR pairing:** run one command on your computer, scan the QR code and verify SSH access.
- **Persistent remote sessions:** tmux reattachment, automatic reconnection and a background connection service.
- **Touch terminal:** Chinese IME, text selection, scrolling, direction controls and Ctrl / Esc / Tab shortcuts.
- **Session home:** thumbnails, reordering and closing; cached previews survive app restarts.
- **Remote agents:** project-grouped Claude Code / Codex history and a Claude Code Hook inbox for events and approvals.
- **Languages:** English and Simplified Chinese. Follow the system or choose a language in Settings.
- **Appearance:** Shello, black and light themes, font size and configurable toolbar controls.

## Quick pairing

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

Add `--port 8765` to use a fixed HTTPS pairing port when configuring a firewall. Pass `--help` for all options. To use the launcher shipped with the current APK, replace `main` in the URL with `v0.1.0-alpha05`.

### 3. Scan from Shello

1. Open **+ → 扫码轻松配对** (QR pairing).
2. Allow camera access and scan the QR code on your own computer.
3. Confirm the computer's address. Wait for public-key installation, SSH host-key verification and the tmux check.
4. Once saved, select an existing remote tmux session or create a new one.

The phone generates and stores its private key; only the public key goes to the computer. Existing `authorized_keys` entries are retained. The QR code contains a temporary token—do not share it publicly. After pairing, **Ctrl+C** closes the temporary service; the installed SSH public key remains. [Protocol and options](docs/HOME_AND_AGENTS.md#pairing)

## Connect manually

Choose **+ → 使用自己的 SSH**, enter the host, port and username, or select a host from LAN discovery. Use password or private-key authentication, verify the host fingerprint, then save the connection.

Tapping a saved connection authenticates over SSH before opening the **Tmux / 最近** (Recent) picker. Long-press a saved connection to edit or delete it. [Connection workflow](docs/LOCAL_SSH.md#create-a-connection-in-the-app)

## Claude Code and Codex

These CLIs run on the remote computer. Install them and complete their login there; Shello does not include model access or a subscription.

Run `claude` or `codex` in the terminal, or use **最近** to find and resume existing sessions by project. Claude Code events and approvals require installing the helper through **设置 → Hook**. Codex history/resume is supported; Codex approval hooks are not implemented. [Agent session details](docs/HOME_AND_AGENTS.md)

## FAQ

**Cannot find or pair with a computer?** Check SSH, your username, network reachability and firewall rules. Guest Wi-Fi isolation, VPNs or multiple interfaces can interfere; use `--host` to select a LAN IPv4 address. Run the script again if the QR code expires.

**Will connections survive backgrounding or screen lock?** Vendor battery policies may interrupt SSH. tmux preserves remote processes for reattachment when networking returns or the app reopens. A stopped tmux session or powered-off host cannot preserve those processes. [Device recovery limits](docs/DEVICE_RECOVERY.md)

**Why does a card say “暂无预览” (No preview)?** Open the terminal first. Visible terminals checkpoint every five seconds and on minimize/background; closing a tab deletes its preview. A thumbnail shows the last captured screen, not live connection status.

**Are all emoji and terminal apps supported?** Glyph availability depends on Android's fonts. Older devices may lack newer emoji, and not every IME, complex cluster or TUI combination is validated. See [validation and limitations](docs/VALIDATION.md).

## Development

| Topic | Documentation |
| --- | --- |
| Build from source and embed the library | [Developer guide](docs/DEVELOPMENT.md) |
| Threading, input and lifecycle | [API contract](docs/API.md) |
| Test evidence and limitations | [Validation](docs/VALIDATION.md) |
| APK signing and releases | [Release guide](docs/RELEASING.md) |
| Report an issue | [GitHub Issues](https://github.com/JackCaow/Shello/issues) |

`terminal` contains VT, native views and IME input; `sample` contains the Shello app, SSH and tmux integration. Library consumers supply their own transport. **No Maven package is publicly published.**

Shello is an independent project powered by the Ghostty terminal engine. It is not an official Ghostty client.

Licensed under the [MIT License](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for dependency licenses.
