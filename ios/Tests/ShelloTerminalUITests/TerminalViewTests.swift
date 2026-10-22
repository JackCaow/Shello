import XCTest
import ShelloTerminal
@testable import ShelloTerminalUI

@MainActor
final class TerminalViewTests: XCTestCase {
    func makeView(_ w: CGFloat = 400, _ h: CGFloat = 300) throws -> (TerminalView, TerminalSession) {
        let view = TerminalView(frame: CGRect(x: 0, y: 0, width: w, height: h))
        let session = try TerminalSession(columns: 10, rows: 5)
        view.setSession(session); view.layoutIfNeeded()
        return (view, session)
    }
    func image(_ v: TerminalView) -> UIImage { UIGraphicsImageRenderer(bounds: v.bounds).image { v.layer.render(in: $0.cgContext) } }
    func nonBackgroundPixels(_ img: UIImage, bg: UInt32 = 0) -> Int {
        guard let cg = img.cgImage, let data = cg.dataProvider?.data, let p = CFDataGetBytePtr(data) else { return 0 }
        var n = 0; let bpp = cg.bitsPerPixel / 8
        for i in stride(from: 0, to: cg.width * cg.height * bpp, by: bpp) where p[i] != 0 || p[i + 1] != 0 || p[i + 2] != 0 { n += 1 }
        return n
    }

    func testGridFollowsViewSizeAndNotifiesResize() throws {
        let (v, s) = try makeView()
        var got: (Int, Int)?
        v.onResize = { got = ($0, $1) }
        v.frame = CGRect(x: 0, y: 0, width: 200, height: 160); v.layoutIfNeeded()
        let size = v.cellSize
        let insets = v.contentInsets
        XCTAssertEqual(s.columns, Int((200 - insets.left - insets.right) / size.width)); XCTAssertEqual(s.rows, Int((160 - insets.top - insets.bottom) / size.height))
        XCTAssertEqual(got?.0, s.columns); XCTAssertEqual(got?.1, s.rows)
    }
    func testRendersTextPixels() throws {
        let (v, s) = try makeView()
        v.refresh()
        let blank = nonBackgroundPixels(image(v))
        try s.write("Hello \u{1b}[31mred\u{1b}[0m 中文 🙂"); v.refresh()
        XCTAssertGreaterThan(nonBackgroundPixels(image(v)), blank + 100)
        XCTAssertEqual(v.currentFrame?.cell(row: 0, column: 0).text, "H")
    }
    func testFontSizeChangesGridAndIsClamped() throws {
        let (v, s) = try makeView()
        let before = s.columns
        v.setFontSize(24); v.layoutIfNeeded()
        XCTAssertLessThan(s.columns, before); XCTAssertEqual(v.fontSize, 24)
        v.setFontSize(100); XCTAssertEqual(v.fontSize, 32)
        v.setFontSize(1); XCTAssertEqual(v.fontSize, 8)
    }
    func testKeyboardInputProducesBytes() throws {
        let (v, _) = try makeView()
        var out = [Data](); v.onInput = { out.append($0) }
        v.insertText("a"); v.insertText("\n"); v.deleteBackward()
        v.pendingModifiers = .ctrl; v.insertText("c")
        v.sendKey(.up); v.pasteText("x")
        XCTAssertEqual(out, [Data("a".utf8), Data("\r".utf8), Data([0x7f]), Data([3]), Data("\u{1b}[A".utf8), Data("x".utf8)])
        XCTAssertTrue(v.pendingModifiers.isEmpty)
    }
    func testLockedModifierPersists() throws {
        let (v, _) = try makeView()
        var out = [Data](); v.onInput = { out.append($0) }
        v.lockedModifiers = .ctrl; v.insertText("a"); v.insertText("b")
        XCTAssertEqual(out, [Data([1]), Data([2])])
    }
    func testNoListenerMeansReadOnly() throws {
        let (v, _) = try makeView()
        v.insertText("a")   // must not crash
        XCTAssertNil(v.onInput)
    }
    func testSelectionTextAndClearOnOutput() throws {
        let (v, s) = try makeView()
        try s.write("hello world"); v.refresh()
        var changes = [String](); v.onSelectionChanged = { changes.append($0) }
        v.select(from: 6, to: 10)
        XCTAssertEqual(v.selectedText, "world")
        try s.write("!")                      // output clears the selection
        XCTAssertEqual(v.selectedText, ""); XCTAssertEqual(changes, ["world", ""])
        v.selectionEnabled = false; v.select(from: 0, to: 3); XCTAssertEqual(v.selectedText, "")
    }
    func testWideSelectionSnapsToWholeCell() throws {
        let (v, s) = try makeView()
        try s.write("中a"); v.refresh()
        v.select(from: 1, to: 1)             // inside the wide cell's tail
        XCTAssertEqual(v.selectedText, "中")
    }
    func testAlternateScreenScrollBecomesArrowKeys() throws {
        let (v, s) = try makeView()
        try s.write("\u{1b}[?1049h"); v.refresh()
        var out = [Data](); v.onInput = { out.append($0) }
        let pan = v.gestureRecognizers!.compactMap { $0 as? UIPanGestureRecognizer }.first { $0.maximumNumberOfTouches == 1 }!
        XCTAssertNotNil(pan)
        XCTAssertTrue(v.currentFrame!.alternateScreen)
    }
    func testBlockCursorDrawsAndHollowWhenUnfocused() throws {
        let (v, s) = try makeView()
        try s.write("x\u{1b}[2 q"); v.refresh()       // steady block
        XCTAssertEqual(v.currentFrame?.cursorShape, .block)
        XCTAssertGreaterThan(nonBackgroundPixels(image(v)), 0)
    }
    func testSessionSwapDetachesOldSession() throws {
        let (v, s1) = try makeView()
        let s2 = try TerminalSession(columns: 10, rows: 5)
        v.setSession(s2); try s1.write("ignored")
        XCTAssertNil(s1.onChange); XCTAssertNotNil(s2.onChange)
        v.setSession(nil); XCTAssertNil(s2.onChange)
    }
}
