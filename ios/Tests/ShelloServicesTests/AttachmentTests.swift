import XCTest
import ShelloCore
@testable import ShelloServices

final class AttachmentNameTests: XCTestCase {
    func testSafeName() {
        XCTAssertEqual(AttachmentTransfer.safeName("a/b\\c.png"), "a_b_c.png")
        XCTAssertEqual(AttachmentTransfer.safeName(".."), "attachment")
        XCTAssertEqual(AttachmentTransfer.safeName(".upload-part"), "attachment")
        XCTAssertEqual(AttachmentTransfer.safeName("   "), "attachment")
        XCTAssertEqual(AttachmentTransfer.safeName(nil), "attachment")
        XCTAssertEqual(AttachmentTransfer.safeName("what?*[x].txt"), "what___x_.txt")
        XCTAssertLessThanOrEqual(AttachmentTransfer.safeName(String(repeating: "中", count: 200)).utf8.count, 180)
        XCTAssertEqual(AttachmentTransfer.safeName("line\nbreak"), "line_break")
    }
}

@MainActor
final class AttachmentIntegrationTests: XCTestCase {
    var dir = "", user = ""
    override func setUpWithError() throws {
        guard let d = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"] else { throw XCTSkip("SHELLO_SSHD_DIR not set") }
        dir = d; user = ProcessInfo.processInfo.environment["SHELLO_SSHD_USER"] ?? NSUserName()
    }
    func store() throws -> (ProfileStore, SSHProfile) {
        let s = try ProfileStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), secrets: MemorySecretStore())
        let f = String(decoding: try Data(contentsOf: URL(fileURLWithPath: dir + "/host_ed25519.pub")), as: UTF8.self).split(separator: " ")
        let p = SSHProfile(name: "t", host: "127.0.0.1", port: 2299, user: user, auth: .key)
        try s.save(p, secrets: SSHSecrets(privateKey: try Data(contentsOf: URL(fileURLWithPath: dir + "/user_ed25519")), knownHosts: Data("[127.0.0.1]:2299 \(f[0]) \(f[1])\n".utf8)))
        return (s, p)
    }
    func wait(_ t: AttachmentTransfer) { let end = Date().addingTimeInterval(30); while t.isBusy && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.05)) } }
    func file(_ bytes: Int) throws -> URL {
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("att-\(UUID().uuidString).bin")
        try Data((0..<bytes).map { UInt8($0 % 253) }).write(to: u); return u
    }
    func testUploadDeliversExactBytesWithPrivatePermissions() throws {
        let (s, p) = try store(); let src = try file(300_000)
        let t = AttachmentTransfer(profileID: p.id, store: s, source: src, displayName: "my photo.png"); t.start(); wait(t)
        guard case .done(let path) = t.state else { return XCTFail("\(t.state)") }
        XCTAssertTrue(path.hasSuffix("/my photo.png")); XCTAssertTrue(path.contains("/.local/share/shello/attachments/"))
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), try Data(contentsOf: src))
        let attrs = try FileManager.default.attributesOfItem(atPath: path)
        XCTAssertEqual((attrs[.posixPermissions] as? Int ?? 0) & 0o077, 0)
        let folder = URL(fileURLWithPath: path).deletingLastPathComponent().path
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder), ["my photo.png"])   // no partial file left
        try? FileManager.default.removeItem(atPath: folder)
    }
    func testTooLargeIsRefusedBeforeConnecting() throws {
        let (s, p) = try store()
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("big-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: u.path, contents: nil)
        let h = try FileHandle(forWritingTo: u); try h.truncate(atOffset: UInt64(AttachmentTransfer.maxBytes + 1)); try h.close()
        let t = AttachmentTransfer(profileID: p.id, store: s, source: u); t.start(); wait(t)
        XCTAssertEqual(t.state, .failed(.tooLarge)); try? FileManager.default.removeItem(at: u)
    }
    func testMissingSourceAndBadHostKey() throws {
        let (s, p) = try store()
        let t = AttachmentTransfer(profileID: p.id, store: s, source: URL(fileURLWithPath: "/nonexistent/file")); t.start(); wait(t)
        XCTAssertEqual(t.state, .failed(.unreadable))
        var (prof, sec) = try s.read(p.id); sec.knownHosts = Data("[127.0.0.1]:2299 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UmKboyWgZ+DLu5DUp6ok1DJ3kKIs\n".utf8); try s.save(prof, secrets: sec)
        let t2 = AttachmentTransfer(profileID: p.id, store: s, source: try file(10)); t2.start(); wait(t2)
        XCTAssertEqual(t2.state, .failed(.connection(.hostKeyMismatch)))
    }
}
