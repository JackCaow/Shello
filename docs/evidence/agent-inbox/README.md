# Agent inbox acceptance — 2026-10-21

- [Physical API 36: 11 checks](phone-tests.txt)
- [API 27: 8 checks](api27-tests.txt), [API 35: 8 checks](api35-tests.txt)
- [API 35 landscape and Unicode draft recreation](landscape-tests.txt)
- [Real Codex Allow once](codex-allow.txt), [real Codex Deny](codex-deny.txt)
- [Real Claude completion and fresh account quotas](claude-usage-tests.txt)
- [Bottom Claude/Codex provider isolation and combined top entry](provider-usage-tests.txt)
- [Prepare archive](process-prepare.txt), [force-stop and restore](process-restore.txt)
- [Controlled SSH loss and recovery](ssh-recovery.txt)
- [22 isolated Python tests](python-tests.txt)

The real-host tests are opt-in and require an existing pinned saved SSH profile. They preserve it; temporary USB profiles and reverse mappings are removed. Personal account screenshots and host fixture paths are intentionally not included. The terminal test accepts only a reserved `shello_inbox_*` tmux fixture and verifies unchanged remote pane PID before closing its disposable app tab.

For local protocol/model checks, run `python3 -m unittest discover -s companion -p 'test_*.py'`, build `:sample:assembleDebug :sample:assembleDebugAndroidTest`, then run AgentInboxModelTest, AgentBoardUiTest and AgentInboxTest through AndroidJUnitRunner. Real-device tests read their opt-in instrumentation arguments from the corresponding test source; never pass passwords or private keys as runner arguments.
