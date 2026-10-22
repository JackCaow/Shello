import XCTest
import ShelloCore
import ShelloTerminal
@testable import ShelloSSH

/// Integration tests need an unprivileged sshd: scripts/ios-test-sshd.sh start <dir>, then
/// TEST_RUNNER_SHELLO_SSHD_DIR=<dir> TEST_RUNNER_SHELLO_SSHD_USER=$USER xcodebuild test ... Without it they are skipped.
final class SSHConnectionTests: XCTestCase {
    var dir = ""
    var port: Int { ProcessInfo.processInfo.environment["SHELLO_SSHD_PORT"].flatMap(Int.init) ?? 2299 }
    override func setUpWithError() throws {
        guard let d = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"] else { throw XCTSkip("SHELLO_SSHD_DIR not set") }
        dir = d
    }
    func key(_ name: String) throws -> Data { try Data(contentsOf: URL(fileURLWithPath: dir + "/" + name)) }
    func hostLine(_ file: String) throws -> String {
        let f = String(decoding: try key(file + ".pub"), as: UTF8.self).split(separator: " ")
        return "[127.0.0.1]:\(port) \(f[0]) \(f[1])\n"
    }
    func connection() -> SSHConnection { SSHConnection(host: "127.0.0.1", port: port, user: ProcessInfo.processInfo.environment["SHELLO_SSHD_USER"] ?? NSUserName()) }

