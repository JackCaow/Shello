import XCTest

/// UI regression for the review fixes. Uses an isolated local SSH fixture, never saved user connections.
final class UIReviewTests: XCTestCase {
    var app: XCUIApplication!
    override func setUpWithError() throws { continueAfterFailure = false; XCUIDevice.shared.orientation = .portrait }
    func launch(extra: [String] = []) throws {
        let env = ProcessInfo.processInfo.environment
        guard let directory = env["SHELLO_SSHD_DIR"] else { throw XCTSkip("SHELLO_SSHD_DIR required") }
        let dir = URL(fileURLWithPath: directory)
        let key = try Data(contentsOf: dir.appendingPathComponent("user_ed25519"))
        let pub = try String(contentsOf: dir.appendingPathComponent("host_ed25519.pub"))
        app = XCUIApplication()
        app.launchArguments = ["-SHELLO_UI_TEST", "-SHELLO_TEST_PROFILE_DIR", NSTemporaryDirectory() + UUID().uuidString,
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-SHELLO_TEST_SEED_KEY_B64", key.base64EncodedString(),
            "-SHELLO_TEST_SEED_PUB", pub, "-SHELLO_TEST_USER", env["SHELLO_SSHD_USER"] ?? NSUserName(), "-SHELLO_TEST_PORT", env["SHELLO_SSHD_PORT"] ?? "2299"] + extra
        app.launch()
    }
    @discardableResult func element(_ id: String, timeout: TimeInterval = 10) -> XCUIElement {
        let e = app.descendants(matching: .any)[id].firstMatch
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "Missing " + id)
        return e
    }
    func hittable(_ e: XCUIElement) {
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: e)], timeout: 10), .completed)
    }
    func shot(_ name: String) {
        Thread.sleep(forTimeInterval: 0.8)
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
    }
    func openShell() {
        element("profile.Local sshd").tap(); element("picker.shell").tap(); let terminal = element("terminal.view", timeout: 25)
        let connection = element("terminal.menu")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "SSH"), object: connection)], timeout: 20), .completed)
        app.typeText("echo SHELLO_UI_READY_$((6*7))\n")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "SHELLO_UI_READY_42"), object: terminal)], timeout: 20), .completed)
    }
    func testToolbarPreviewPinsButtonsAndUsesCurrentAppearance() throws {
        try launch()
        element("home.settings").tap(); element("settings.toolbar").tap()
        element("toolbar.configure").tap()
        for id in ["alt", "shift", "super"] {
            let row = element("layout.add." + id)
            if !row.isHittable { app.swipeUp() }
            row.tap()
        }
        element("settings.back").tap()
        let chat = element("preview.key.chat"), keyboard = element("preview.key.keyboard")
        let chatX = chat.frame.midX, keyboardX = keyboard.frame.midX
        let lane = app.descendants(matching: .any)["toolbar.scrollable"].firstMatch
        if lane.exists { lane.swipeLeft() } else { element("preview.key.ctrl").swipeLeft() }
        XCTAssertEqual(chat.frame.midX, chatX, accuracy: 2)
        XCTAssertEqual(keyboard.frame.midX, keyboardX, accuracy: 2)
        element("toolbar.modifier_icons").tap(); shot("review-toolbar-dark")
        element("settings.back").tap(); element("settings.appearance").tap(); element("theme.light").tap()
        element("settings.back").tap(); element("settings.toolbar").tap(); shot("review-toolbar-light")
    }
    func testGroupIconPreferenceChangesTheTerminalPanel() throws {
        try launch()
        element("home.settings").tap(); element("settings.shortcuts").tap()
        let toggle = element("shortcuts.icons_only")
        if !toggle.isHittable { app.swipeUp() }
        toggle.tap(); element("settings.back").tap(); element("settings.close").tap()
        openShell()
        element("tool.ctrl").press(forDuration: 1)
        let claude = element("group.Claude"), codex = element("group.Codex")
        XCTAssertEqual(claude.value as? String, "Show group icons only")
        XCTAssertEqual(codex.value as? String, "Show group icons only")
        codex.tap(); shot("review-shortcut-icons")
    }
    func testHomeSessionLongPressReorderCloseAndCloseAll() throws {
        try launch(); openShell()
        app.typeText("echo SHELLO_REVIEW_ONE\n"); element("terminal.back").tap()
        openShell(); app.typeText("echo SHELLO_REVIEW_TWO\n"); element("terminal.back").tap()
        let cards = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "home.session."))
        XCTAssertEqual(cards.count, 2)
        hittable(cards.element(boundBy: 0))
        let secondID = cards.element(boundBy: 1).identifier
        cards.element(boundBy: 0).press(forDuration: 1); shot("review-session-menu")
        element("session.reorder").tap()
        // The context-menu preview can still cover the original tile while its
        // dismissal animation finishes. Wait for the actual drag surface.
        hittable(element("home.reorder.done"))
        hittable(cards.element(boundBy: 0)); hittable(cards.element(boundBy: 1))
        cards.element(boundBy: 0).press(forDuration: 1, thenDragTo: cards.element(boundBy: 1), withVelocity: .slow, thenHoldForDuration: 0.5)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in cards.element(boundBy: 0).identifier == secondID }, object: nil)], timeout: 10), .completed)
        element("home.reorder.done").tap()
        XCTAssertEqual(cards.element(boundBy: 0).identifier, secondID)
        element("home.settings").tap(); element("settings.appearance").tap(); element("theme.light").tap()
        element("settings.back").tap(); element("settings.close").tap(); shot("review-light-home")
        cards.element(boundBy: 0).press(forDuration: 1); element("session.close").tap()
        XCTAssertEqual(cards.count, 1)
        cards.element(boundBy: 0).press(forDuration: 1); element("session.closeall").tap()
        element("home.sessions.empty"); XCTAssertEqual(cards.count, 0)
    }
    func testLargeTextAndLandscapeSettingsRemainNavigable() throws {
        try launch(extra: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        element("home.settings").tap(); shot("review-large-settings")
        element("settings.appearance").tap(); element("theme.light").tap(); shot("review-large-appearance")
        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.app.frame.width > self.app.frame.height }, object: nil)], timeout: 5), .completed)
        element("settings.back").tap(); shot("review-landscape-settings")
        element("settings.close").tap(); element("home.add").tap(); element("home.add.ssh").tap()
        element("form.cancel"); shot("review-landscape-form")
    }
    func testNewConnectionAutomaticallyDiscoversSSHAndPreservesManualFields() throws {
        try launch(extra: ["-SHELLO_TEST_DISCOVERY_PORT", ProcessInfo.processInfo.environment["SHELLO_SSHD_PORT"] ?? "2299"])
        element("home.add").tap(); element("home.add.ssh").tap()
        let discover = element("form.discovery")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "1"), object: discover)], timeout: 15), .completed)
        let host = element("form.host"), name = element("form.name")
        name.tap(); name.typeText("My custom name")
        host.tap(); host.typeText("manual.example")
        element("keyboard.done").tap()
        XCTAssertEqual(host.value as? String, "manual.example", "Automatic discovery must wait for explicit selection")
        shot("discovery-new-form")
        discover.tap()
        element("discover.host.127.0.0.1"); shot("discovery-server-picker")
        element("discover.host.127.0.0.1").tap()
        hittable(host)
        XCTAssertEqual(host.value as? String, "127.0.0.1")
        XCTAssertEqual(name.value as? String, "My custom name")
        XCTAssertEqual(element("form.port").value as? String, "22")
        shot("discovery-selected-host")
        discover.tap(); element("discover.rescan").tap(); element("discover.host.127.0.0.1")
        element("discover.close").tap()
        hittable(host)
        XCTAssertEqual(name.value as? String, "My custom name")
    }
    func testEditingConnectionDoesNotAutomaticallyDiscover() throws {
        try launch(extra: ["-SHELLO_TEST_DISCOVERY_PORT", "2399"])
        element("profile.Local sshd").press(forDuration: 1)
        app.buttons["Edit"].tap()
        element("form.host")
        XCTAssertFalse(app.descendants(matching: .any)["form.discovery"].exists)
        XCTAssertEqual(element("form.name").value as? String, "Local sshd")
        XCTAssertEqual(element("form.port").value as? String, ProcessInfo.processInfo.environment["SHELLO_SSHD_PORT"] ?? "2399")
        element("form.cancel").tap()
        hittable(element("home.add"))
    }

    func edgeBack(fraction: CGFloat = 0.86, hold: TimeInterval = 0) {
        // At the root, ending over a saved-connection button can activate its picker
        // when there is no pop recognizer. Use the empty right side of the session row.
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.003, dy: 0.25))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: fraction, dy: 0.25))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: hold)
    }
    func dismissSheetByDraggingGrabber() {
        let grabber = app.buttons.matching(NSPredicate(format: "label == %@", "Sheet Grabber")).allElementsBoundByIndex.first { $0.isHittable }
        XCTAssertNotNil(grabber)
        guard let grabber else { return }
        grabber.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.97)), withVelocity: .slow, thenHoldForDuration: 0)
    }
    func testEverySettingsPageSupportsEdgeBackAndRootDoesNotGetStuck() throws {
        try launch()
        element("home.settings").tap()
        for page in ["appearance", "font", "advanced", "hook", "notifications", "draft", "toolbar", "shortcuts", "gestures"] {
            let row = element("settings." + page)
            if !row.isHittable { app.swipeUp() }
            row.tap(); hittable(element("settings.back"))
            edgeBack()
            hittable(element("settings.close"))
        }
        edgeBack(); hittable(element("home.add"))
        edgeBack() // attempting back at the root must not disable subsequent navigation
        element("home.settings").tap(); hittable(element("settings.close"))
        let font = app.descendants(matching: .any)["settings.font"].firstMatch
        for _ in 0..<3 where !font.isHittable { app.swipeDown() }
        element("settings.font").tap()
        edgeBack(); element("settings.close").tap(); hittable(element("home.add"))
    }
    func testNestedEditorEdgeBackCanCancelAndPreservesEdits() throws {
        try launch()
        element("home.settings").tap(); element("settings.toolbar").tap(); element("toolbar.configure").tap()
        let add = element("layout.add.alt")
        if !add.isHittable { app.swipeUp() }; add.tap()
        let handle = element("handle.layout.alt")
        if !handle.isHittable { app.swipeDown() }
        edgeBack(fraction: 0.12, hold: 0.5)
        hittable(handle) // a short, released gesture should cancel the native transition
        shot("navigation-cancelled-editor-swipe")
        edgeBack(); hittable(element("toolbar.configure"))
        element("toolbar.configure").tap(); hittable(element("handle.layout.alt"))
        edgeBack(); edgeBack(); hittable(element("settings.close"))
        edgeBack(); hittable(element("home.add"))
    }
    func testInboxDeepLinkAndTerminalKeepTheirBackStackAndSession() throws {
        try launch()
        element("home.agent").tap(); element("agent.setup").tap()
        hittable(element("settings.back"))
        edgeBack(); hittable(element("settings.close"))
        edgeBack(); hittable(element("agent.close"))
        edgeBack(); hittable(element("home.add"))
        openShell(); app.typeText("echo GESTURE_SESSION_ALIVE\n")
        edgeBack(); hittable(element("home.add"))
        let cards = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "home.session."))
        XCTAssertEqual(cards.count, 1)
        cards.firstMatch.tap()
        let terminal = element("terminal.view")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "GESTURE_SESSION_ALIVE"), object: terminal)], timeout: 10), .completed)
        shot("navigation-restored-terminal")
        element("terminal.back").tap(); hittable(element("home.add"))
    }
    func testModalPullDownKeepsUnsavedFormAndDismissesOnlyTopSheet() throws {
        try launch(extra: ["-SHELLO_TEST_DISCOVERY_PORT", ProcessInfo.processInfo.environment["SHELLO_SSHD_PORT"] ?? "2399"])
        element("home.add").tap(); element("home.add.ssh").tap()
        let name = element("form.name")
        name.tap(); name.typeText("Keep my draft"); element("keyboard.done").tap()
        dismissSheetByDraggingGrabber()
        hittable(name); XCTAssertEqual(name.value as? String, "Keep my draft")
        element("form.discovery").tap(); element("discover.host.127.0.0.1")
        dismissSheetByDraggingGrabber()
        hittable(name); XCTAssertEqual(name.value as? String, "Keep my draft")
        element("form.cancel").tap(); app.buttons["Keep editing"].tap()
        hittable(name)
        element("form.cancel").tap(); app.buttons["Discard changes"].tap()
        hittable(element("home.add"))
    }

    func testRootEdgeSwipeDoesNotBlockOpeningAnotherPage() throws {
        try launch()
        element("home.settings").tap(); element("settings.font").tap()
        edgeBack(); hittable(element("settings.close"))
        edgeBack(); hittable(element("home.add"))
        edgeBack()
        element("home.settings").tap(); hittable(element("settings.close"))
        element("settings.font").tap(); hittable(element("settings.back"))
        edgeBack(); edgeBack(); hittable(element("home.add"))
    }

}
