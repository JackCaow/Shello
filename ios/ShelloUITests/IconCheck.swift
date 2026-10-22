import XCTest

/// Screenshots the real home screen so the installed app icon can be reviewed (physical device or Simulator).
final class IconCheck: XCTestCase {
    func testHomeScreenIcon() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-SHELLO_UI_TEST"]
        app.launch()
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        Thread.sleep(forTimeInterval: 2)
        // The app may sit on a later page: swipe through the pages until the icon is visible.
        var pages = 0
        while !springboard.icons["Shello"].isHittable && pages < 8 { springboard.swipeLeft(); pages += 1; Thread.sleep(forTimeInterval: 0.6) }
        let a = XCTAttachment(screenshot: springboard.screenshot()); a.name = "homescreen"; a.lifetime = .keepAlways; add(a)
        XCTAssertTrue(springboard.icons["Shello"].exists, "Shello icon is on the home screen")
    }
}
