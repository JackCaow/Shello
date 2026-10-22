import XCTest
import ShelloCore
@testable import ShelloServices

@MainActor
final class AgentInboxTests: XCTestCase {
    func event(_ id: String, _ kind: String, created: Double, extra: JSON = [:], profile: String = "p") -> AgentEvent {
        var d: JSON = ["id": id, "hook_event_name": kind, "session_id": "s1", "provider": "claude", "created": created, "localCreated": created, "cached": false, "offline": false, "remainingMs": 0, "process_alive": true]
        for (k, v) in extra { d[k] = v }
        return AgentEvent(profile: profile, host: "Mac", data: d)
    }
    var defaults: UserDefaults { UserDefaults(suiteName: UUID().uuidString)! }

    func testStatesFromHookEvents() {
        let now = Date(); let t = now.timeIntervalSince1970
        let working = AgentInbox.sessions([event("1", "UserPromptSubmit", created: t - 5, extra: ["prompt_preview": "fix bug", "turn_id": "a"])], defaults: defaults, now: now)
        XCTAssertEqual(working.first?.state, .working); XCTAssertEqual(working.first?.lastPrompt, "fix bug")
        let done = AgentInbox.sessions([event("1", "UserPromptSubmit", created: t - 9, extra: ["turn_id": "a"]), event("2", "Stop", created: t - 5, extra: ["turn_id": "a", "last_assistant_message": "Done!"])], defaults: defaults, now: now)
        XCTAssertEqual(done.first?.state, .done); XCTAssertEqual(done.first?.summary, "Done!")
        let pending = AgentInbox.sessions([event("3", "PermissionRequest", created: t - 1, extra: ["status": "pending", "remainingMs": 60000])], defaults: defaults, now: now)
        XCTAssertEqual(pending.first?.state, .needsYou); XCTAssertEqual(pending.first?.requests.count, 1)
        let stale = AgentInbox.sessions([event("4", "PostToolUse", created: t - 500, extra: ["process_alive": false])], defaults: defaults, now: now)
        XCTAssertEqual(stale.first?.state, .offline)
        let interrupted = AgentInbox.sessions([event("5", "StopFailure", created: t - 5)], defaults: defaults, now: now)
        XCTAssertEqual(interrupted.first?.state, .interrupted)
    }
    func testCachedRequestsCannotBeAnswered() {
        let e = event("9", "PermissionRequest", created: Date().timeIntervalSince1970, extra: ["status": "pending", "remainingMs": 60000, "cached": true])
        XCTAssertFalse(e.isPending())
        let live = event("9", "PermissionRequest", created: Date().timeIntervalSince1970, extra: ["status": "pending", "remainingMs": 60000])
        XCTAssertTrue(live.isPending())
        XCTAssertFalse(event("9", "PermissionRequest", created: 0, extra: ["status": "pending", "remainingMs": 0]).isPending())
    }
    func testOrderingArchiveAndFilters() {
        let now = Date(); let t = now.timeIntervalSince1970; let d = defaults
        let old = event("1", "Stop", created: t - 30000, extra: ["session_id": "old"]), fresh = event("2", "UserPromptSubmit", created: t - 3, extra: ["session_id": "fresh", "cwd": "/home/me/app"])
        let s = AgentInbox.sessions([old, fresh], defaults: d, now: now)
        XCTAssertEqual(s.map(\.sessionID), ["fresh", "old"]); XCTAssertEqual(s[1].state, .archived)
        XCTAssertEqual(s[0].project, "app")
        XCTAssertTrue(AgentInbox.matches(s[0], query: "APP", provider: nil, host: nil, project: nil))
        XCTAssertFalse(AgentInbox.matches(s[0], query: "zzz", provider: nil, host: nil, project: nil))
        XCTAssertFalse(AgentInbox.matches(s[0], query: nil, provider: "codex", host: nil, project: nil))
    }
    func testTargetFromTmuxSession() {
        let s = AgentInbox.sessions([event("1", "Stop", created: Date().timeIntervalSince1970, extra: ["tmux_session": "work"])], defaults: defaults)
        XCTAssertEqual(s.first?.target, "=work")
    }
}

