import XCTest
import ShelloCore
import ShelloSSH
@testable import ShelloServices

@MainActor
final class SessionManagerTests: XCTestCase {
    var dir = "", user = ""
    var temp: URL!
    var tabsDebug = ""
    override func setUpWithError() throws {
        guard let d = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"] else { throw XCTSkip("SHELLO_SSHD_DIR not set") }
        dir = d; user = ProcessInfo.processInfo.environment["SHELLO_SSHD_USER"] ?? NSUserName()
        temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }
    func hostLine() throws -> String {
        let f = String(decoding: try Data(contentsOf: URL(fileURLWithPath: dir + "/host_ed25519.pub")), as: UTF8.self).split(separator: " ")
        return "[127.0.0.1]:2299 \(f[0]) \(f[1])\n"
    }
    func makeManager(knownHosts: String?, keyFile: String = "user_ed25519") throws -> (SessionManager, SSHProfile) {
        let store = try ProfileStore(directory: temp, secrets: MemorySecretStore())
        let profile = SSHProfile(name: "local", host: "127.0.0.1", port: 2299, user: user, auth: .key, tmux: true)
        let hosts = try knownHosts ?? hostLine()
        try store.save(profile, secrets: SSHSecrets(privateKey: try Data(contentsOf: URL(fileURLWithPath: dir + "/" + keyFile)), knownHosts: Data(hosts.utf8)))
        return (SessionManager(store: store, defaults: UserDefaults(suiteName: UUID().uuidString)!), profile)
    }
    func until(_ what: String, timeout: TimeInterval = 20, _ cond: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !cond() && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.05)) }
        XCTAssertTrue(cond(), "\(what) — tabs: \(tabsDebug)")
    }
    func screen(_ t: TerminalTab) -> String {
        guard let f = try? t.session.frame() else { return "" }
        return (0..<f.rows).map { r in (0..<f.columns).map { f.cell(row: r, column: $0).text.isEmpty ? " " : f.cell(row: r, column: $0).text }.joined() }.joined(separator: "\n")
    }

    func testConnectTypeDisconnectAndReattachKeepsTmuxState() throws {
        let (m, profile) = try makeManager(knownHosts: nil)
        let target = SessionTarget.fresh(tmux: true)
        let tab = try m.open(profile: profile, target: target)
        defer { tabsDebug = "" }
        tabsDebug = "\(tab.state)"
        until("connected") { self.tabsDebug = "\(tab.state)"; return tab.state == .connected }
        m.send(tab, Data("echo SHELLO_READY_$((6*7))\n".utf8))
        until("shell command round trip") { self.screen(tab).contains("SHELLO_READY_42") }
        m.send(tab, Data("echo KEEP_$((6*7))\n".utf8))
        until("echo") { self.screen(tab).contains("KEEP_42") }
        m.disconnect(tab)
        XCTAssertEqual(tab.state, .idle)
        m.connect(tab)                               // fast reconnect to the same tmux session
        until("reconnected") { tab.state == .connected }
        until("restored screen") { self.screen(tab).contains("KEEP_42") }
        m.send(tab, Data("exit\n".utf8))
        until("closed") { if case .failed = tab.state { return true }; return false }
        m.close(tab); XCTAssertTrue(m.tabs.isEmpty)
    }
    func testChangedHostKeyIsRefused() throws {
        let (m, profile) = try makeManager(knownHosts: nil)
        var (p, s) = try m.store.read(profile.id)
        s.knownHosts = Data("[127.0.0.1]:2299 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UmKboyWgZ+DLu5DUp6ok1DJ3kKIs\n".utf8)
        try m.store.save(p, secrets: s)
        let tab = try m.open(profile: p, target: SessionTarget.fresh(tmux: false))
        until("mismatch") { if case .failed(.hostKeyMismatch, let retry) = tab.state { return !retry }; return false }
    }
    func testWrongKeyFailsAuthentication() throws {
        let (m, profile) = try makeManager(knownHosts: nil, keyFile: "user_enc")   // encrypted key without passphrase
        let tab = try m.open(profile: profile, target: SessionTarget.fresh(tmux: false))
        until("failed") { if case .failed = tab.state { return true }; return false }
        if case .failed(let f, let retry) = tab.state { XCTAssertNotEqual(f, .network); XCTAssertFalse(retry) }
    }
    func testPlainShellAndOrderPersistence() throws {
        let (m, profile) = try makeManager(knownHosts: nil)
        let a = try m.open(profile: profile, target: SessionTarget.fresh(tmux: false))
        let b = try m.open(profile: profile, target: "=other", connect: false)
        until("plain connected") { a.state == .connected }
        m.send(a, Data("echo PLAIN_OK\n".utf8)); until("plain echo") { self.screen(a).contains("PLAIN_OK") }
        XCTAssertEqual(m.tabs.map(\.id), [a.id, b.id]); XCTAssertEqual(m.selectedID, b.id)
        let d = UserDefaults(suiteName: "persist-\(UUID().uuidString)")!
        let m2 = SessionManager(store: m.store, defaults: d)
        _ = try m2.open(profile: profile, target: "=other", connect: false)
        let m3 = SessionManager(store: m.store, defaults: d); m3.restore()
        XCTAssertEqual(m3.tabs.map(\.id), [b.id])
        m.closeAll()
    }
    func testSameTargetFocusesExistingTab() throws {
        let (m, profile) = try makeManager(knownHosts: nil)
        let a = try m.open(profile: profile, target: "=same", connect: false)
        let b = try m.open(profile: profile, target: "=same", connect: false)
        XCTAssertTrue(a === b); XCTAssertEqual(m.tabs.count, 1)
    }
}
