# Sample homepage, pairing and agent history

These features belong to the sample; the terminal AAR remains transport-independent.

Tapping a saved SSH host first displays Connecting on its homepage card. Only after SSH authentication and the remote list query completes does the **Tmux / Recent** picker open. Failed authentication/network attempts remain on the homepage; leaving or starting another request cancels the pending sheet. A connected server without tmux offers Skip to open a plain shell. Tmux lists real remote sessions; create opens an independent tmux session, while Skip opens a plain shell. Recent reads Claude Code / Codex history metadata, groups by working directory, and displays project, parent directory, activity time and provider. Tap a row to resume its latest session; tap the count badge to choose the latest session from another agent provider in that project. Missing directories or CLI executables produce an error rather than silently starting a different session.

The host helper reads Claude project indexes and bounded JSONL headers, or the Codex SQLite index in read-only mode with legacy JSONL fallback. It returns identifiers, directories and timestamps, not conversation text. The recent window is bounded to 200 records. Custom history roots and future history format changes are not yet supported. Resume invokes `claude --resume UUID` or `codex resume UUID` in the recorded directory. No prompt or permission-bypass flags are added. An isolated stable tmux wrapper keeps the same process across SSH reconnections, including when its metadata ages out of the recent window. CLI trust/login prompts remain visible.

## Pairing

On an SSH-enabled Mac/Linux host with Python 3.9+, OpenSSL and tmux, run as your SSH user:

```sh
curl -fsSL https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.sh | bash
```

The standalone script embeds the Python helper, creates a private virtual environment under `~/.local/share/shello/pair`, installs `qrcode==8.2`, and starts the temporary pairing service. It does not enable SSH or install system packages. On Linux, `python3-venv` may be needed. To select an interface or custom SSH port:

```sh
curl -fsSL https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.sh | bash -s -- --host 192.168.1.10 --ssh-port 22
```

Use `--help` for all options. Ctrl-C stops the service and removes its temporary helper/certificate. Regenerate the checked-in launcher with `python3 scripts/generate-pair-script.py` whenever `companion/pair.py` changes. The release-tag URL (`v0.1.0-alpha02` in place of `main`) selects that version's script.

Open Add → Scan to pair on Android. The QR pins an ephemeral HTTPS certificate and a short-lived one-use token. The phone generates its RSA key locally and sends only the public key. The helper appends it to `authorized_keys`, returns SSH host keys, and the phone verifies SSH and tmux before saving the encrypted profile. Camera scanning and scanning from a selected image are supported. QR/token files must never be committed or shared. This is Shello's own protocol, not compatible with Moshi QR codes.

## Hook and settings

Settings → Hook installs/updates the helper over pinned SSH, backs up and merges `~/.claude/settings.json` and `$CODEX_HOME/hooks.json` (default `~/.codex/hooks.json`), and preserves unrelated hooks, including other installed integrations. Uninstall removes only this helper's registrations. New Claude sessions receive approval, question, notification and completion hooks. Permission requests require explicit Allow once / Deny; a timeout supplies no grant. Question responses preserve the original question payload. Inbox events synchronize over SSH every five seconds while its UI or an active SSH service runs. There is no cloud push service; force-stop/offline delivery resumes on reopening.

The helper also supplies the Recent page; update it from Settings after upgrading the app. Codex history/resume, approval hooks and lifecycle events are supported; Codex interactive question replies remain unsupported. The inbox and account quota views are described below. Text draft settings are named explicitly; they are not a transcript/chat renderer. Other working settings include terminal theme, font size, toolbar buttons, selection, notification preferences and system battery settings.

Codex receives `SessionStart`, `UserPromptSubmit`, `PermissionRequest`, `Stop`, `Interrupt` and `SessionEnd` events. The inbox identifies each event as Codex or Claude Code. Codex approvals return only the documented single-request allow/deny decision. Expiry or a disconnected phone leaves the usual terminal approval flow intact; no persistent permission rules are granted. The UserPromptSubmit hook now retains a bounded preview of the user message, and Stop retains the last assistant reply (up to 8 KiB of UTF-8 per message, plus a truncation marker). These form the last-turn pair on the card and in session details. Transcript paths/files are not read or exported. A new prompt clears the previous reply from the current-turn view; known Codex turn IDs prevent late replies from an older turn replacing it.

