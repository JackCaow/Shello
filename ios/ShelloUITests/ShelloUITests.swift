import XCTest

/// End-to-end acceptance flows on the simulator against the local test sshd and companion/pair.py.
/// Prepare with: scripts/ios-test-sshd.sh start <dir>; scripts/ios-test-pair.sh start <dir>
/// Run with:     TEST_RUNNER_SHELLO_SSHD_DIR=<dir> TEST_RUNNER_SHELLO_SSHD_USER=$USER xcodebuild test ...
final class ShelloUITests: XCTestCase {
    var user = ""
    var env: [String: String] { ProcessInfo.processInfo.environment }
    var host: String { ProcessInfo.processInfo.environment["SHELLO_SSHD_HOST"] ?? "127.0.0.1" }
    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        guard ProcessInfo.processInfo.environment["SHELLO_SEED_KEY_B64"] != nil else { throw XCTSkip("SHELLO_SEED_KEY_B64 not set (see scripts/verify-ios.sh)") }
        user = ProcessInfo.processInfo.environment["SHELLO_SSHD_USER"] ?? NSUserName()
    }
    func launch(seed: Bool = true, extra: [String] = [], language: String = "en") {
        app = XCUIApplication()
        let profileDir = NSTemporaryDirectory() + "shello-ui-\(UUID().uuidString)"
        app.launchArguments = ["-SHELLO_UI_TEST", "-SHELLO_TEST_PROFILE_DIR", profileDir, "-AppleLanguages", "(\(language))", "-AppleLocale", language == "en" ? "en_US" : "zh_CN"] + extra
        if seed { app.launchArguments += ["-SHELLO_TEST_SEED_KEY_B64", env["SHELLO_SEED_KEY_B64"] ?? "", "-SHELLO_TEST_SEED_PUB", env["SHELLO_SEED_HOSTPUB"] ?? "", "-SHELLO_TEST_USER", user, "-SHELLO_TEST_HOST", host] }
        // The Local Network permission prompt appears on a physical device (never on the Simulator); allow it.
        addUIInterruptionMonitor(withDescription: "system alerts") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label IN {'Allow','OK','允许','好','Allow While Using App'}")).firstMatch
            if allow.exists { allow.tap(); return true }
            let last = alert.buttons.allElementsBoundByIndex.last; last?.tap(); return last != nil
        }
        app.launch()
    }
    @discardableResult func el(_ id: String, timeout: TimeInterval = 10) -> XCUIElement {
        let e = app.descendants(matching: .any)[id].firstMatch
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "missing \(id)\n\(app.debugDescription.prefix(9000))")
        return e
    }
    func screen() -> String { (app.descendants(matching: .any)["terminal.view"].firstMatch.value as? String) ?? "" }
    func waitScreen(_ text: String, timeout: TimeInterval = 25) {
        let end = Date().addingTimeInterval(timeout)
        while !screen().contains(text) && Date() < end { Thread.sleep(forTimeInterval: 0.3) }
        XCTAssertTrue(screen().contains(text), "screen lacks \(text): \(screen())")
    }
    func openShell() {
        el("profile.Local sshd").tap()
        el("picker.shell").tap()
        _ = el("terminal.view", timeout: 30)
        waitScreen("@")
    }

    func testHomeEmptyStateAndMenus() {
        launch(seed: false)
        el("home.sessions.empty"); el("home.connections.empty")
        el("home.add").tap(); el("home.add.discover"); el("home.add.pair"); el("home.add.ssh")
    }

    func testSavedConnectionOpensShellAndRunsCommands() {
        launch()
        openShell()
        app.typeText("echo UI_$((6*7))\n")
        waitScreen("UI_42")
        el("tool.key_ESCAPE"); el("tool.ctrl"); el("tool.chat"); el("tool.keyboard")
    }

    func sessionIDs() -> Set<String> {
        Set(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'picker.session.'")).allElementsBoundByIndex.map { $0.identifier })
    }
    func testTmuxSessionPersistsAcrossTabCloseAndReopen() {
        launch()
        el("profile.Local sshd").tap()
        el("picker.new"); Thread.sleep(forTimeInterval: 2)            // let the existing list load
        let before = sessionIDs()
        el("picker.new").tap()
        waitScreen("@")
        app.typeText("echo TMUXKEEP_$((5*5))\n")
        waitScreen("TMUXKEEP_25")
        el("terminal.menu").tap(); app.buttons["Close"].firstMatch.tap(); el("terminal.close.confirm").tap()
        el("home.settings")                                   // back on home, no tabs
        el("profile.Local sshd").tap()
        el("picker.new"); Thread.sleep(forTimeInterval: 2)
        let created = sessionIDs().subtracting(before)
        XCTAssertEqual(created.count, 1, "our tmux session must be listed once: \(created)")
        guard let id = created.first else { return }
        app.buttons[id].tap()
        waitScreen("TMUXKEEP_25")                               // tmux redrew the preserved screen
        app.typeText("exit\n")
    }

    func testToolbarCtrlComposerAndShortcutPanel() {
        launch(); openShell()
        el("tool.chat").tap()
        let text = el("composer.text"); text.tap(); text.typeText("echo DRAFT_$((3*4))")
        el("composer.send").tap()
        waitScreen("DRAFT_12")
        el("composer.close").tap()
        app.typeText("sleep 30")
        app.typeText("\n")
        el("tool.ctrl").tap(); app.typeText("c")                // Ctrl+C stops the sleep
        app.typeText("echo AFTER_CTRL_C\n")
        waitScreen("AFTER_CTRL_C")
        el("tool.panel").tap(); el("sheet.close").tap()
    }

    func testPairingEndToEndThenShell() throws {
        guard let qr = env["SHELLO_QR"] else { throw XCTSkip("pairing harness not running") }
        launch(seed: false, extra: ["-SHELLO_OPEN_PAIR", "-SHELLO_TEST_QR", qr.trimmingCharacters(in: .whitespacesAndNewlines)])
        let confirm = app.alerts.firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 20), "confirmation alert missing\n\(app.debugDescription.prefix(3000))")
        confirm.buttons.matching(NSPredicate(format: "label == %@", "Pair")).firstMatch.tap()
        el("picker.shell", timeout: 40).tap()
        el("terminal.view")
        waitScreen("@", timeout: 30)
    }

    func testManualFormTrustPromptSavesConnection() {
        launch(seed: false, extra: ["-SHELLO_TEST_KEY_B64", env["SHELLO_SEED_KEY_B64"] ?? ""])
        el("home.add").tap(); el("home.add.ssh").tap()
        el("form.name").tap(); el("form.name").typeText("Manual box")
        el("form.host").tap(); el("form.host").typeText(host)
        let port = el("form.port"); port.tap(); port.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 6) + "2299")
        el("form.user").tap(); el("form.user").typeText(user)
        el("keyboard.done").tap()                                 // small screens: the keyboard covers Save
        let save = el("form.save")
        var tries = 0
        while !save.isHittable && tries < 6 { app.scrollViews.firstMatch.swipeUp(); tries += 1 }
        save.tap()
        let trustShown = app.descendants(matching: .any)["trust.fingerprint"].firstMatch.waitForExistence(timeout: 20)
        if !trustShown {
            let status = app.descendants(matching: .any)["form.status"].firstMatch, err = app.descendants(matching: .any)["form.error"].firstMatch
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "no-trust"; shot.lifetime = .keepAlways; add(shot)
            XCTFail("no trust prompt; status=\(status.exists ? status.label : "-") error=\(err.exists ? err.label : "-")")
        }
        el("trust.accept").tap()
        el("picker.shell", timeout: 30)
    }

    func testPairingScannerStartsTheCamera() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("the Simulator has no camera; this runs on a physical device")
        #else
        launch(seed: false, extra: ["-SHELLO_OPEN_PAIR"])
        let status = el("pair.status", timeout: 20)
        let end = Date().addingTimeInterval(20)
        while !status.label.contains("Point at a QR") && Date() < end { Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertTrue(status.label.contains("Point at a QR"), "camera preview did not start: \(status.label)")
        #endif
    }

    func testFormValidationMessages() {
        launch(seed: false)
        el("home.add").tap(); el("home.add.ssh").tap()
        el("form.save").tap()
        XCTAssertTrue(el("form.error").label.count > 5)
    }

    func testSettingsThemeLanguageAndFont() {
        launch()
        el("home.settings").tap()
        if !app.descendants(matching: .any)["settings.appearance"].firstMatch.waitForExistence(timeout: 5) { el("home.settings").tap() }   // a system alert can swallow the first tap
        el("settings.appearance").tap(); el("theme.light").tap(); el("language.zh-Hans").tap()
        XCTAssertTrue(app.staticTexts["外观与语言"].waitForExistence(timeout: 5) || app.navigationBars["外观与语言"].waitForExistence(timeout: 5))
        el("language.en").tap()
        el("settings.back").tap()
        el("settings.font").tap(); let before = el("font.value").label
        el("font.plus").tap()
        XCTAssertNotEqual(before, el("font.value").label)
    }

    func testSettingsSubpagesToolbarShortcutsGestures() {
        launch()
        el("home.settings").tap()
        if !app.descendants(matching: .any)["settings.toolbar"].firstMatch.waitForExistence(timeout: 5) { el("home.settings").tap() }
        // Toolbar: preview, Zen mode switch, layout editor add / remove
        el("settings.toolbar").tap()
        el("toolbar.preview"); el("toolbar.mode.zen").tap(); el("toolbar.mode.toolbar").tap()
        el("toolbar.configure").tap()
        // Drag the Tab handle onto the Esc row: Tab moves before Esc
        let esc = el("handle.layout.key_ESCAPE"), tab = el("handle.layout.key_TAB")
        XCTAssertLessThan(esc.frame.minY, tab.frame.minY)
        for _ in 0..<3 where el("handle.layout.key_TAB").frame.minY > el("handle.layout.key_ESCAPE").frame.minY {   // drag-and-drop is timing sensitive
            el("handle.layout.key_TAB").press(forDuration: 0.8, thenDragTo: el("handle.layout.key_ESCAPE"))
            Thread.sleep(forTimeInterval: 1)
        }
        XCTAssertLessThan(el("handle.layout.key_TAB").frame.minY, el("handle.layout.key_ESCAPE").frame.minY, "drag-and-drop reordered the layout")
        el("layout.add.alt").tap(); el("remove.layout.alt").tap()
        XCTAssertFalse(app.descendants(matching: .any)["remove.layout.alt"].firstMatch.exists)
        el("settings.back").tap(); el("settings.back").tap()
        // Shortcuts: enabled group opens its editor with the tmux prefix field
        el("settings.shortcuts").tap()
        el("shortcuts.group.Tmux").tap(); el("shortcuts.prefix"); el("settings.back").tap()
        el("settings.back").tap()
        // Gestures: change the double tap action
        el("settings.gestures").tap()
        let row = el("gesture.double_tap")
        let before = row.label
        row.tap(); el("action.none").tap()
        XCTAssertNotEqual(before, el("gesture.double_tap").label)
    }

    func testAgentInboxEmptyState() {
        launch()
        el("home.agent").tap()
        el("agent.setup"); el("agent.close").tap()
    }

    func testSessionOverviewAndClose() {
        launch(); openShell()
        el("terminal.overview").tap()
        el("overview.card.shell"); el("overview.done").tap()
    }

    func testDiscoverySheetOpens() {
        launch()
        el("home.add").tap(); el("home.add.discover").tap()
        el("discover.status"); el("discover.rescan")
    }
}
