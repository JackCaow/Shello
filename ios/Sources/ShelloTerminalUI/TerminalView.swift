import UIKit
import ShelloTerminal

/// Native Core Graphics terminal viewport with grapheme-aware touch selection. Contains no transport.
public final class TerminalView: UIView, UIKeyInput, UITextInputTraits {
    public enum Gesture: String {
        case singleTap, doubleTap, tripleTap, pullDown, swipeLeft, swipeRight, twoLeft, twoRight, twoUp, twoDown, pinchIn, pinchOut
        /// Tap, then press and hold: the host shows an action ring and follows the finger (start / move / end / cancel).
        case doubleHoldStart, doubleHoldMove, doubleHoldEnd, doubleHoldCancel
    }
    public var onInput: ((Data) -> Void)?
    public var onResize: ((_ columns: Int, _ rows: Int) -> Void)?
    public var onSelectionChanged: ((String) -> Void)?
    /// Return true when the host handled the gesture; otherwise built-in behaviour (if any) continues.
    public var onGesture: ((Gesture, CGPoint) -> Bool)?
    public var onModifiersChanged: (() -> Void)?
    public var onFocusChanged: ((Bool) -> Void)?

    public private(set) var session: TerminalSession?
    public private(set) var columns = 80, rows = 24
    public private(set) var selectedText = ""
    public var selectionEnabled = true { didSet { if !selectionEnabled { clearSelection() } } }
    public var mouseSelectionEnabled = false
    public var rightClickEnabled = false
    public var optionAsMeta = true
    public var dragArrowsEnabled = false
    /// Modifiers applied to the next typed character; `locked` ones persist.
    public var pendingModifiers: TerminalSession.Modifiers = [] { didSet { onModifiersChanged?() } }
    public var lockedModifiers: TerminalSession.Modifiers = [] { didSet { onModifiersChanged?() } }
    public var fontSize: CGFloat { get { _fontSize } set { setFontSize(newValue) } }
    /// Breathing room around the text grid; the cell grid is laid out inside these insets.
    public var contentInsets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16) { didSet { resizeToView(); setNeedsDisplay() } }
    private func local(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x - contentInsets.left, y: p.y - contentInsets.top) }

    private var _fontSize: CGFloat = 14
    private var fonts = [UIFont]()
    private(set) var cellWidth: CGFloat = 8, cellHeight: CGFloat = 17
    private var frameData: TerminalSession.Frame?
    private var display = [[DisplayCell]]()
    private var drawScheduled = false
    private var blinkTimer: Timer?
    private var blinkEpoch = CACurrentMediaTime()
    private var selection: (start: Int, end: Int)?
    private var handleDrag: Int?   // 0 start, 1 end
    private var scrollRemainder: CGFloat = 0
    private var pinchAmount: CGFloat = 1
    private var mousePressed = false

    struct DisplayCell { var text = ""; var span = 1; var skip = false; var cell: TerminalSession.Cell }

    // MARK: setup

    public override init(frame: CGRect) { super.init(frame: frame); setup() }
    public required init?(coder: NSCoder) { super.init(coder: coder); setup() }
    private func setup() {
        isOpaque = true; backgroundColor = UIColor(rgb: 0x151922)
        contentMode = .redraw; clipsToBounds = true
        isMultipleTouchEnabled = true; isAccessibilityElement = true; accessibilityLabel = "Terminal"
        accessibilityTraits = .allowsDirectInteraction
        rebuildFonts()
        installGestures()
        NotificationCenter.default.addObserver(self, selector: #selector(appStateChanged), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(appStateChanged), name: UIApplication.willResignActiveNotification, object: nil)
    }
    deinit { blinkTimer?.invalidate(); NotificationCenter.default.removeObserver(self) }

    private func rebuildFonts() {
        let regular = UIFont.monospacedSystemFont(ofSize: _fontSize, weight: .regular)
        let bold = UIFont.monospacedSystemFont(ofSize: _fontSize, weight: .bold)
        func italic(_ f: UIFont) -> UIFont { f.fontDescriptor.withSymbolicTraits(.traitItalic).map { UIFont(descriptor: $0, size: _fontSize) } ?? f }
        fonts = [regular, bold, italic(regular), italic(bold)]
        cellWidth = ("M" as NSString).size(withAttributes: [.font: regular]).width.rounded(.up)
        cellHeight = max(regular.lineHeight.rounded(.up), _fontSize * 1.28)
    }

    public func setFontSize(_ points: CGFloat) {
        let size = min(32, max(8, points))
        guard size != _fontSize else { return }
        _fontSize = size; rebuildFonts(); clearSelection(); resizeToView(); refresh()
    }

    // MARK: session

    public func setSession(_ value: TerminalSession?) {
        if session === value { return }
        session?.onChange = nil
        session = value; clearSelection(); frameData = nil; display = []; scrollRemainder = 0
        if let v = value {
            v.onChange = { [weak self] clear in self?.sessionChanged(clearSelection: clear) }
            columns = v.columns; rows = v.rows
            resizeToView(); refresh()
        }
        setNeedsDisplay()
    }
    private func sessionChanged(clearSelection clear: Bool) {
        if clear { setSelection(nil) }
        scheduleRefresh()
    }
    private func scheduleRefresh() {
        guard !drawScheduled else { return }
        drawScheduled = true
        DispatchQueue.main.async { [weak self] in self?.drawScheduled = false; self?.refresh() }
    }
    /// Re-reads the VT state immediately (tests and layout use this; output uses the coalesced path).
    public func refresh() {
        guard let s = session, let f = try? s.frame() else { return }
        let previous = frameData
        frameData = f
        shape(f)
        if previous == nil || previous!.cursorX != f.cursorX || previous!.cursorY != f.cursorY || previous!.cursorShape != f.cursorShape
            || previous!.cursorVisible != f.cursorVisible { resetBlink() }
        updateBlinkTimer()
        accessibilityValue = display.map { $0.map { $0.text.isEmpty ? " " : $0.text }.joined().replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression) }.joined(separator: "\n")
        setNeedsDisplay()
    }

    override public func layoutSubviews() { super.layoutSubviews(); resizeToView() }
    private func resizeToView() {
        guard let s = session, bounds.width > 0, bounds.height > 0 else { return }
        let usableW = max(cellWidth, bounds.width - contentInsets.left - contentInsets.right), usableH = max(cellHeight, bounds.height - contentInsets.top - contentInsets.bottom)
        let w = max(1, min(TerminalSession.maximumColumns, Int(usableW / cellWidth)))
        var h = max(1, min(65536 / w, Int(usableH / cellHeight)))
        h = min(TerminalSession.maximumRows, h)
        if w == columns && h == rows && s.columns == w && s.rows == h { return }
        columns = w; rows = h
        try? s.resize(columns: w, rows: h)
        onResize?(w, h)
    }
    /// Asks the host to apply the current grid size (call after attaching a transport).
    public func notifyResize() { onResize?(columns, rows) }

    // MARK: shaping (port of the Android row shaper)

    private func shape(_ f: TerminalSession.Frame) {
        var out = [[DisplayCell]](); out.reserveCapacity(f.rows)
        for y in 0..<f.rows {
            var line = (0..<f.columns).map { x -> DisplayCell in
                let c = f.cell(row: y, column: x)
                return DisplayCell(text: c.text, span: c.wide == 1 ? 2 : 1, skip: false, cell: c)
            }
            var text = String.UnicodeScalarView(); var owners = [Int]()
            for x in 0..<f.columns where line[x].cell.wide < 2 {
                let v = line[x].cell.text.isEmpty ? " " : line[x].cell.text
                for sc in v.unicodeScalars { text.append(sc); owners.append(x) }
            }
            let row = String(text)
            if row.unicodeScalars.contains(where: { $0.value > 0x7f }) {
                var begin = 0
                for end in TerminalSession.graphemeEnds(row) {
                    let start = begin; begin = end
                    guard start < owners.count, end - 1 < owners.count else { continue }
                    let first = owners[start], last = owners[end - 1]
                    if first == last { continue }
                    var same = true
                    for x in (first + 1)...last where line[x].cell.wide < 2 && !sameStyle(line[first].cell, line[x].cell) { same = false }
                    if !same { continue }
                    let scalars = Array(row.unicodeScalars)
                    var s = String.UnicodeScalarView(); s.append(contentsOf: scalars[start..<end])
                    line[first].text = String(s)
                    line[first].span = last - first + (line[last].cell.wide == 1 ? 2 : 1)
                    for x in (first + 1)...last { line[x].skip = true }
                }
            }
            out.append(line)
        }
        display = out
    }
    private func sameStyle(_ a: TerminalSession.Cell, _ b: TerminalSession.Cell) -> Bool {
        a.foreground == b.foreground && a.background == b.background && a.flags == b.flags && a.underline == b.underline && a.underlineColor == b.underlineColor
    }

    // MARK: drawing

    private var blinkOn: Bool { Int((CACurrentMediaTime() * 1000) / 500) % 2 == 0 }
    public override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let bg = frameData.map { UIColor(rgb: $0.defaultBackground) } ?? UIColor(rgb: 0x151922)
        ctx.setFillColor(bg.cgColor); ctx.fill(bounds)
        guard let f = frameData else { return }
        ctx.translateBy(x: contentInsets.left, y: contentInsets.top)
        let textBlinkVisible = !windowActive || blinkOn
        for y in 0..<display.count {
            var x = 0
            while x < display[y].count {
                var end = x + 1
                while end < display[y].count && display[y][end].cell.background == display[y][x].cell.background { end += 1 }
                ctx.setFillColor(UIColor(rgb: display[y][x].cell.background).cgColor)
                ctx.fill(CGRect(x: CGFloat(x) * cellWidth, y: CGFloat(y) * cellHeight, width: CGFloat(end - x) * cellWidth, height: cellHeight))
                x = end
            }
        }
        drawSelection(ctx, f)
        for y in 0..<display.count {
            var x = 0
            while x < display[y].count {
                let v = display[y][x]
                defer { x += 1 }
                if v.cell.wide >= 2 || v.skip || v.text.isEmpty { continue }
                if v.cell.flags.contains(.invisible) || (v.cell.flags.contains(.blink) && !textBlinkVisible) { continue }
                drawGlyph(ctx, v.cell, v.text, col: x, row: y, span: v.span, color: nil)
            }
        }
        drawCursor(ctx, f, textBlinkVisible)
        if let sel = selection, handlesVisible { drawHandles(ctx, f, sel) }
    }

    private func drawGlyph(_ ctx: CGContext, _ c: TerminalSession.Cell, _ text: String, col: Int, row: Int, span: Int, color override: UIColor?) {
        let left = CGFloat(col) * cellWidth, top = CGFloat(row) * cellHeight, width = CGFloat(span) * cellWidth
        let font = fonts[Int(c.flags.rawValue & 3)]
        let color = override ?? UIColor(rgb: c.foreground)
        let fg = c.flags.contains(.faint) && override == nil ? color.withAlphaComponent(0.51) : color
        ctx.saveGState()
        ctx.clip(to: CGRect(x: left, y: top, width: width, height: cellHeight))
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: fg]
        let str = text as NSString
        var advance = str.size(withAttributes: attrs).width
        if advance <= 0 { advance = width }
        let y = top + (cellHeight - font.lineHeight) / 2
        if advance > width {  // keep protocol columns even when fallback fonts are wider
            ctx.translateBy(x: left, y: 0); ctx.scaleBy(x: width / advance, y: 1); ctx.translateBy(x: -left, y: 0)
        }
        str.draw(at: CGPoint(x: left, y: y), withAttributes: attrs)
        ctx.restoreGState()
        ctx.saveGState()
        ctx.setStrokeColor(fg.cgColor); ctx.setLineWidth(1)
        if c.flags.contains(.strikethrough) { ctx.move(to: CGPoint(x: left, y: top + cellHeight * 0.52)); ctx.addLine(to: CGPoint(x: left + width, y: top + cellHeight * 0.52)); ctx.strokePath() }
        if c.flags.contains(.overline) { ctx.move(to: CGPoint(x: left, y: top + 1)); ctx.addLine(to: CGPoint(x: left + width, y: top + 1)); ctx.strokePath() }
        ctx.setStrokeColor((override ?? UIColor(rgb: c.underlineColor)).cgColor)
        let bottom = top + cellHeight - 2
        switch c.underline {
        case 1, 2:
            ctx.move(to: CGPoint(x: left, y: bottom)); ctx.addLine(to: CGPoint(x: left + width, y: bottom))
            if c.underline == 2 { ctx.move(to: CGPoint(x: left, y: bottom - 3)); ctx.addLine(to: CGPoint(x: left + width, y: bottom - 3)) }
            ctx.strokePath()
        case 3:
            ctx.move(to: CGPoint(x: left, y: bottom)); var dx: CGFloat = 0
            while dx < width { ctx.addLine(to: CGPoint(x: left + dx, y: bottom + (Int(dx / 2) % 2 == 0 ? -1 : 1))); dx += 2 }
            ctx.strokePath()
        case 4, 5:
            ctx.setLineDash(phase: 0, lengths: c.underline == 4 ? [1, 2] : [4, 2])
            ctx.move(to: CGPoint(x: left, y: bottom)); ctx.addLine(to: CGPoint(x: left + width, y: bottom)); ctx.strokePath()
        default: break
        }
        ctx.restoreGState()
    }

    private func cursorRect(_ f: TerminalSession.Frame) -> CGRect? {
        guard f.cursorVisible, f.cursorY >= 0, f.cursorY < display.count, f.cursorX >= 0, f.cursorX < display[f.cursorY].count else { return nil }
        var x = f.cursorX
        if display[f.cursorY][x].cell.wide == 2 && x > 0 { x -= 1 }
        let w = display[f.cursorY][x].cell.wide == 1 ? 2 : 1
        return CGRect(x: CGFloat(x) * cellWidth, y: CGFloat(f.cursorY) * cellHeight, width: CGFloat(min(f.columns - x, w)) * cellWidth, height: cellHeight)
    }
    private var windowActive: Bool { window != nil && UIApplication.shared.applicationState == .active && (window?.isKeyWindow ?? false) }
    private func drawCursor(_ ctx: CGContext, _ f: TerminalSession.Frame, _ textBlink: Bool) {
        guard let r = cursorRect(f) else { return }
        let focused = windowActive
        if focused && f.cursorBlinking && !cursorPhaseVisible { return }
        let shape: TerminalSession.CursorShape = focused ? f.cursorShape : .hollow
        let color = UIColor(rgb: f.cursorColor)
        ctx.setFillColor(color.cgColor); ctx.setStrokeColor(color.cgColor)
        switch shape {
        case .bar: ctx.fill(CGRect(x: r.minX, y: r.minY, width: min(r.width, 2), height: r.height))
        case .underline: ctx.fill(CGRect(x: r.minX, y: max(r.minY, r.maxY - 2), width: r.width, height: min(2, r.height)))
        case .hollow: ctx.setLineWidth(1); ctx.stroke(r.insetBy(dx: 0.5, dy: 0.5))
        case .block:
            ctx.fill(r)
            var owner = f.cursorX
            while owner > 0 && (display[f.cursorY][owner].skip || display[f.cursorY][owner].cell.wide == 2) { owner -= 1 }
            let v = display[f.cursorY][owner]
            if !v.text.isEmpty && !v.cell.flags.contains(.invisible) && (!v.cell.flags.contains(.blink) || textBlink) {
                var fgc = UIColor(rgb: v.cell.background)
                if contrast(color, fgc) < 4.5 { fgc = luminance(color) > 0.179 ? .black : .white }
                ctx.saveGState(); ctx.clip(to: r)
                drawGlyph(ctx, v.cell, v.text, col: owner, row: f.cursorY, span: v.span, color: fgc)
                ctx.restoreGState()
            }
        }
    }
    private func luminance(_ c: UIColor) -> CGFloat {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0; c.getRed(&r, green: &g, blue: &b, alpha: &a)
        func l(_ v: CGFloat) -> CGFloat { v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * l(r) + 0.7152 * l(g) + 0.0722 * l(b)
    }
    private func contrast(_ a: UIColor, _ b: UIColor) -> CGFloat {
        let x = luminance(a) + 0.05, y = luminance(b) + 0.05; return max(x, y) / min(x, y)
    }
    /// Steady for 750 ms after activity, then 500 ms on/off.
    private var cursorPhaseVisible: Bool {
        let t = (CACurrentMediaTime() - blinkEpoch) * 1000
        if t < 750 { return true }
        return Int((t - 750) / 500) % 2 == 1
    }
    private func resetBlink() { blinkEpoch = CACurrentMediaTime() }
    private func updateBlinkTimer() {
        let needs = window != nil && ((frameData?.cursorVisible == true && frameData?.cursorBlinking == true) || display.contains { $0.contains { $0.cell.flags.contains(.blink) } })
        if needs && blinkTimer == nil {
            let t = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in self?.setNeedsDisplay() }
            RunLoop.main.add(t, forMode: .common); blinkTimer = t
        } else if !needs { blinkTimer?.invalidate(); blinkTimer = nil }
    }
    @objc private func appStateChanged() { resetBlink(); setNeedsDisplay() }
    public override func didMoveToWindow() {
        super.didMoveToWindow(); resetBlink(); updateBlinkTimer(); setNeedsDisplay()
        prioritizeNavigationBackGesture()
        DispatchQueue.main.async { [weak self] in self?.prioritizeNavigationBackGesture() }
    }

    private weak var navigationBackGesture: UIGestureRecognizer?
    private func prioritizeNavigationBackGesture() {
        guard window != nil else { return }
        var responder: UIResponder? = self
        while let current = responder {
            if let controller = current as? UIViewController,
               let navigation = (controller as? UINavigationController) ?? controller.navigationController,
               let back = navigation.interactivePopGestureRecognizer {
                if navigationBackGesture !== back {
                    pan.require(toFail: back)
                    navigationBackGesture = back
                }
                return
            }
            responder = current.next
        }
    }

    // MARK: selection

    private var handlesVisible: Bool { selection != nil && selectionEnabled }
    private func screenIndex(row: Int, col: Int) -> Int { ((frameData?.scrollOffset ?? 0) + row) * columns + col }
    private func cellAt(_ viewPoint: CGPoint) -> (row: Int, col: Int) {
        let p = local(viewPoint)
        return (min(max(0, Int(p.y / cellHeight)), max(0, rows - 1)), min(max(0, Int(p.x / cellWidth)), max(0, columns - 1)))
    }
    private func snapped(_ index: Int, end: Bool) -> Int {
        guard let f = frameData else { return index }
        let off = f.scrollOffset * columns
        let local = index - off
        guard local >= 0, local / columns < display.count else { return index }
        let (r, c) = (local / columns, local % columns)
        let cell = display[r][c].cell
        if cell.wide == 2 && c > 0 { return index - (end ? 0 : 1) }
        if cell.wide == 1 && end && c + 1 < columns { return index + 1 }
        return index
    }
    public func select(from a: Int, to b: Int) {
        guard selectionEnabled, let s = session else { return }
        let lo = snapped(min(a, b), end: false), hi = snapped(max(a, b), end: true)
        guard let text = try? s.selectedText(from: lo, to: hi) else { return }
        selection = (lo, hi); setSelectedText(text); setNeedsDisplay()
    }
    public func clearSelection() { setSelection(nil) }
    private func setSelection(_ value: (start: Int, end: Int)?) {
        selection = value
        if value == nil { setSelectedText("") }
        setNeedsDisplay()
    }
    private func setSelectedText(_ text: String) {
        guard text != selectedText else { return }
        selectedText = text; onSelectionChanged?(text)
    }
    private func drawSelection(_ ctx: CGContext, _ f: TerminalSession.Frame) {
        guard let sel = selection else { return }
        ctx.setFillColor(UIColor(red: 0.2, green: 0.5, blue: 1, alpha: 0.38).cgColor)
        let base = f.scrollOffset * columns
        for y in 0..<f.rows {
            let rs = base + y * columns, re = rs + columns - 1
            if sel.end < rs || sel.start > re { continue }
            let from = max(sel.start, rs) - rs, to = min(sel.end, re) - rs
            ctx.fill(CGRect(x: CGFloat(from) * cellWidth, y: CGFloat(y) * cellHeight, width: CGFloat(to - from + 1) * cellWidth, height: cellHeight))
        }
    }
    private func handlePoint(_ index: Int, end: Bool) -> CGPoint? {
        guard let f = frameData else { return nil }
        let local = index - f.scrollOffset * columns
        let row = local / columns, col = local % columns
        guard local >= 0, row < f.rows else { return nil }
        return CGPoint(x: CGFloat(col + (end ? 1 : 0)) * cellWidth, y: CGFloat(row + 1) * cellHeight)
    }
    /// Lollipop handles: a thin stem under the selection edge with a round grip, so they never cover the selected text.
    private func drawHandles(_ ctx: CGContext, _ f: TerminalSession.Frame, _ sel: (start: Int, end: Int)) {
        let blue = UIColor(red: 0.25, green: 0.52, blue: 1, alpha: 1)
        ctx.setFillColor(blue.cgColor); ctx.setStrokeColor(blue.cgColor); ctx.setLineWidth(2)
        let collapsed = sel.start == sel.end
        for (i, p) in [handlePoint(sel.start, end: false), handlePoint(sel.end, end: true)].enumerated() {
            guard let p else { continue }
            let x = p.x + (collapsed ? (i == 0 ? -9 : 9) : 0), top = p.y - cellHeight, bottom = p.y + 6
            ctx.move(to: CGPoint(x: x, y: top)); ctx.addLine(to: CGPoint(x: x, y: bottom)); ctx.strokePath()
            ctx.fillEllipse(in: CGRect(x: x - 7, y: bottom, width: 14, height: 14))
        }
    }

    // MARK: gestures

    private var pan: UIPanGestureRecognizer!
    private func installGestures() {
        let single = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:))); double.numberOfTapsRequired = 2
        let triple = UITapGestureRecognizer(target: self, action: #selector(tripleTapped(_:))); triple.numberOfTapsRequired = 3
        single.require(toFail: double); double.require(toFail: triple)
        let long = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:))); long.minimumPressDuration = 0.4
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(doubleHeld(_:))); hold.numberOfTapsRequired = 1; hold.minimumPressDuration = 0.35
        double.require(toFail: hold)
        pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:))); pan.maximumNumberOfTouches = 1
        let two = UIPanGestureRecognizer(target: self, action: #selector(twoPanned(_:))); two.minimumNumberOfTouches = 2; two.maximumNumberOfTouches = 2
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        for g in [single, double, triple, long, hold, pan, two, pinch] as [UIGestureRecognizer] { addGestureRecognizer(g) }
    }
    private func fire(_ g: Gesture, _ p: CGPoint) -> Bool { onGesture?(g, p) ?? false }
    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let p = g.location(in: self)
        if selection != nil { clearSelection() }
        if !fire(.singleTap, p) { _ = becomeFirstResponder() }
    }
    @objc private func doubleTapped(_ g: UITapGestureRecognizer) { _ = fire(.doubleTap, g.location(in: self)) }
    @objc private func tripleTapped(_ g: UITapGestureRecognizer) { _ = fire(.tripleTap, g.location(in: self)) }
    @objc private func longPressed(_ g: UILongPressGestureRecognizer) {
        guard selectionEnabled, !mouseCaptured else { return }
        let p = g.location(in: self)
        switch g.state {
        case .began:
            let (r, c) = cellAt(p); let (a, b) = wordRange(row: r, col: c)
            select(from: screenIndex(row: r, col: a), to: screenIndex(row: r, col: b))
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .changed:
            guard let sel = selection else { return }
            let (r, c) = cellAt(p); select(from: sel.start, to: screenIndex(row: r, col: c))
        default: break
        }
    }
    private func wordRange(row: Int, col: Int) -> (Int, Int) {
        guard row < display.count else { return (col, col) }
        func isWord(_ x: Int) -> Bool { let t = display[row][x].text; return !t.isEmpty && !t.allSatisfy { $0.isWhitespace } }
        guard col < display[row].count, isWord(col) else { return (col, col) }
        var a = col, b = col
        while a > 0 && isWord(a - 1) { a -= 1 }
        while b + 1 < display[row].count && isWord(b + 1) { b += 1 }
        return (a, b)
    }
    /// Fires only when the host assigns a Zen layout; otherwise the press falls through to nothing.
    @objc private func doubleHeld(_ g: UILongPressGestureRecognizer) {
        let p = g.location(in: self)
        switch g.state {
        case .began: zenActive = fire(.doubleHoldStart, p)
        case .changed: if zenActive { _ = fire(.doubleHoldMove, p) }
        case .ended: if zenActive { _ = fire(.doubleHoldEnd, p) }; zenActive = false
        case .cancelled, .failed: if zenActive { _ = fire(.doubleHoldCancel, p) }; zenActive = false
        default: break
        }
    }
    private var zenActive = false
    private var mouseCaptured: Bool { (frameData?.mouseTracking ?? false) && mouseSelectionEnabled }

    @objc private func panned(_ g: UIPanGestureRecognizer) {
        let p = g.location(in: self)
        switch g.state {
        case .began:
            handleDrag = nil
            if let sel = selection, handlesVisible {
                let hs = handlePoint(sel.start, end: false), he = handlePoint(sel.end, end: true)
                let lp = local(p)
                func near(_ h: CGPoint?) -> Bool { h.map { hypot($0.x - lp.x, $0.y - lp.y) < 44 } ?? false }
                if near(he) { handleDrag = 1 } else if near(hs) { handleDrag = 0 }
            }
            if mouseCaptured, handleDrag == nil { mousePressed = true; sendMouse(.press, p) }
            scrollRemainder = 0
        case .changed:
            if let h = handleDrag, let sel = selection {
                let (r, c) = cellAt(p); let idx = screenIndex(row: r, col: c)
                h == 0 ? select(from: idx, to: sel.end) : select(from: sel.start, to: idx)
            } else if mousePressed { sendMouse(.motion, p) }
            else {
                let dy = g.translation(in: self).y; g.setTranslation(.zero, in: self)
                scrollBy(dy)
            }
        case .ended, .cancelled, .failed:
            if mousePressed { mousePressed = false; sendMouse(.release, p) }
            else if g.state == .ended, handleDrag == nil {
                let t = g.translation(in: self), v = g.velocity(in: self)
                if abs(v.x) > 600 && abs(v.x) > abs(v.y) * 2 { _ = fire(v.x < 0 ? .swipeLeft : .swipeRight, p) }
                else if v.y > 900 && abs(v.y) > abs(v.x) * 2 && (frameData?.scrollOffset ?? 1) == 0 && t.y > 80 { _ = fire(.pullDown, p) }
            }
            handleDrag = nil
        default: break
        }
    }
    private func scrollBy(_ dy: CGFloat) {
        guard let s = session, let f = frameData else { return }
        scrollRemainder += dy
        let lines = Int(scrollRemainder / cellHeight)
        if lines == 0 { return }
        scrollRemainder -= CGFloat(lines) * cellHeight
        if f.alternateScreen && !f.mouseTracking {          // swipe becomes cursor keys when the app owns the screen
            let key: TerminalSession.Key = lines > 0 ? .up : .down
            for _ in 0..<min(abs(lines), 10) { if let d = try? s.encode(key: key) { onInput?(d) } }
        } else if f.mouseTracking {
            for _ in 0..<min(abs(lines), 10) { if let d = try? s.encodeWheel(down: lines < 0, x: 1, y: 1, cellWidth: Int(cellWidth), cellHeight: Int(cellHeight)) { onInput?(d) } }
        } else { try? s.scroll(by: -lines) }               // finger down = older lines
    }
    private func sendMouse(_ a: TerminalSession.MouseAction, _ viewPoint: CGPoint) {
        guard let s = session else { return }
        let p = local(viewPoint)
        let x = Float(max(0, min(CGFloat(columns) * cellWidth - 1, p.x))), y = Float(max(0, min(CGFloat(rows) * cellHeight - 1, p.y)))
        if let d = try? s.encodeMouse(a, right: false, x: x, y: y, cellWidth: Int(cellWidth), cellHeight: Int(cellHeight)), !d.isEmpty { onInput?(d) }
    }
    @objc private func twoPanned(_ g: UIPanGestureRecognizer) {
        guard g.state == .ended else { return }
        let v = g.velocity(in: self), p = g.location(in: self)
        guard max(abs(v.x), abs(v.y)) > 500 else { return }
        if abs(v.x) > abs(v.y) { _ = fire(v.x < 0 ? .twoLeft : .twoRight, p) } else { _ = fire(v.y < 0 ? .twoUp : .twoDown, p) }
    }
    @objc private func pinched(_ g: UIPinchGestureRecognizer) {
        switch g.state {
        case .began: pinchAmount = 1
        case .changed:
            if g.scale > 1.12 { if !fire(.pinchOut, g.location(in: self)) { setFontSize(_fontSize + 1) }; g.scale = 1 }
            else if g.scale < 0.89 { if !fire(.pinchIn, g.location(in: self)) { setFontSize(_fontSize - 1) }; g.scale = 1 }
        default: break
        }
    }

    // MARK: input (UIKeyInput + hardware keys)

    public override var canBecomeFirstResponder: Bool { true }
    public var hasText: Bool { true }
    public var keyboardType: UIKeyboardType = .asciiCapable
    public var autocorrectionType: UITextAutocorrectionType = .no
    public var autocapitalizationType: UITextAutocapitalizationType = .none
    public var spellCheckingType: UITextSpellCheckingType = .no
    public var smartQuotesType: UITextSmartQuotesType = .no
    public var smartDashesType: UITextSmartDashesType = .no
    public var smartInsertDeleteType: UITextSmartInsertDeleteType = .no
    public var keyboardAppearance: UIKeyboardAppearance = .dark
    private var _inputAccessory: UIView?
    public override var inputAccessoryView: UIView? { _inputAccessory }
    public func setInputAccessory(_ v: UIView?) { _inputAccessory = v; reloadInputViews() }
    public override func becomeFirstResponder() -> Bool { let r = super.becomeFirstResponder(); onFocusChanged?(r); return r }
    public override func resignFirstResponder() -> Bool { let r = super.resignFirstResponder(); onFocusChanged?(!r); return r }

    private func emit(_ d: Data) {
        guard session != nil, onInput != nil else { return }
        resetBlink(); scrollToBottom(); onInput?(d)
    }
    public func scrollToBottom() { clearSelection(); try? session?.scroll(by: Int.max / 4) }
    public func insertText(_ text: String) {
        guard let s = session else { return }
        if text == "\n" { sendKey(.enter); return }
        let mods = consumeModifiers()
        if !mods.isEmpty, text.unicodeScalars.count == 1, let sc = text.unicodeScalars.first, let d = try? s.encode(character: Self.unshifted(sc), text: text, modifiers: mods) { emit(d); return }
        emit(Data(text.utf8))
    }
    public func deleteBackward() { sendKey(.backspace) }
    private func consumeModifiers() -> TerminalSession.Modifiers {
        let m = pendingModifiers.union(lockedModifiers)
        if !pendingModifiers.isEmpty { pendingModifiers = [] }
        return m
    }
    static func unshifted(_ s: Unicode.Scalar) -> UInt32 {
        let l = String(s).lowercased().unicodeScalars; return l.count == 1 ? l.first!.value : s.value
    }
    public func sendText(_ text: String) { emit(Data(text.utf8)) }
    /// Bulk text as one terminal operation (bracketed paste when the app enabled it). Does not submit.
    public func pasteText(_ text: String) { if let s = session, let d = try? s.encodePaste(text) { emit(d) } }
    public func sendKey(_ key: TerminalSession.Key, modifiers: TerminalSession.Modifiers = []) {
        guard let s = session else { return }
        let m = modifiers.union(consumeModifiers())
        if let d = try? s.encode(key: key, modifiers: m) { emit(d) }
    }
    public func sendCharacter(_ codePoint: UInt32, text: String, modifiers: TerminalSession.Modifiers) {
        if let s = session, let d = try? s.encode(character: codePoint, text: text, modifiers: modifiers) { emit(d) }
    }

    public override var keyCommands: [UIKeyCommand]? { nil }
    public override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var unhandled = Set<UIPress>()
        for p in presses { if !handle(press: p) { unhandled.insert(p) } }
        if !unhandled.isEmpty { super.pressesBegan(unhandled, with: event) }
    }
    private static let hid: [UIKeyboardHIDUsage: TerminalSession.Key] = [
        .keyboardUpArrow: .up, .keyboardDownArrow: .down, .keyboardLeftArrow: .left, .keyboardRightArrow: .right,
        .keyboardPageUp: .pageUp, .keyboardPageDown: .pageDown, .keyboardHome: .home, .keyboardEnd: .end,
        .keyboardReturnOrEnter: .enter, .keypadEnter: .enter, .keyboardTab: .tab, .keyboardEscape: .escape,
        .keyboardDeleteOrBackspace: .backspace, .keyboardDeleteForward: .delete, .keyboardInsert: .insert,
        .keyboardF1: .f1, .keyboardF2: .f2, .keyboardF3: .f3, .keyboardF4: .f4, .keyboardF5: .f5, .keyboardF6: .f6,
        .keyboardF7: .f7, .keyboardF8: .f8, .keyboardF9: .f9, .keyboardF10: .f10, .keyboardF11: .f11, .keyboardF12: .f12,
    ]
    private func handle(press: UIPress) -> Bool {
        guard let key = press.key, session != nil else { return false }
        var mods: TerminalSession.Modifiers = []
        let f = key.modifierFlags
        if f.contains(.shift) { mods.insert(.shift) }
        if f.contains(.control) { mods.insert(.ctrl) }
        if f.contains(.alternate) && optionAsMeta { mods.insert(.alt) }
        if f.contains(.command) { return false }       // leave Cmd shortcuts (copy/paste) to the system
        if let named = Self.hid[key.keyCode] { sendKey(named, modifiers: mods); return true }
        let chars = key.charactersIgnoringModifiers
        guard let sc = chars.unicodeScalars.first, chars.unicodeScalars.count == 1 else { return false }
        if mods.isEmpty || mods == .shift {
            if f.contains(.alternate) && !optionAsMeta { insertText(key.characters); return true }
            return false          // plain typing arrives through insertText
        }
        let produced = key.characters.isEmpty ? chars : (mods.contains(.shift) ? chars.uppercased() : chars)
        sendCharacter(Self.unshifted(sc), text: produced, modifiers: mods.union(consumeModifiers()))
        return true
    }
    public override func paste(_ sender: Any?) { if let t = UIPasteboard.general.string { pasteText(t) } }
    public override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool { action == #selector(paste(_:)) ? UIPasteboard.general.hasStrings : false }

    // MARK: test hooks

    public var cellSize: CGSize { CGSize(width: cellWidth, height: cellHeight) }
    public var currentFrame: TerminalSession.Frame? { frameData }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255, blue: CGFloat(rgb & 255) / 255, alpha: 1)
    }
}