/// Real hook script over the test sshd, isolated from the developer's Claude/Codex settings.
@MainActor
final class AgentHubIntegrationTests: XCTestCase {
    func testInstallPollUsageAndUninstall() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let dir = env["SHELLO_SSHD_DIR"], let script = env["SHELLO_HOOK_SCRIPT"], let state = env["SHELLO_HOOK_STATE"] else { throw XCTSkip("hook harness not configured") }
        let user = env["SHELLO_SSHD_USER"] ?? NSUserName()
        let store = try ProfileStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), secrets: MemorySecretStore())
        let f = String(decoding: try Data(contentsOf: URL(fileURLWithPath: dir + "/host_ed25519.pub")), as: UTF8.self).split(separator: " ")
        let profile = SSHProfile(name: "Test Mac", host: "127.0.0.1", port: 2299, user: user, auth: .key)
        try store.save(profile, secrets: SSHSecrets(privateKey: try Data(contentsOf: URL(fileURLWithPath: dir + "/user_ed25519")), knownHosts: Data("[127.0.0.1]:2299 \(f[0]) \(f[1])\n".utf8)))
        let isolated = "GHOSTTY_HOOK_STATE=\(state) GHOSTTY_CLAUDE_SETTINGS=\(state)/claude.json GHOSTTY_CODEX_SETTINGS=\(state)/codex.json "
        let command = isolated + "python3 \"\(state)/ghostty_hook.py\" "
        let cfg = AgentHub.Config(script: try Data(contentsOf: URL(fileURLWithPath: script)), command: command,
                                  installCommand: "mkdir -p \"\(state)\" && cat > \"\(state)/ghostty_hook.py\" && \(command)install")
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let hub = AgentHub(store: store, defaults: defaults, config: cfg, cacheURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json"))
        let installed = await hub.configure(profile: profile.id, install: true)
        XCTAssertTrue(installed); XCTAssertTrue(hub.enabled(profile.id))
        XCTAssertEqual(hub.hostStates[profile.id], .connected)
        XCTAssertNotNil(hub.diagnostics[profile.id]?["capabilities"])
        // Drop a fake pending request into the spool through the hook protocol, then answer it.
        let event = #"{"hook_event_name":"PermissionRequest","session_id":"sess-1","tool_name":"Bash","tool_input":{"command":"ls"},"cwd":"/tmp/demo"}"#
        // Darwin nohup can fail to detach from a console on a non-PTY SSH channel.
        // Spawn a separate session with explicit file descriptors instead of shell job control.
        let payload = ShellQuote.quote(state + "/pending-request.input")
        let launcher = "import subprocess,sys; subprocess.Popen([sys.executable,sys.argv[1],'hook'], stdin=open(sys.argv[2],'rb'), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True); print('started')"
        let hookRun = try await RemoteCommand.run(profileID: profile.id, store: store, command: "umask 077; cat > \(payload) && env \(isolated)python3 -c \(ShellQuote.quote(launcher)) \(ShellQuote.quote(state + "/ghostty_hook.py")) \(payload)", input: Data(event.utf8))
        XCTAssertTrue(String(decoding: hookRun, as: UTF8.self).contains("started"))
        let deadline = Date().addingTimeInterval(10)
        repeat {
            await hub.refresh()
            if hub.events.contains(where: { $0.isPending() }) { break }
            try await Task.sleep(nanoseconds: 200_000_000)
        } while Date() < deadline
        let pending = hub.events.first { $0.isPending() }
        XCTAssertNotNil(pending, "the hook should have spooled a pending request")
        if let pending {
            let ok = await hub.respond(pending, behavior: "deny")
            XCTAssertTrue(ok)
        }
        let removed = await hub.configure(profile: profile.id, install: false)
        XCTAssertTrue(removed)
    }
}
