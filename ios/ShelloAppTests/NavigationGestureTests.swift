import XCTest
import UIKit
import ShelloCore
import ShelloTerminalUI
@testable import Shello

@MainActor
final class NavigationGestureTests: XCTestCase {
    private func environment() -> AppEnvironment {
        AppEnvironment(profileDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), defaults: UserDefaults(suiteName: UUID().uuidString)!, secrets: MemorySecretStore())
    }
    func testDeepLinkBuildsRealSettingsBackStackAndKeepsOrigin() {
        let env = environment()
        env.route = .agent
        env.route = .settings("hook")
        XCTAssertEqual(env.navigationPath, [.agent, .settings(nil), .settings("hook")])
        env.navigationPath.removeLast()
        XCTAssertEqual(env.route, .settings(nil))
        env.navigationPath.removeLast()
        XCTAssertEqual(env.route, .agent)
        env.navigationPath.removeLast()
        XCTAssertNil(env.route)
    }
    func testReopeningScreenDoesNotDuplicateItAndMinimizeDoesNotCloseTabs() throws {
        let env = environment()
        let profile = SSHProfile(name: "fixture", host: "127.0.0.1", user: "test")
        let tab = try env.sessions.open(profile: profile, connect: false)
        env.route = .terminal; env.route = .terminal
        XCTAssertEqual(env.navigationPath, [.terminal])
        env.route = nil
        XCTAssertTrue(env.navigationPath.isEmpty)
        XCTAssertEqual(env.sessions.tabs.map(\.id), [tab.id])
        env.sessions.closeAll()
    }
    func testSwitchingSettingsPageKeepsSingleSettingsRoot() {
        let env = environment()
        env.route = .settings("toolbar"); env.route = .settings("appearance")
        XCTAssertEqual(env.navigationPath, [.settings(nil), .settings("appearance")])
    }
    func testNativeGestureCannotPopRootButWorksWithHiddenCustomHeader() {
        let nav = UINavigationController(rootViewController: UIViewController())
        nav.loadViewIfNeeded(); nav.setNavigationBarHidden(true, animated: false)
        let bridge = InteractiveBackNavigation.Coordinator()
        bridge.install(on: nav)
        let gesture = nav.interactivePopGestureRecognizer!
        XCTAssertFalse(bridge.gestureRecognizerShouldBegin(gesture))
        nav.pushViewController(UIViewController(), animated: false)
        bridge.install(on: nav)
        XCTAssertTrue(gesture.isEnabled)
        XCTAssertTrue(bridge.gestureRecognizerShouldBegin(gesture))
        nav.popViewController(animated: false)
        bridge.install(on: nav)
        XCTAssertFalse(gesture.isEnabled)
        XCTAssertFalse(bridge.gestureRecognizerShouldBegin(gesture))
        bridge.uninstall()
    }
    func testMultiplePageBridgesNeverForwardToEachOther() {
        let nav = UINavigationController(rootViewController: UIViewController())
        nav.loadViewIfNeeded()
        let first = InteractiveBackNavigation.Coordinator(), second = InteractiveBackNavigation.Coordinator()
        first.install(on: nav); second.install(on: nav); first.install(on: nav)
        // Reentrant optional-delegate lookup must terminate, rather than cycling between bridges.
        _ = first.responds(to: NSSelectorFromString("gestureRecognizer:shouldReceiveTouch:"))
        first.uninstall(); second.uninstall()
    }
    func testBridgeRestoresPreviousDelegateAndEnabledState() {
        final class Delegate: NSObject, UIGestureRecognizerDelegate {}
        let nav = UINavigationController(rootViewController: UIViewController())
        nav.loadViewIfNeeded()
        let previous = Delegate(), gesture = nav.interactivePopGestureRecognizer!
        gesture.delegate = previous; gesture.isEnabled = false
        let bridge = InteractiveBackNavigation.Coordinator()
        bridge.install(on: nav); bridge.install(on: nav)
        XCTAssertTrue(gesture.delegate === bridge)
        bridge.uninstall()
        XCTAssertTrue(gesture.delegate === previous)
        XCTAssertFalse(gesture.isEnabled)
    }
    func testCancelledTerminalPanDoesNotExecuteConfiguredShortcut() {
        final class Pan: UIPanGestureRecognizer {
            var simulatedState: UIGestureRecognizer.State = .cancelled
            override var state: UIGestureRecognizer.State {
                get { simulatedState }
                set { simulatedState = newValue }
            }
            override func velocity(in view: UIView?) -> CGPoint { CGPoint(x: 1000, y: 0) }
        }
        let terminal = TerminalView(), pan = Pan()
        var actions = 0
        terminal.onGesture = { _, _ in actions += 1; return true }
        terminal.perform(NSSelectorFromString("panned:"), with: pan)
        XCTAssertEqual(actions, 0)
        pan.simulatedState = .ended
        terminal.perform(NSSelectorFromString("panned:"), with: pan)
        XCTAssertEqual(actions, 1)
    }
}
