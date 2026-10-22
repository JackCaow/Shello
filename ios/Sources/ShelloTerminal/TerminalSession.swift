import Foundation
import GhosttyVT

public struct TerminalError: Error, Equatable, CustomStringConvertible {
    public let status: Int32
    public var description: String { "Ghostty returned \(status)" }
}

/// A thread-confined VT engine. Use one thread (normally the main thread when attached to a view).
public final class TerminalSession {
    /// Order matches the engine's key table (and the Android `TerminalSession.Key`).
    public enum Key: Int, CaseIterable {
        case up, down, left, right, pageUp, pageDown, home, end, enter, tab, escape, backspace, delete, insert
        case f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12
    }
    public struct Modifiers: OptionSet {
        public let rawValue: Int
        public init(rawValue: Int) { self.rawValue = rawValue }
        public static let shift = Modifiers(rawValue: 1)
        public static let ctrl = Modifiers(rawValue: 2)
        public static let alt = Modifiers(rawValue: 4)
        public static let `super` = Modifiers(rawValue: 8)
    }
    public struct CellFlags: OptionSet {
        public let rawValue: Int32
        public init(rawValue: Int32) { self.rawValue = rawValue }
        public static let bold = CellFlags(rawValue: 1)
        public static let italic = CellFlags(rawValue: 2)
        public static let faint = CellFlags(rawValue: 4)
        public static let invisible = CellFlags(rawValue: 8)
        public static let strikethrough = CellFlags(rawValue: 16)
        public static let overline = CellFlags(rawValue: 32)
        public static let blink = CellFlags(rawValue: 64)
    }
    public struct Cell {
        public var text: String
        public var foreground: UInt32
        public var background: UInt32
        /// 0 narrow, 1 wide head, 2 spacer tail, 3 spacer head (engine values).
        public var wide: Int32
        public var flags: CellFlags
        public var underline: Int32
        public var underlineColor: UInt32
    }
    public enum CursorShape: Int32 { case bar = 0, block = 1, underline = 2, hollow = 3 }
    public struct Frame {
        public var columns: Int, rows: Int
        public var cursorX: Int, cursorY: Int
        public var alternateScreen: Bool, mouseTracking: Bool
        public var scrollOffset: Int, scrollTotal: Int
        public var cursorVisible: Bool, cursorBlinking: Bool
        public var cursorShape: CursorShape
        public var cursorColor: UInt32, defaultBackground: UInt32
        public var cells: [Cell]
        public func cell(row: Int, column: Int) -> Cell { cells[row * columns + column] }
    }

    public static let maximumColumns = 1000, maximumRows = 1000
    private var handle: OpaquePointer?
    /// Called after output, resize or scroll with `true` when the selection should be cleared. Set by the attached view.
    public var onChange: ((_ clearSelection: Bool) -> Void)?
    public private(set) var columns: Int
    public private(set) var rows: Int

    public init(columns: Int, rows: Int) throws {
        try Self.validate(columns, rows)
        var status: Int32 = 0
        guard let h = shello_terminal_create(Int32(columns), Int32(rows), &status) else { throw TerminalError(status: status) }
        handle = h; self.columns = columns; self.rows = rows
    }
    private init(handle: OpaquePointer, columns: Int, rows: Int) { self.handle = handle; self.columns = columns; self.rows = rows }
    deinit { close() }

    public func close() { if let h = handle { shello_terminal_free(h); handle = nil } }
    private func live() throws -> OpaquePointer { guard let h = handle else { throw TerminalError(status: -3) }; return h }
    private static func validate(_ c: Int, _ r: Int) throws {
        guard (1...maximumColumns).contains(c), (1...maximumRows).contains(r) else { throw TerminalError(status: -1) }
    }
    private func check(_ status: Int32) throws { if status != 0 { throw TerminalError(status: status) } }

    /// Feed ordered PTY output. Returns VT replies the transport must send back to the PTY.
    @discardableResult
    public func write(_ output: Data) throws -> Data {
        let h = try live()
        try output.withUnsafeBytes { raw in
            try check(shello_terminal_write(h, raw.bindMemory(to: UInt8.self).baseAddress, raw.count))
        }
        defer { onChange?(true) }
        return try takeReplies()
    }
    @discardableResult
    public func write(_ text: String) throws -> Data { try write(Data(text.utf8)) }

    private func takeReplies() throws -> Data {
        let h = try live()
        var capacity = 65536
        while true {
            var buffer = [UInt8](repeating: 0, count: capacity)
            var length = 0
            let status = shello_terminal_take_replies(h, &buffer, capacity, &length)
            if status == Int32(SHELLO_ERR_ARG), length > capacity { capacity = length; continue }
            try check(status)
            return Data(buffer.prefix(length))
        }
    }

    public func resize(columns: Int, rows: Int) throws {
        let h = try live(); try Self.validate(columns, rows)
        if columns == self.columns && rows == self.rows { return }
        try check(shello_terminal_resize(h, Int32(columns), Int32(rows)))
        self.columns = columns; self.rows = rows
        onChange?(true)
    }
    public func scroll(by delta: Int) throws { try check(shello_terminal_scroll(try live(), Int32(clamping: delta))); onChange?(false) }