After installing or updating the helper, start a new Codex session and review the Shello hooks through `/hooks`. Codex requires trust for non-managed hooks and skips unreviewed definitions. If the local Codex configuration explicitly disables `features.hooks`, enable it in Codex. Shello does not alter `config.toml`, daemon options, approval policies or trust records. The tested CLIs are 0.163.0 and 0.163.0-alpha.10.2; older versions without lifecycle hooks are not supported by this integration. See the [official Hooks contract](https://learn.chatgpt.com/docs/hooks).

## Session inbox and account usage

The inbox groups events by saved SSH profile, provider and session ID. Working, Needs you, Done, Interrupted, Offline/stale and Archived sections reflect hook events and host process identity; unknown or dead processes are not presented as confirmed working. Every pending request retains its own event ID, command and deadline. Opening a session reveals its requests and recent event timeline; Allow once / Deny operates on exactly one request. Claude question answers retain the existing per-question flow. Search and agent/host/project filters can be combined and survive Activity recreation.

Completed sessions can be archived or restored. New activity revives an explicitly archived session. Unresolved requests are not auto-archived. Inactive completed sessions archive after ten minutes; other inactive sessions archive after six hours. Restoring postpones automatic archive for ten minutes. A session recorded inside tmux offers Open terminal, which attaches to that exact session through the saved pinned SSH profile. Sessions outside tmux explain that no live terminal can be attached and can be resumed separately from Recent.

An atomic, bounded snapshot in Android private no-backup storage retains session metadata, bounded last-user/assistant message previews, read/archive choices and sanitized quota snapshots. It is not a full transcript. Large tool payloads may be omitted from disk. Cached/offline/expired requests cannot be approved; a new successful SSH poll is required. Removing a saved host removes its retained inbox data during synchronization. Host events have bounded 24-hour retention; the phone retains bounded history for up to seven days. No secret credentials are exported by the usage collectors.

The bottom Claude/Codex entries open only that provider’s accounts and diagnostics; the top usage button opens all providers. Usage collection is optional (toggle in the usage sheet). Account quota windows are distinct from task progress or context remaining. Codex uses its local app-server `account/read` and `account/rateLimits/read` APIs. Claude uses its locally signed-in OAuth quota endpoint; this is a compatibility adapter rather than a stable public Anthropic API contract. Login, network or schema failures show unavailable instead of a fabricated zero. Returned windows, reset times and snapshot ages are shown; the same account seen on several hosts is deduplicated. Collection is limited to once per minute on each host, including manual refresh.

On macOS, Hook installation adds the user LaunchAgent `dev.shello.usage` at `~/Library/LaunchAgents/dev.shello.usage.plist`. It collects sanitized quotas in the logged-in user context so the existing Keychain login remains host-side; SSH requests wake it through a private request file. It does not run a periodic host poll, open a network listener or read refresh tokens. Hook uninstall removes this helper's LaunchAgent as well as its hook registrations. A locked/missing login session or unavailable Keychain remains an explicit failure state. Linux reads Claude's local credential file. Existing hosts must update Hook after installing this app version.

Host diagnostics distinguish registration from trust: a registered Codex hook still requires review in `/hooks`. Inbox synchronization runs while its UI or an SSH service is active. Notifications cover new approvals and recent completions; Android notification permission, background execution and network access still apply. Optional Keep screen on is scoped to the visible inbox. There is no cloud relay or delivery guarantee after force-stop; reopening restores the snapshot and resumes SSH synchronization.

## Validation (2026-10-21)

Latest session-inbox acceptance: real Codex allow/deny, Claude completion and both provider quotas, pinned Tailscale terminal attachment with unchanged PID, controlled SSH loss/recovery and process restart passed on Xiaomi API 36. API 27/API 35 each passed eight inbox checks; API 35 passed two landscape/recreation checks. See [results](evidence/agent-inbox/README.md). The older homepage/pairing coverage below remains separately scoped.

- 11 isolated Python checks: approval exactly once, expiry, Chinese question input, Hook config merge/uninstall, metadata privacy/deduplication, read-only SQLite, malformed/legacy history, resume argv/cwd and existing tmux reattach.
- API 27 and API 35: all 13 sample connection tests passed, including TLS pin rejection, one-use QR replay rejection, real OpenSSH pairing, real tmux PID preservation, and isolated SSH Hook events.
- The final phone run passed the new connect-before-sheet/cancellation/failure check and all connection, QR, tmux and Hook tests. The existing drag-reorder test timed out while the Activity had lost focus (12/13 passed); complete uninterrupted physical UI acceptance remains outstanding.
- Xiaomi phone/API 36: actual LAN QR image pairing installed a phone-generated public key and connected to local SSH; UI installed Hook, received a real Claude completion, and explicitly approved a harmless Bash request which then completed.
- Recent on the phone lists actual Claude/Codex metadata. A dedicated Claude history was resumed in its original directory and displayed the previous test exchange. Live Codex interactive resume and live AskUserQuestion UI are not yet verified; the latter was unavailable in the tested Claude `-p` session, so only its Hook protocol was verified.

This is a Moshi-inspired implementation, not a claim of complete feature/pixel parity. No Maven package is remotely published.
