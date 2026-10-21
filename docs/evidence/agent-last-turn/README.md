# Last-turn message acceptance — 2026-10-21

- [24 isolated Python checks](python-tests.txt): bounded UTF-8 previews for both providers, no transcript-path export, and latest prompt preservation through history pruning.
- [API 27: nine model/UI checks](api27-tests.txt), [physical API 36: nine checks](phone-tests.txt): paired messages, new-turn reset, delayed old-turn rejection, message search, multiline/emoji details and existing approvals.
- Real [Claude Code](claude-phone.txt) and [Codex](codex-phone.txt): actual CLI hook payloads traveled over a saved pinned Tailscale SSH profile to the physical phone. Assertions check the card user-message line, both selectable detail messages and a fresh private cache read. Codex hook trust was reviewed normally in an isolated test home; unrelated hooks/configuration were preserved.

Screenshots and CLI logs remain local under `build/last-turn-*`; personal inbox content is not committed. Only temporary test projects and tmux sessions are removed after validation. Debug/test APK, R8 release and debug lint builds passed. No release tag or Maven publication.
