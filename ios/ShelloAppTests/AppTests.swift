import XCTest
import ShelloCore
import ShelloServices
@testable import Shello

/// Hosted in the app so the Keychain and Bundle resources are real.
final class AppTests: XCTestCase {
    func testKeychainRoundTripAndProfileStore() throws {
        let secrets = KeychainSecretStore(service: "dev.ghostty.shello.tests.\(UUID().uuidString)")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try ProfileStore(directory: dir, secrets: secrets)
        let p = SSHProfile(name: "kc", host: "192.168.1.2", user: "me", auth: .key)
        let s = SSHSecrets(passphrase: "pp", privateKey: Data("KEY".utf8), knownHosts: Data("h ssh-ed25519 AAAA\n".utf8))
        try store.save(p, secrets: s)
        let (rp, rs) = try store.read(p.id)
        XCTAssertEqual(rp, p); XCTAssertEqual(rs, s)
        try store.delete(p.id)
        XCTAssertNil(try secrets.read(p.id)); XCTAssertTrue(store.list().isEmpty)
    }
    func testHookScriptIsBundled() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "ghostty_hook", withExtension: "py"))
        let text = try String(contentsOf: url)
        XCTAssertTrue(text.contains("Shello / Claude Code and Codex bridge"))
    }
    func testBothLanguagesResolveAndFormat() {
        for lang in ["en", "zh-Hans"] {
            let path = Bundle.main.path(forResource: lang, ofType: "lproj")
            XCTAssertNotNil(path, lang)
            let b = Bundle(path: path!)!
            XCTAssertNotEqual(b.localizedString(forKey: "settings", value: "settings", table: nil), "settings", lang)
            XCTAssertTrue(String(format: b.localizedString(forKey: "connecting_endpoint", value: "", table: nil), "host.example").contains("host.example"))
        }
        let en = Bundle(path: Bundle.main.path(forResource: "en", ofType: "lproj")!)!, zh = Bundle(path: Bundle.main.path(forResource: "zh-Hans", ofType: "lproj")!)!
        XCTAssertEqual(en.localizedString(forKey: "settings", value: nil, table: nil), "Settings")
        XCTAssertEqual(zh.localizedString(forKey: "settings", value: nil, table: nil), "设置")
        XCTAssertTrue(String.localizedStringWithFormat(en.localizedString(forKey: "tmux_window_count", value: nil, table: nil), 1).contains("1 window"))
        XCTAssertTrue(String.localizedStringWithFormat(en.localizedString(forKey: "tmux_window_count", value: nil, table: nil), 3).contains("3 windows"))
    }
    @MainActor func testThemesAndSettingsPersist() {
        let d = UserDefaults(suiteName: UUID().uuidString)!
        let s = AppSettings(defaults: d)
        XCTAssertEqual(s.theme, "shello"); XCTAssertEqual(s.fontSize, 14); XCTAssertTrue(s.selection)
        s.theme = "light"; s.set("fontSize", 18)
        let again = AppSettings(defaults: d)
        XCTAssertEqual(again.theme, "light"); XCTAssertEqual(again.fontSize, 18); XCTAssertTrue(again.current.light)
        XCTAssertEqual(Theme.named("unknown").id, "shello")
    }
}

import Network
import ShelloServices
final class DiscoveryAndQRTests: XCTestCase {
    func testQRImageRoundTripAndInvalidImage() throws {
        let payload = "ghostty-pair://v1?host=192.168.1.9&port=8765&pin=\(String(repeating: "a", count: 64))&token=\(String(repeating: "T", count: 40))&expires=\(Int(Date().timeIntervalSince1970) + 300)"
        let img = try XCTUnwrap(QRDecoder.image(for: payload))
        let png = try XCTUnwrap(UIGraphicsImageRenderer(size: CGSize(width: 330, height: 330)).image { _ in img.draw(in: CGRect(x: 0, y: 0, width: 330, height: 330)) }.pngData())
        XCTAssertEqual(QRDecoder.decode(png), payload)
        let blank = try XCTUnwrap(UIGraphicsImageRenderer(size: CGSize(width: 100, height: 100)).image { UIColor.gray.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 100, height: 100)) }.pngData())
        XCTAssertNil(QRDecoder.decode(blank))
    }
    func testBonjourSSHServiceIsDiscovered() async throws {
        let listener = try NWListener(using: .tcp, on: .any)
        listener.service = NWListener.Service(name: "ShelloBonjourTest", type: "_ssh._tcp")
        listener.newConnectionHandler = { $0.start(queue: .global()) }
        let ready = expectation(description: "advertising"); listener.stateUpdateHandler = { if case .ready = $0 { ready.fulfill() } }
        listener.start(queue: .global()); await fulfillment(of: [ready], timeout: 10)
        defer { listener.cancel() }
        let found = await withTaskGroup(of: Discovery.Host?.self) { group -> Discovery.Host? in
            group.addTask { for await h in Discovery.bonjour() where h.name == "ShelloBonjourTest" { return h }; return nil }
            group.addTask { try? await Task.sleep(nanoseconds: 20_000_000_000); return nil }
            let r = await group.next() ?? nil; group.cancelAll(); return r
        }
        XCTAssertNotNil(found, "Bonjour browse should find the advertised _ssh._tcp service")
        XCTAssertEqual(found?.source, .bonjour)
    }
}

import ShelloTerminalUI
@MainActor
final class ZenControllerTests: XCTestCase {
    func testZenRingLifecycle() throws {
        let env = AppEnvironment()
        let c = TerminalController(env: env)
        env.prefs.reset("layout_zen")
        XCTAssertEqual(c.zenIDs.count, 8)                         // the default Zen layout has eight actions
        XCTAssertTrue(c.zen(.doubleHoldStart, at: CGPoint(x: 100, y: 100)))
        XCTAssertEqual(c.zenCenter, CGPoint(x: 100, y: 100)); XCTAssertNil(c.zenSelected)
        XCTAssertTrue(c.zen(.doubleHoldMove, at: CGPoint(x: 100, y: 20)))   // straight up = first item
        XCTAssertEqual(c.zenSelected, 0)
        XCTAssertTrue(c.zen(.doubleHoldMove, at: CGPoint(x: 103, y: 102)))  // back inside the dead zone
        XCTAssertNil(c.zenSelected)
        XCTAssertTrue(c.zen(.doubleHoldEnd, at: .zero))
        XCTAssertNil(c.zenCenter)
        env.prefs.save("layout_zen", [])
        XCTAssertFalse(c.zen(.doubleHoldStart, at: .zero), "no ring without a layout")
        env.prefs.reset("layout_zen")
    }
    func testGestureMappingsAndLabels() {
        let env = AppEnvironment()
        env.prefs.setMapping("double_tap", "paste"); XCTAssertEqual(env.prefs.mapping("double_tap"), "paste")
        XCTAssertEqual(InputText.label(for: "mux_next", env.prefs), tr("input_next_tmux_window"))
        XCTAssertEqual(env.prefs.mapping("single_tap"), "none")
        XCTAssertEqual(InputText.label(for: "none", env.prefs), tr("input_none"))
    }
}
