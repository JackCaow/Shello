import XCTest
import ShelloCore
import ShelloSSH
@testable import ShelloServices

final class ConnectionSetupTests: XCTestCase {
    var dir = "", user = ""
    override func setUpWithError() throws {
        guard let d = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"] else { throw XCTSkip("SHELLO_SSHD_DIR not set") }
        dir = d; user = ProcessInfo.processInfo.environment["SHELLO_SSHD_USER"] ?? NSUserName()
    }
    func profile(auth: AuthMethod = .key) -> SSHProfile { SSHProfile(name: "l", host: "127.0.0.1", port: 2299, user: user, auth: auth, tmux: true) }
    func secrets(_ pin: String, key: String = "user_ed25519") throws -> SSHSecrets {
        SSHSecrets(privateKey: try Data(contentsOf: URL(fileURLWithPath: dir + "/" + key)), knownHosts: Data(pin.utf8))
    }
    func testTrustFlowThenVerified() async throws {
        guard case .trustNeeded(let info) = await ConnectionSetup.verify(profile: profile(), secrets: try secrets("")) else { return XCTFail("expected trust prompt") }
        XCTAssertTrue(info.fingerprint.hasPrefix("ssh-"))
        let pinned = try secrets(info.knownHostsLine)
        let outcome = await ConnectionSetup.verify(profile: profile(), secrets: pinned)
        XCTAssertEqual(outcome, .verified)
        let bad = await ConnectionSetup.verify(profile: profile(), secrets: try secrets("[127.0.0.1]:2299 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UmKboyWgZ+DLu5DUp6ok1DJ3kKIs\n"))
        XCTAssertEqual(bad, .failed(.hostKeyMismatch))
    }
    func testAuthFailureAndRefused() async throws {
        guard case .trustNeeded(let info) = await ConnectionSetup.verify(profile: profile(), secrets: try secrets("")) else { return XCTFail() }
        var p = profile(); p.user = "nobody-\(UUID().uuidString.prefix(5))"
        let auth = await ConnectionSetup.verify(profile: p, secrets: try secrets(info.knownHostsLine))
        XCTAssertEqual(auth, .failed(.authentication))
        var q = profile(); q.port = 1
        let net = await ConnectionSetup.verify(profile: q, secrets: try secrets(info.knownHostsLine), timeout: 3)
        XCTAssertEqual(net, .failed(.network))
    }
    func testListTmuxAndRemoteCommand() async throws {
        let store = try ProfileStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), secrets: MemorySecretStore())
        guard case .trustNeeded(let info) = await ConnectionSetup.verify(profile: profile(), secrets: try secrets("")) else { return XCTFail() }
        let p = profile(); try store.save(p, secrets: try secrets(info.knownHostsLine))
        let out = try await RemoteCommand.run(profileID: p.id, store: store, command: "cat", input: Data("abc".utf8))
        XCTAssertEqual(String(decoding: out, as: UTF8.self), "abc")
        let list = try await ConnectionSetup.listTmux(profileID: p.id, store: store)
        XCTAssertNotNil(list)
        do { _ = try await RemoteCommand.run(profileID: p.id, store: store, command: "exit 4", input: nil); XCTFail() } catch {}
    }
}

/// Password authentication against scripts/ios-test-passwd-server.py (paramiko; the macOS sshd cannot do PAM as a normal user).
final class PasswordAuthTests: XCTestCase {
    func setup() throws -> (String, SSHProfile) {
        guard let dir = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"], let pub = try? String(contentsOfFile: dir + "/passwd_host.pub").trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ") else { throw XCTSkip("password server not running") }
        return ("[127.0.0.1]:2398 \(pub[0]) \(pub[1])\n", SSHProfile(name: "pw", host: "127.0.0.1", port: 2398, user: "tester", auth: .password, tmux: false))
    }
    func testCorrectAndWrongPassword() async throws {
        let (pin, p) = try setup()
        let ok = await ConnectionSetup.verify(profile: p, secrets: SSHSecrets(password: "s3cret", knownHosts: Data(pin.utf8)))
        XCTAssertEqual(ok, .verified)
        let bad = await ConnectionSetup.verify(profile: p, secrets: SSHSecrets(password: "wrong", knownHosts: Data(pin.utf8)))
        XCTAssertEqual(bad, .failed(.authentication))
        guard case .trustNeeded(let info) = await ConnectionSetup.verify(profile: p, secrets: SSHSecrets(password: "s3cret")) else { return XCTFail("first contact must ask for trust") }
        XCTAssertEqual(info.knownHostsLine, pin)
    }
    func testPasswordSessionRunsCommands() async throws {
        let (pin, p) = try setup()
        let store = try ProfileStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), secrets: MemorySecretStore())
        try store.save(p, secrets: SSHSecrets(password: "s3cret", knownHosts: Data(pin.utf8)))
        let out = try await RemoteCommand.run(profileID: p.id, store: store, command: "echo pw-$((20+22))", input: nil)
        XCTAssertEqual(String(decoding: out, as: UTF8.self), "pw-42\n")
    }
}

final class TailscalePeersTests: XCTestCase {
    func testPeersReadOverSSHFromSavedHost() async throws {
        guard let dir = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"] else { throw XCTSkip("SHELLO_SSHD_DIR not set") }
        let user = ProcessInfo.processInfo.environment["SHELLO_SSHD_USER"] ?? NSUserName()
        let f = String(decoding: try Data(contentsOf: URL(fileURLWithPath: dir + "/host_ed25519.pub")), as: UTF8.self).split(separator: " ")
        let store = try ProfileStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), secrets: MemorySecretStore())
        let p = SSHProfile(name: "ts", host: "127.0.0.1", port: 2299, user: user, auth: .key)
        try store.save(p, secrets: SSHSecrets(privateKey: try Data(contentsOf: URL(fileURLWithPath: dir + "/user_ed25519")), knownHosts: Data("[127.0.0.1]:2299 \(f[0]) \(f[1])\n".utf8)))
        let json = #"{"BackendState":"Running","Self":{"HostName":"mac","TailscaleIPs":["100.64.0.1"]},"Peer":{"a":{"HostName":"box","TailscaleIPs":["100.64.0.2"],"Online":true}}}"#
        let peers = await Discovery.tailscalePeers(store: store, command: "printf '%s' '\(json)'")
        XCTAssertEqual(peers.map(\.address), ["100.64.0.1", "100.64.0.2"]); XCTAssertEqual(peers.map(\.source), [.tailscale, .tailscale])
    }
}
