import XCTest
@testable import ShelloCore

final class CoreTests: XCTestCase {
    func testSessionTargetRoundTripAndValidation() throws {
        let target = SessionTarget.fresh(tmux: true)
        let id = try SessionTarget.id(profile: "abc", target: target)
        XCTAssertEqual(SessionTarget.profile(of: id), "abc")
        XCTAssertEqual(SessionTarget.target(of: id), target)
        XCTAssertNoThrow(try SessionTarget.validate("=my session"))
        XCTAssertThrowsError(try SessionTarget.validate("rm -rf"))
        XCTAssertThrowsError(try SessionTarget.validate("=bad\nname"))
        XCTAssertThrowsError(try SessionTarget.validate(nil))
        XCTAssertTrue(SessionTarget.isAgent("agent:claude:123e4567-e89b-12d3-a456-426614174000"))
    }
    func testCommands() throws {
        XCTAssertEqual(ShellQuote.quote("a'b"), "'a'\\''b'")
        XCTAssertTrue(try SessionTarget.command("=work").contains("attach-session -t '=work'"))
        let fresh = SessionTarget.fresh(tmux: true)
        XCTAssertTrue(try SessionTarget.command(fresh).contains("new-session -A -s '\(fresh)' -e NO_COLOR= -e COLORTERM=truecolor"))
        XCTAssertTrue(try SessionTarget.command("agent:codex:123e4567-e89b-12d3-a456-426614174000").contains("resume 'codex' '123e4567-e89b-12d3-a456-426614174000'"))
    }
    func testTmuxParse() throws {
        let e = try TmuxSessions.parse("2 1 dev\n1 0 my work\n")
        XCTAssertEqual(e, [.init(name: "dev", windows: 2, attached: 1), .init(name: "my work", windows: 1, attached: 0)])
        XCTAssertThrowsError(try TmuxSessions.parse("x y z"))
        XCTAssertEqual(try TmuxSessions.parse(""), [])
    }
    func testNetworkAddress() {
        XCTAssertTrue(NetworkAddress.isPairingAddress("192.168.1.5"))
        XCTAssertTrue(NetworkAddress.isPairingAddress("100.100.1.1"))
        XCTAssertFalse(NetworkAddress.isPairingAddress("8.8.8.8"))
        XCTAssertFalse(NetworkAddress.isPairingAddress("192.168.01.5"))
        XCTAssertFalse(NetworkAddress.isPairingAddress("example.com"))
        XCTAssertFalse(NetworkAddress.isTailscale("100.128.0.1"))
    }
    private func qr(host: String = "192.168.1.9", expires: Int64? = nil) -> String {
        let e = expires ?? Int64(Date().timeIntervalSince1970) + 300
        return "ghostty-pair://v1?host=\(host)&port=8765&pin=\(String(repeating: "a", count: 64))&token=\(String(repeating: "T", count: 40))&expires=\(e)"
    }
    func testPairingTicket() throws {
        let t = try PairingTicket(qr())
        XCTAssertEqual(t.host, "192.168.1.9"); XCTAssertEqual(t.port, 8765)
        XCTAssertThrowsError(try PairingTicket(qr(host: "8.8.8.8")))
        XCTAssertThrowsError(try PairingTicket(qr(expires: 1)))
        XCTAssertThrowsError(try PairingTicket("https://example.com"))
        XCTAssertThrowsError(try PairingTicket(qr().replacingOccurrences(of: "pin=", with: "pin=zz")))
    }
    func testPairingResponse() throws {
        let t = try PairingTicket(qr())
        let json = #"{"version":1,"port":2222,"user":"me","name":"Mac","hostKeys":["ssh-ed25519 AAAAC3Nza"]}"#
        let r = try PairingTicket.Response(json: Data(json.utf8), ticket: t)
        XCTAssertEqual(r.knownHosts, "[192.168.1.9]:2222 ssh-ed25519 AAAAC3Nza\n")
        XCTAssertThrowsError(try PairingTicket.Response(json: Data(#"{"version":2}"#.utf8), ticket: t))
        XCTAssertThrowsError(try PairingTicket.Response(json: Data(#"{"version":1,"port":22,"user":"u","name":"n","hostKeys":["bad key"]}"#.utf8), ticket: t))
    }
    func testProfileStoreRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try ProfileStore(directory: dir, secrets: MemorySecretStore())
        var p = SSHProfile(name: "Mac", host: "192.168.1.9", user: "me", auth: .key)
        let s = SSHSecrets(privateKey: Data("KEY".utf8), knownHosts: Data("h k\n".utf8))
        try store.save(p, secrets: s)
        let (rp, rs) = try store.read(p.id)
        XCTAssertEqual(rp, p); XCTAssertEqual(rs, s)
        XCTAssertEqual(store.list().map(\.id), [p.id])
        p.host = "bad host"
        XCTAssertThrowsError(try store.save(p, secrets: s))
        XCTAssertThrowsError(try store.save(SSHProfile(name: "x", host: "h", user: "u"), secrets: SSHSecrets(password: "p")))
        try store.delete(p.id)
        XCTAssertTrue(store.list().isEmpty)
        XCTAssertThrowsError(try store.read(p.id))
    }
}

final class InputActionsTests: XCTestCase {
    func testParseSequences() throws {
        let tmux = try InputActions.parse("C-b, c")
        XCTAssertEqual(tmux.count, 2)
        XCTAssertEqual(tmux[0].modifiers, .ctrl); XCTAssertEqual(tmux[0].codePoint, UInt32(UInt8(ascii: "b")))
        XCTAssertEqual(try InputActions.parse("S-TAB"), [.init(key: .tab, codePoint: 0, text: "", modifiers: .shift, paste: false)])
        XCTAssertEqual(try InputActions.parse("Ctrl+Shift+Tab")[0].modifiers, [.ctrl, .shift])
        XCTAssertEqual(try InputActions.parse("text:/clear"), [.init(key: nil, codePoint: 0, text: "/clear", modifiers: [], paste: true)])
        XCTAssertEqual(try InputActions.parse("PGUP")[0].key, .pageUp)
        XCTAssertEqual(try InputActions.parse("action:paste"), [])
    }
    func testParseErrors() {
        XCTAssertThrowsError(try InputActions.parse(""))
        XCTAssertThrowsError(try InputActions.parse("action:nope"))
        XCTAssertThrowsError(try InputActions.parse("C-"))
        XCTAssertThrowsError(try InputActions.parse("notakey"))
        XCTAssertThrowsError(try InputActions.parse(Array(repeating: "a", count: 65).joined(separator: ",")))
    }
    func testCatalogAndGroups() {
        XCTAssertNotNil(InputActions.catalog["key_ESCAPE"]); XCTAssertNotNil(InputActions.catalog["ctrl_c"]); XCTAssertNotNil(InputActions.catalog["char_124"])
        XCTAssertEqual(InputActions.groups["Tmux"]?.count, 8)
        XCTAssertEqual(InputActions.catalog["preset_Claude_0"]?.enter, true)
    }
    func testPreferences() throws {
        let d = UserDefaults(suiteName: UUID().uuidString)!
        let p = InputPreferences(defaults: d)
        XCTAssertEqual(p.list("layout_toolbar").first, "ctrl")
        XCTAssertEqual(p.list("group_Tmux").count, 4)
        p.save("layout_toolbar", ["key_TAB"]); XCTAssertEqual(p.list("layout_toolbar"), ["key_TAB", "ctrl"])
        let id = try p.put(label: "Ls", binding: "text:ls", enter: true)
        p.save("layout_composer", ["key_ESCAPE", id]); XCTAssertEqual(p.list("layout_composer"), ["key_ESCAPE", id])
        p.removeCustom(id); XCTAssertEqual(p.list("layout_composer"), ["key_ESCAPE"])
        XCTAssertThrowsError(try p.put(label: " ", binding: "text:x", enter: false))
        XCTAssertThrowsError(try p.put(label: "x", binding: "", enter: false))
        p.setPrefix("Tmux", "C-a"); XCTAssertEqual(p.action("preset_Tmux_0")?.binding, "C-a, c")
        XCTAssertEqual(p.mapping("swipe_left"), "mux_next"); p.setMapping("swipe_left", "none"); XCTAssertEqual(p.mapping("swipe_left"), "none")
    }
}


final class ZenRingTests: XCTestCase {
    func testIndexMapsDirectionsAndDeadZone() {
        XCTAssertNil(ZenRing.index(dx: 5, dy: -5, count: 8))
        XCTAssertEqual(ZenRing.index(dx: 0, dy: -80, count: 8), 0)       // up
        XCTAssertEqual(ZenRing.index(dx: 80, dy: 0, count: 8), 2)        // right
        XCTAssertEqual(ZenRing.index(dx: 0, dy: 80, count: 8), 4)        // down
        XCTAssertEqual(ZenRing.index(dx: -80, dy: 0, count: 8), 6)       // left
        XCTAssertEqual(ZenRing.index(dx: -1, dy: -80, count: 8), 0)      // just left of up still snaps to the first item
        XCTAssertNil(ZenRing.index(dx: 80, dy: 0, count: 0))
        XCTAssertEqual(ZenRing.index(dx: 0, dy: 80, count: 1), 0)
    }
    func testOffsetsAreOnTheCircle() {
        for i in 0..<6 { let o = ZenRing.offset(i, of: 6); XCTAssertEqual((o.x * o.x + o.y * o.y).squareRoot(), ZenRing.radius, accuracy: 0.001) }
        XCTAssertEqual(ZenRing.offset(0, of: 4).y, -ZenRing.radius, accuracy: 0.001)
    }
}
