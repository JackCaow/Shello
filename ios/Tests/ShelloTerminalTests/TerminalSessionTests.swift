import XCTest
@testable import ShelloTerminal

final class TerminalSessionTests: XCTestCase {
    func testPrintsTextAndColors() throws {
        let t = try TerminalSession(columns: 20, rows: 5)
        try t.write("hi \u{1b}[31mred\u{1b}[0m")
        let f = try t.frame()
        XCTAssertEqual(f.columns, 20); XCTAssertEqual(f.rows, 5)
        XCTAssertEqual(f.cell(row: 0, column: 0).text, "h")
        XCTAssertEqual(f.cell(row: 0, column: 1).text, "i")
        XCTAssertEqual(f.cell(row: 0, column: 3).text, "r")
        XCTAssertNotEqual(f.cell(row: 0, column: 3).foreground, f.cell(row: 0, column: 0).foreground)
        XCTAssertEqual(f.cursorX, 6)
    }
    func testPartialUTF8AndEscapeAcrossWrites() throws {
        let t = try TerminalSession(columns: 10, rows: 2)
        let bytes = Array("é\u{1b}[1mB".utf8)
        for b in bytes { try t.write(Data([b])) }
        let f = try t.frame()
        XCTAssertEqual(f.cell(row: 0, column: 0).text, "é")
        XCTAssertTrue(f.cell(row: 0, column: 1).flags.contains(.bold))
    }
    func testWideCharacterAndEmoji() throws {
        let t = try TerminalSession(columns: 10, rows: 2)
        try t.write("中🙂")
        let f = try t.frame()
        XCTAssertEqual(f.cell(row: 0, column: 0).wide, 1)
        XCTAssertEqual(f.cell(row: 0, column: 2).text, "🙂")
    }
    func testRepliesForDeviceStatusReport() throws {
        let t = try TerminalSession(columns: 10, rows: 2)
        let reply = try t.write("\u{1b}[6n")
        XCTAssertEqual(String(decoding: reply, as: UTF8.self), "\u{1b}[1;1R")
    }
    func testKeyEncodingHonorsApplicationCursor() throws {
        let t = try TerminalSession(columns: 10, rows: 2)
        XCTAssertEqual(try t.encode(key: .up), Data("\u{1b}[A".utf8))
        try t.write("\u{1b}[?1h")
        XCTAssertTrue(try t.modes().applicationCursor)
        XCTAssertEqual(try t.encode(key: .up), Data("\u{1b}OA".utf8))
        XCTAssertEqual(try t.encode(key: .enter), Data("\r".utf8))
        XCTAssertEqual(try t.encode(character: UInt32(UInt8(ascii: "c")), text: "c", modifiers: .ctrl), Data([3]))
        XCTAssertEqual(try t.encode(key: .tab, modifiers: .shift), Data("\u{1b}[Z".utf8))
    }
    func testBracketedPaste() throws {
        let t = try TerminalSession(columns: 10, rows: 2)
        XCTAssertEqual(try t.encodePaste("a\nb"), Data("a\rb".utf8))
        try t.write("\u{1b}[?2004h")
        XCTAssertEqual(try t.encodePaste("x"), Data("\u{1b}[200~x\u{1b}[201~".utf8))
    }
    func testResizeAndScrollback() throws {
        let t = try TerminalSession(columns: 10, rows: 3)
        for i in 0..<20 { try t.write("line\(i)\r\n") }
        var f = try t.frame()
        XCTAssertGreaterThan(f.scrollTotal, f.rows)
        try t.scroll(by: -5)
        f = try t.frame()
        XCTAssertLessThan(f.scrollOffset, f.scrollTotal - f.rows)
        try t.resize(columns: 20, rows: 4)
        XCTAssertEqual(try t.frame().columns, 20)
        XCTAssertThrowsError(try t.resize(columns: 0, rows: 4))
    }
    func testSelectionText() throws {
        let t = try TerminalSession(columns: 10, rows: 2)
        try t.write("hello world")
        XCTAssertEqual(try t.selectedText(from: 0, to: 4), "hello")
    }
    func testCheckpointRestore() throws {
        let t = try TerminalSession(columns: 12, rows: 3)
        try t.write("keep me")
        let r = try TerminalSession.restore(try t.checkpoint())
        XCTAssertEqual(try r.frame().cell(row: 0, column: 0).text, "k")
        XCTAssertEqual(r.columns, 12)
    }
    func testMouseWheelEncodedWhenTracking() throws {
        let t = try TerminalSession(columns: 10, rows: 3)
        try t.write("\u{1b}[?1000h\u{1b}[?1006h")
        XCTAssertTrue(try t.frame().mouseTracking)
        let wheel = try t.encodeWheel(down: true, x: 5, y: 5, cellWidth: 10, cellHeight: 20)
        XCTAssertEqual(String(decoding: wheel, as: UTF8.self), "\u{1b}[<65;1;1M")
    }
    func testGraphemeEnds() {
        XCTAssertEqual(TerminalSession.graphemeEnds("ae\u{301}b"), [1, 3, 4])
        XCTAssertEqual(TerminalSession.graphemeEnds(""), [])
    }
    func testClosedSessionThrows() throws {
        let t = try TerminalSession(columns: 5, rows: 2)
        t.close()
        XCTAssertThrowsError(try t.frame())
    }
}