    public func frame() throws -> Frame {
        let h = try live()
        var words: UnsafeMutablePointer<Int32>?; var count = 0
        try check(shello_terminal_render(h, &words, &count))
        guard let w = words else { throw TerminalError(status: -2) }
        defer { shello_free(w) }
        let head = UnsafeBufferPointer(start: w, count: count)
        let cols = Int(head[0]), rws = Int(head[1])
        var cells = [Cell](); cells.reserveCapacity(cols * rws)
        var at = 14
        for _ in 0..<(cols * rws) {
            let n = Int(head[at + 6])
            var text = String.UnicodeScalarView()
            for i in 0..<n { if let s = Unicode.Scalar(UInt32(bitPattern: head[at + 7 + i])) { text.append(s) } }
            cells.append(Cell(text: String(text), foreground: UInt32(bitPattern: head[at]), background: UInt32(bitPattern: head[at + 1]),
                              wide: head[at + 2], flags: CellFlags(rawValue: head[at + 3]), underline: head[at + 4],
                              underlineColor: UInt32(bitPattern: head[at + 5])))
            at += 7 + n
        }
        return Frame(columns: cols, rows: rws, cursorX: Int(head[2]), cursorY: Int(head[3]), alternateScreen: head[4] != 0,
                     mouseTracking: head[5] != 0, scrollOffset: Int(head[6]), scrollTotal: Int(head[7]), cursorVisible: head[8] != 0,
                     cursorBlinking: head[9] != 0, cursorShape: CursorShape(rawValue: head[10]) ?? .block,
                     cursorColor: UInt32(bitPattern: head[11]), defaultBackground: UInt32(bitPattern: head[12]), cells: cells)
    }

    /// DEC private modes 1 (application cursor), 1000, 1006 (mouse) and 2004 (bracketed paste).
    public struct Modes { public var applicationCursor, mouseNormal, mouseSGR, bracketedPaste: Bool }
    public func modes() throws -> Modes {
        var mask: UInt32 = 0; try check(shello_terminal_modes(try live(), &mask))
        return Modes(applicationCursor: mask & 1 != 0, mouseNormal: mask & 2 != 0, mouseSGR: mask & 4 != 0, bracketedPaste: mask & 8 != 0)
    }

    public func encode(key: Key, modifiers: Modifiers = []) throws -> Data {
        try encodeKey(index: Int32(key.rawValue), modifiers: modifiers, text: Data(), codePoint: 0)
    }
    /// `codePoint` is the unshifted Unicode scalar; `text` the UTF-8 the keypress produced.
    public func encode(character codePoint: UInt32, text: String, modifiers: Modifiers = []) throws -> Data {
        try encodeKey(index: -1, modifiers: modifiers, text: Data(text.utf8), codePoint: codePoint)
    }
    private func encodeKey(index: Int32, modifiers: Modifiers, text: Data, codePoint: UInt32) throws -> Data {
        let h = try live()
        var out = [UInt8](repeating: 0, count: 4096); var n = 0
        let status = text.withUnsafeBytes { raw in
            shello_terminal_key(h, index, Int32(modifiers.rawValue), raw.bindMemory(to: UInt8.self).baseAddress, raw.count, codePoint, &out, out.count, &n)
        }
        try check(status)
        return Data(out.prefix(n))
    }

    public func encodePaste(_ text: String) throws -> Data {
        let h = try live(); var out: UnsafeMutablePointer<UInt8>?; var n = 0
        let bytes = Array(text.utf8)
        try check(shello_terminal_paste(h, bytes, bytes.count, &out, &n))
        defer { shello_free(out) }
        return Data(bytes: out!, count: n)
    }

    public enum MouseAction: Int32 { case press = 0, release = 1, motion = 2 }
    public func encodeMouse(_ action: MouseAction, right: Bool = false, x: Float, y: Float, cellWidth: Int, cellHeight: Int) throws -> Data {
        var out = [UInt8](repeating: 0, count: 128); var n = 0
        try check(shello_terminal_mouse(try live(), action.rawValue, right, x, y, Int32(cellWidth), Int32(cellHeight), &out, out.count, &n))
        return Data(out.prefix(n))
    }
    public func encodeWheel(down: Bool, x: Float, y: Float, cellWidth: Int, cellHeight: Int) throws -> Data {
        var out = [UInt8](repeating: 0, count: 128); var n = 0
        try check(shello_terminal_wheel(try live(), down, x, y, Int32(cellWidth), Int32(cellHeight), &out, out.count, &n))
        return Data(out.prefix(n))
    }

    /// Inclusive row-major indices across the active screen and history.
    public func selectedText(from start: Int, to end: Int) throws -> String {
        var out: UnsafeMutablePointer<UInt8>?; var n = 0
        try check(shello_terminal_selection(try live(), Int32(start), Int32(end), &out, &n))
        defer { shello_free(out) }
        return String(decoding: UnsafeBufferPointer(start: out, count: n), as: UTF8.self)
    }

    public struct Checkpoint { public let bytes: Data; public let columns: Int; public let rows: Int }
    public func checkpoint() throws -> Checkpoint {
        var out: UnsafeMutablePointer<UInt8>?; var n = 0
        try check(shello_terminal_snapshot(try live(), &out, &n))
        defer { shello_free(out) }
        return Checkpoint(bytes: Data(bytes: out!, count: n), columns: columns, rows: rows)
    }
    public static func restore(_ checkpoint: Checkpoint) throws -> TerminalSession {
        var status: Int32 = 0
        let h = checkpoint.bytes.withUnsafeBytes { shello_terminal_restore($0.bindMemory(to: UInt8.self).baseAddress, $0.count, &status) }
        guard let handle = h else { throw TerminalError(status: status) }
        return TerminalSession(handle: handle, columns: checkpoint.columns, rows: checkpoint.rows)
    }

    /// Grapheme cluster end offsets (exclusive, in Unicode scalars) using the engine's Unicode tables.
    public static func graphemeEnds(_ text: String) -> [Int] {
        let scalars = text.unicodeScalars.map { $0.value }
        if scalars.isEmpty { return [] }
        var ends = [Int32](repeating: 0, count: scalars.count)
        let groups = shello_grapheme_ends(scalars, scalars.count, &ends)
        return ends.prefix(groups).map(Int.init)
    }
}
