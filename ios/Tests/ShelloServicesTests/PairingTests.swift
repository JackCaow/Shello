import XCTest
import ShelloCore
@testable import ShelloServices

final class PairingTests: XCTestCase {
    func testGeneratedKeyIsAcceptableSSHRSA() throws {
        let k = try PairingService.generateKey(comment: "t")
        let f = k.publicLine.split(separator: " ")
        XCTAssertEqual(f[0], "ssh-rsa")
        let raw = try XCTUnwrap(Data(base64Encoded: String(f[1])))
        var off = 0; var parts = [Data]()
        for _ in 0..<3 { let n = raw[off..<off + 4].reduce(0) { $0 << 8 | Int($1) }; off += 4; parts.append(raw.subdata(in: off..<off + n)); off += n }
        XCTAssertEqual(off, raw.count); XCTAssertEqual(String(decoding: parts[0], as: UTF8.self), "ssh-rsa")
        let bits = (parts[2].count - (parts[2].first == 0 ? 1 : 0)) * 8
        XCTAssertGreaterThanOrEqual(bits, 3065)       // 3072-bit modulus
        XCTAssertTrue(String(decoding: k.privatePEM, as: UTF8.self).hasPrefix("-----BEGIN RSA PRIVATE KEY-----"))
    }
    func testKeychainRoundTrip() throws {
        let store = KeychainSecretStore(service: "dev.ghostty.shello.tests.\(UUID().uuidString)")
        let key = UUID().uuidString
        do { _ = try store.read(key) } catch { throw XCTSkip("Keychain needs an app host (run in the Shello app tests): \(error)") }
        XCTAssertNil(try store.read(key))
        try store.write(key, Data("one".utf8)); try store.write(key, Data("two".utf8))
        XCTAssertEqual(try store.read(key), Data("two".utf8))
        try store.delete(key); XCTAssertNil(try store.read(key))
        try store.delete(key)
    }
}

/// End to end against companion/pair.py: scripts/ios-test-pair.sh start <dir> (needs SHELLO_SSHD_DIR).
final class PairingEndToEndTests: XCTestCase {
    func testPairThenConnectWithGeneratedKey() async throws {
        guard let dir = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"],
              let qr = try? String(contentsOfFile: dir + "/qr.txt") else { throw XCTSkip("pairing harness not running") }
        let ticket = try PairingTicket(qr)
        let result = try await PairingService.pair(ticket: ticket)
        XCTAssertEqual(result.profile.name, "Test Mac"); XCTAssertEqual(result.profile.port, 2299)
        XCTAssertTrue(String(decoding: result.secrets.knownHosts, as: UTF8.self).hasPrefix("[127.0.0.1]:2299 ssh-ed25519 "))
        // The installed RSA key must now authenticate over SSH (rsa-sha2) with the pinned host key.
        var p = result.profile; p.user = ProcessInfo.processInfo.environment["SHELLO_SSHD_USER"] ?? p.user
        let outcome = await ConnectionSetup.verify(profile: p, secrets: result.secrets)
        XCTAssertEqual(outcome, .verified)
        // A used token cannot pair a different phone.
        do { _ = try await PairingService.pair(ticket: ticket); XCTFail("second key must be rejected") } catch {}
    }
    func testWrongPinIsRejected() async throws {
        guard let dir = ProcessInfo.processInfo.environment["SHELLO_SSHD_DIR"],
              let qr = try? String(contentsOfFile: dir + "/qr.txt") else { throw XCTSkip("pairing harness not running") }
        let real = try PairingTicket(qr)
        let forged = try PairingTicket("ghostty-pair://v1?host=\(real.host)&port=\(real.port)&pin=\(String(repeating: "0", count: 64))&token=\(real.token)&expires=\(real.expires)")
        do { _ = try await PairingService.pair(ticket: forged); XCTFail("pin mismatch must fail") } catch {}
    }
}