    func testUnknownHostKeyThenTrusted() throws {
        let c = connection()
        var info: HostKeyInfo?
        do { try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: "") }
        catch SSHError.hostKeyUnknown(let i) { info = i }
        let i = try XCTUnwrap(info)
        XCTAssertTrue(i.fingerprint.contains("SHA256:"))
        let c2 = connection()
        try c2.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: i.knownHostsLine)
        XCTAssertEqual(String(decoding: try c2.run("echo hello").output, as: UTF8.self), "hello\n")
    }
    func testPinnedKeyAnyAlgorithmAndMismatch() throws {
        for host in ["host_ed25519", "host_rsa"] {
            let c = connection()
            try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine(host))
            XCTAssertEqual(try c.run("echo ok").status, 0)
        }
        let bad = "[127.0.0.1]:\(port) ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UmKboyWgZ+DLu5DUp6ok1DJ3kKIs\n"
        XCTAssertThrowsError(try connection().connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: bad)) {
            XCTAssertEqual($0 as? SSHError, .hostKeyMismatch)
        }
    }
    func testRSAKeyAuthentication() throws {
        let c = connection()
        try c.connect(credentials: .privateKey(try key("user_rsa"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
        XCTAssertEqual(String(decoding: try c.run("echo rsa").output, as: UTF8.self), "rsa\n")
    }
    func testEncryptedKeyAndWrongPassphrase() throws {
        let c = connection()
        try c.connect(credentials: .privateKey(try key("user_enc"), passphrase: "secret pass"), knownHosts: try hostLine("host_ed25519"))
        XCTAssertEqual(try c.run("true").status, 0)
        XCTAssertThrowsError(try connection().connect(credentials: .privateKey(try key("user_enc"), passphrase: "nope"), knownHosts: try hostLine("host_ed25519"))) {
            XCTAssertNotNil($0 as? SSHError)
        }
    }
    func testWrongUserFailsAuthentication() throws {
        let c = SSHConnection(host: "127.0.0.1", port: port, user: "nobody-\(UUID().uuidString.prefix(6))")
        XCTAssertThrowsError(try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))) {
            XCTAssertEqual($0 as? SSHError, .authentication)
        }
    }
    func testConnectionRefused() {
        let c = SSHConnection(host: "127.0.0.1", port: 1, user: "x")
        XCTAssertThrowsError(try c.connect(credentials: .password("x"), knownHosts: "", timeout: 3)) { XCTAssertTrue(($0 as? SSHError)?.isNetworkFailure == true) }
    }
    func testRunWithInputExitStatusAndLimit() throws {
        let c = connection()
        try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
        XCTAssertEqual(String(decoding: try c.run("cat", input: Data("piped".utf8)).output, as: UTF8.self), "piped")
        XCTAssertEqual(try c.run("exit 3").status, 3)
        XCTAssertThrowsError(try c.run("head -c 5000 /dev/zero", maxOutput: 100))
    }
    func testSFTPUpload() throws {
        let c = connection()
        try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
        let path = NSTemporaryDirectory() + "shello-upload-\(UUID().uuidString)"
        let payload = Data((0..<100_000).map { UInt8($0 % 251) })
        try c.upload(payload, to: path)
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), payload)
        try? FileManager.default.removeItem(atPath: path)
    }
    func collect(_ ch: ShellChannel) -> (get: () -> String, closed: XCTestExpectation) {
        let box = Box(), closed = expectation(description: "closed")
        ch.onOutput = { box.append($0) }
        ch.onClose = { _ in closed.fulfill() }
        return ({ box.text }, closed)
    }
    final class Box { private var d = Data(); let l = NSLock(); func append(_ x: Data) { l.lock(); d.append(x); l.unlock() }; var text: String { l.lock(); defer { l.unlock() }; return String(decoding: d, as: UTF8.self) } }
    func waitFor(_ s: String, _ get: () -> String, timeout: TimeInterval = 10) {
        let end = Date().addingTimeInterval(timeout)
        while !get().contains(s) && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.05)) }
        XCTAssertTrue(get().contains(s), "missing \(s) in \(get())")
    }
    func testInteractiveShellRoundTripResizeAndClose() throws {
        let c = connection()
        try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
        let ch = try ShellChannel(connection: c, command: nil, columns: 100, rows: 30)
        let (get, closed) = collect(ch)
        ch.start()
        XCTAssertTrue(ch.write(Data("stty size; echo MARK$((20+22))\n".utf8)))
        waitFor("30 100", get); waitFor("MARK42", get)
        ch.resize(columns: 70, rows: 20)
        XCTAssertTrue(ch.write(Data("stty size\n".utf8)))
        waitFor("20 70", get)
        ch.write(Data("exit\n".utf8))
        wait(for: [closed], timeout: 10)
    }

    func testChannelCanDeallocateWithOutputStillQueued() throws {
        for _ in 0..<20 {
            let c = connection()
            try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
            let callbacks = DispatchQueue(label: "shello.tests.held-output")
            callbacks.suspend()
            defer { callbacks.resume(); callbacks.sync {} }
            var channel: ShellChannel? = try ShellChannel(connection: c, command: "printf SHELLO_QUEUED_OUTPUT", columns: 80, rows: 24, callbackQueue: callbacks)
            weak var released = channel
            channel?.start()
            channel = nil
            let deadline = Date().addingTimeInterval(10)
            while released != nil && Date() < deadline { Thread.sleep(forTimeInterval: 0.01) }
            XCTAssertNil(released, "The finished pump must release its channel even while output callbacks are suspended")
            if released != nil { return }
        }
    }
    /// Wires a channel to a VT session exactly as the app does: output -> VT -> replies back to the PTY.
    final class Screen {
        let term = try! TerminalSession(columns: 80, rows: 24)
        func attach(_ ch: ShellChannel, closed: XCTestExpectation) {
            ch.onOutput = { [term] data in if let r = try? term.write(data), !r.isEmpty { ch.write(r) } }
            ch.onClose = { _ in closed.fulfill() }
        }
        var text: String {
            guard let f = try? term.frame() else { return "" }
            return (0..<f.rows).map { r in (0..<f.columns).map { f.cell(row: r, column: $0).text.isEmpty ? " " : f.cell(row: r, column: $0).text }.joined() }.joined(separator: "\n")
        }
    }
    func testShellRequestsUTF8Locale() throws {
        let c = connection()
        try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
        let ch = try ShellChannel(connection: c, command: nil, columns: 100, rows: 30)
        let (get, closed) = collect(ch)
        ch.start()
        ch.write(Data("echo LOCALE_IS_$LANG\n".utf8))
        waitFor("LOCALE_IS_en_US.UTF-8", get)           // the server accepts LANG (AcceptEnv), so non-ASCII input is echoed correctly
        ch.write(Data("exit\n".utf8)); wait(for: [closed], timeout: 10)
    }
    func testTmuxSessionPersistsAcrossReconnect() throws {
        let target = SessionTarget.fresh(tmux: true)
        func open() throws -> (ShellChannel, Screen, XCTestExpectation) {
            let c = connection()
            try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
            let ch = try ShellChannel(connection: c, command: try SessionTarget.command(target), columns: 80, rows: 24)
            let screen = Screen(), closed = expectation(description: "closed"); screen.attach(ch, closed: closed); ch.start()
            return (ch, screen, closed)
        }
        let (first, s1, closed1) = try open()
        first.write(Data("echo SHELLO_READY_$((6*7))\n".utf8))
        waitFor("SHELLO_READY_42", { s1.text }, timeout: 20)
        first.write(Data("echo PERSIST_$((6*7))\n".utf8)); waitFor("PERSIST_42", { s1.text })
        first.close(); wait(for: [closed1], timeout: 10)   // drop the SSH client; tmux keeps the session
        let (second, s2, closed2) = try open()
        waitFor("PERSIST_42", { s2.text }, timeout: 20)    // tmux redraws the preserved screen
        second.write(Data("exit\n".utf8)); wait(for: [closed2], timeout: 10)
        let c = connection()
        try c.connect(credentials: .privateKey(try key("user_ed25519"), passphrase: nil), knownHosts: try hostLine("host_ed25519"))
        let out = try c.run(TmuxSessions.command).output
        XCTAssertFalse(try TmuxSessions.parse(String(decoding: out, as: UTF8.self)).contains { $0.name == target })
    }
}
