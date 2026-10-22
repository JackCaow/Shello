import XCTest

/// Captures review screenshots into the test result bundle (attachments); compare with docs/evidence/moshi-ui.
final class Screenshots: XCTestCase {
    func testCaptureScreens() throws {
        let env = ProcessInfo.processInfo.environment
        guard env["SHELLO_SEED_KEY_B64"] != nil else { throw XCTSkip("SHELLO_SEED_KEY_B64 not set") }
        let user = env["SHELLO_SSHD_USER"] ?? NSUserName()
        let app = XCUIApplication()
        app.launchArguments = ["-SHELLO_UI_TEST", "-SHELLO_TEST_PROFILE_DIR", NSTemporaryDirectory() + "shots-\(UUID())", "-AppleLanguages", "(en)", "-SHELLO_TEST_SEED_KEY_B64", env["SHELLO_SEED_KEY_B64"] ?? "", "-SHELLO_TEST_SEED_PUB", env["SHELLO_SEED_HOSTPUB"] ?? "", "-SHELLO_TEST_USER", user, "-SHELLO_TEST_HOST", env["SHELLO_SSHD_HOST"] ?? "127.0.0.1"]
        addUIInterruptionMonitor(withDescription: "alerts") { a in a.buttons.allElementsBoundByIndex.last?.tap(); return true }
        app.launch()
        func shot(_ name: String) { let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
        func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }
        shot("01-home")
        any("profile.Local sshd").tap(); _ = any("picker.shell").waitForExistence(timeout: 10); shot("02-picker")
        any("picker.shell").tap()
        XCTAssertTrue(any("terminal.view").waitForExistence(timeout: 20)); Thread.sleep(forTimeInterval: 3)
        app.typeText("printf '\\033[1;32mgreen\\033[0m \\033[4munderline\\033[0m 中文 🙂\\n'; ls | head -4\n"); Thread.sleep(forTimeInterval: 2)
        shot("03-terminal")
        any("tool.pad").tap(); shot("04-pad"); any("tool.pad").tap()
        any("tool.chat").tap(); any("composer.text").tap(); any("composer.text").typeText("echo 中文 draft"); shot("05-composer")
        any("composer.close").tap()
        any("tool.shortcuts").exists ? any("tool.shortcuts").tap() : ()
        any("terminal.overview").tap(); Thread.sleep(forTimeInterval: 1); shot("06-overview"); any("overview.done").tap()
        any("terminal.back").tap(); Thread.sleep(forTimeInterval: 1); shot("07-home-with-session")
        any("home.settings").tap(); Thread.sleep(forTimeInterval: 1); shot("08-settings")
        for (id, name) in [("toolbar", "09-toolbar"), ("shortcuts", "10-shortcuts"), ("gestures", "11-gestures"), ("advanced", "12-advanced"), ("font", "13-font"), ("appearance", "14-appearance"), ("hook", "15-hook")] {
            any("settings.\(id)").tap(); Thread.sleep(forTimeInterval: 1); shot(name); any("settings.back").tap(); Thread.sleep(forTimeInterval: 0.5)
        }
    }
}
