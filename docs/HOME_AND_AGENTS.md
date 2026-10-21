# Sample homepage, pairing and agent history

These features belong to the sample; the terminal AAR remains transport-independent.

Tapping a saved SSH host first displays Connecting on its homepage card. Only after SSH authentication and the remote list query completes does the **Tmux / Recent** picker open. Failed authentication/network attempts remain on the homepage; leaving or starting another request cancels the pending sheet. A connected server without tmux offers Skip to open a plain shell. Tmux lists real remote sessions; create opens an independent tmux session, while Skip opens a plain shell. Recent reads Claude Code / Codex history metadata, groups by working directory, and displays project, parent directory, activity time and provider. Tap a row to resume its latest session; tap the count badge to choose the latest session from another agent provider in that project. Missing directories or CLI executables produce an error rather than silently starting a different session.

The host helper reads Claude project indexes and bounded JSONL headers, or the Codex SQLite index in read-only mode with legacy JSONL fallback. It returns identifiers, directories and timestamps, not conversation text. The recent window is bounded to 200 records. Custom history roots and future history format changes are not yet supported. Resume invokes `claude --resume UUID` or `codex resume UUID` in the recorded directory. No prompt or permission-bypass flags are added. An isolated stable tmux wrapper keeps the same process across SSH reconnections, including when its metadata ages out of the recent window. CLI trust/login prompts remain visible.

## Pairing

On an SSH-enabled Mac/Linux host with Python 3, OpenSSL and tmux:

```sh
python3 -m venv ~/.local/share/ghostty-pair-venv
~/.local/share/ghostty-pair-venv/bin/pip install qrcode==8.2
~/.local/share/ghostty-pair-venv/bin/python companion/pair.py
```

Open Add → Scan to pair on Android. The QR pins an ephemeral HTTPS certificate and a short-lived one-use token. The phone generates its RSA key locally and sends only the public key. The helper appends it to `authorized_keys`, returns SSH host keys, and the phone verifies SSH and tmux before saving the encrypted profile. Camera scanning and scanning from a selected image are supported. QR/token files must never be committed or shared. This is Shello's own protocol, not compatible with Moshi QR codes.

## Hook and settings

Settings → Hook installs/updates the helper over pinned SSH, backs up and merges `~/.claude/settings.json`, and preserves unrelated hooks. Uninstall removes only this helper's registrations. New Claude sessions receive approval, question, notification and completion hooks. Permission requests require explicit Allow once / Deny; a timeout supplies no grant. Question responses preserve the original question payload. Inbox events synchronize over SSH every five seconds while its UI or an active SSH service runs. There is no cloud push service; force-stop/offline delivery resumes on reopening.

The helper also supplies the Recent page; update it from Settings after upgrading the app. Codex history/resume is supported, but Codex approval Hook integration and provider usage quotas are not implemented. Text draft settings are named explicitly; they are not a transcript/chat renderer. Other working settings include terminal theme, font size, toolbar buttons, selection, notification preferences and system battery settings.

## Validation (2026-10-21)

- 11 isolated Python checks: approval exactly once, expiry, Chinese question input, Hook config merge/uninstall, metadata privacy/deduplication, read-only SQLite, malformed/legacy history, resume argv/cwd and existing tmux reattach.
- API 27 and API 35: all 13 sample connection tests passed, including TLS pin rejection, one-use QR replay rejection, real OpenSSH pairing, real tmux PID preservation, and isolated SSH Hook events.
- The final phone run passed the new connect-before-sheet/cancellation/failure check and all connection, QR, tmux and Hook tests. The existing drag-reorder test timed out while the Activity had lost focus (12/13 passed); complete uninterrupted physical UI acceptance remains outstanding.
- Xiaomi phone/API 36: actual LAN QR image pairing installed a phone-generated public key and connected to local SSH; UI installed Hook, received a real Claude completion, and explicitly approved a harmless Bash request which then completed.
- Recent on the phone lists actual Claude/Codex metadata. A dedicated Claude history was resumed in its original directory and displayed the previous test exchange. Live Codex interactive resume and live AskUserQuestion UI are not yet verified; the latter was unavailable in the tested Claude `-p` session, so only its Hook protocol was verified.

This is a Moshi-inspired implementation, not a claim of complete feature/pixel parity. No Maven package is remotely published.
