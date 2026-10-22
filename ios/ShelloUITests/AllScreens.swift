import XCTest

/// Walks every screen of the app and attaches a screenshot per screen (named NN-name).
/// Export with scripts/export-ios-screenshots.sh. Uses harmless shell output only.
final class AllScreens: XCTestCase {
    var app: XCUIApplication!
    var n = 0
    var env: [String: String] { ProcessInfo.processInfo.environment }

    override func setUpWithError() throws {
        continueAfterFailure = true
        guard env["SHELLO_SEED_KEY_B64"] != nil else { throw XCTSkip("SHELLO_SEED_KEY_B64 not set") }
    }
    func launch(seed: Bool, language: String = "en", extra: [String] = []) {
        app?.terminate()
        app = XCUIApplication()
        var args = ["-SHELLO_UI_TEST", "-SHELLO_TEST_PROFILE_DIR", NSTemporaryDirectory() + "all-\(UUID())", "-AppleLanguages", "(\(language))", "-AppleLocale", language == "en" ? "en_US" : "zh_CN"]
        if seed { args += ["-SHELLO_TEST_SEED_KEY_B64", env["SHELLO_SEED_KEY_B64"] ?? "", "-SHELLO_TEST_SEED_PUB", env["SHELLO_SEED_HOSTPUB"] ?? "", "-SHELLO_TEST_USER", env["SHELLO_SSHD_USER"] ?? NSUserName(), "-SHELLO_TEST_HOST", env["SHELLO_SSHD_HOST"] ?? "127.0.0.1"] }
        app.launchArguments = args + extra
        addUIInterruptionMonitor(withDescription: "alerts") { a in
            let b = a.buttons.matching(NSPredicate(format: "label IN {'Allow','OK','允许','好'}")).firstMatch
            if b.exists { b.tap() } else { a.buttons.allElementsBoundByIndex.last?.tap() }; return true
        }
        app.launch()
    }
    func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }
    @discardableResult func tap(_ id: String, wait: TimeInterval = 8) -> Bool {
        let e = any(id); guard e.waitForExistence(timeout: wait) else { return false }
        let end = Date().addingTimeInterval(min(wait, 6))
        while !e.isHittable && Date() < end { Thread.sleep(forTimeInterval: 0.3) }
        guard e.isHittable else { return false }
        e.tap(); return true
    }
    func shot(_ name: String, settle: TimeInterval = 0.8) {
        Thread.sleep(forTimeInterval: settle); n += 1
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = String(format: "%02d-%@", n, name); a.lifetime = .keepAlways; add(a)
    }
    /// Dismisses stacked sheets (tools sheet behind the web preview, etc.) via their Done buttons.
    func closeSheets() { var guardN = 0; while any("sheet.close").exists && guardN < 4 { any("sheet.close").tap(); guardN += 1; Thread.sleep(forTimeInterval: 0.6) } }
    func back() { if !tap("settings.back", wait: 2) { _ = tap("overview.done", wait: 1) } }

    func testEveryScreen() {
        // ── Empty home, add menu, forms ──────────────────────────────────────────
        launch(seed: false, extra: ["-SHELLO_TEST_KEY_B64", env["SHELLO_SEED_KEY_B64"] ?? ""])
        shot("home-empty")
        tap("home.add"); shot("home-add-menu"); tap("home.add.ssh")
        shot("form-new-connection")
        tap("keyboard.done", wait: 1); tap("form.save"); shot("form-validation-error")
        any("form.name").tap(); any("form.name").typeText("My Mac")
        any("form.host").tap(); any("form.host").typeText(env["SHELLO_SSHD_HOST"] ?? "127.0.0.1")
        let port = any("form.port"); port.tap(); port.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 6) + "2299")
        any("form.user").tap(); any("form.user").typeText(env["SHELLO_SSHD_USER"] ?? NSUserName())
        shot("form-filled"); tap("keyboard.done", wait: 2); tap("form.save")
        if any("trust.fingerprint").waitForExistence(timeout: 20) { shot("trust-host-fingerprint"); tap("trust.cancel") }
        tap("form.cancel", wait: 3); Thread.sleep(forTimeInterval: 0.5)
        tap("home.add"); tap("home.add.pair"); shot("pairing", settle: 2); tap("pair.close", wait: 3)
        tap("home.add"); tap("home.add.discover"); shot("discovery", settle: 4);         tap("discover.close", wait: 2)

        // ── Seeded: home, picker, terminal and its panels ─────────────────────────
        launch(seed: true)
        shot("home-with-connection")
        tap("profile.Local sshd"); shot("tmux-picker", settle: 2.5)
        tap("picker.shell"); XCTAssertTrue(any("terminal.view").waitForExistence(timeout: 25)); Thread.sleep(forTimeInterval: 3)
        app.typeText("printf '\\033[1;32mShello\\033[0m \\033[4munderline\\033[0m 中文 🙂\\n'; echo hello from the terminal\n"); Thread.sleep(forTimeInterval: 2)
        shot("terminal")
        tap("tool.keyboard"); shot("terminal-keyboard-hidden"); tap("tool.keyboard"); Thread.sleep(forTimeInterval: 0.8)
        any("terminal.view").coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.12)).press(forDuration: 1.2); shot("terminal-selection")
        tap("selection.clear", wait: 1)
        tap("tool.pad"); shot("terminal-direction-pad"); tap("tool.pad")
        tap("tool.shortcuts", wait: 1)   // not in the default toolbar; tolerated
        tap("tool.ctrl"); shot("terminal-ctrl-armed"); tap("tool.ctrl")
        tap("tool.panel"); shot("session-tools-sheet"); tap("tools.safari"); if app.state == .runningForeground { shot("web-preview") }; app.activate(); closeSheets()
        if tap("tool.history", wait: 1) { shot("input-history"); closeSheets() }
        tap("tool.chat"); any("composer.text").tap(); any("composer.text").typeText("echo 中文 draft\nsecond line"); shot("composer")
        tap("composer.attach"); shot("composer-attach-menu"); app.tap()
        tap("composer.close")
        tap("terminal.menu"); shot("terminal-session-menu"); let disconnect = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'Disconnect'")).firstMatch
        if disconnect.waitForExistence(timeout: 3) { disconnect.tap(); shot("terminal-disconnected", settle: 1.5) } else { app.tap() }
        tap("terminal.overview"); shot("session-overview"); tap("overview.menu"); shot("session-overview-menu"); app.tap(); tap("overview.done", wait: 2)
        tap("terminal.back"); shot("home-with-session", settle: 1.2)

        // ── Settings and every sub-page ───────────────────────────────────────────
        tap("home.settings"); shot("settings")
        for (id, name) in [("appearance", "appearance"), ("font", "font"), ("advanced", "advanced"), ("hook", "hook"), ("notifications", "notifications"), ("draft", "text-draft")] {
            tap("settings.\(id)"); shot("settings-\(name)"); back()
        }
        tap("settings.toolbar"); shot("settings-toolbar"); tap("toolbar.mode.zen"); shot("settings-toolbar-zen"); tap("toolbar.mode.toolbar")
        tap("toolbar.configure"); shot("settings-toolbar-layout"); back()
        tap("toolbar.configure.zen"); shot("settings-zen-layout"); back()
        tap("toolbar.configure.composer"); shot("settings-composer-layout"); back(); back()
        tap("settings.shortcuts"); shot("settings-shortcuts")
        tap("shortcuts.group.Tmux"); shot("settings-shortcut-group-tmux"); back()
        tap("shortcuts.custom"); shot("settings-custom-shortcut"); back(); back()
        tap("settings.gestures"); shot("settings-gestures"); tap("gesture.double_tap"); shot("settings-gesture-action-picker"); back(); back()
        tap("settings.close", wait: 2)

        // ── Agent inbox ───────────────────────────────────────────────────────────
        tap("home.agent"); shot("agent-inbox-empty"); tap("agent.close", wait: 2)

        // ── Light theme and Chinese ───────────────────────────────────────────────
        tap("home.settings"); tap("settings.appearance"); tap("theme.light"); shot("light-appearance"); back(); tap("settings.close", wait: 2)
        shot("light-home")
        tap("profile.Local sshd"); tap("picker.shell"); _ = any("terminal.view").waitForExistence(timeout: 25); Thread.sleep(forTimeInterval: 3)
        shot("light-terminal"); tap("tool.chat"); shot("light-composer"); tap("composer.close"); tap("terminal.back")
        launch(seed: true, language: "zh-Hans")
        shot("zh-home"); tap("home.settings"); shot("zh-settings"); tap("settings.toolbar"); shot("zh-settings-toolbar"); back(); tap("settings.gestures"); shot("zh-settings-gestures")
    }
}
