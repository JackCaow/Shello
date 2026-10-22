import SwiftUI
import ShelloCore
import ShelloServices
import ShelloTerminal
import ShelloTerminalUI

/// Executes toolbar / gesture actions against the visible terminal and owns the transient input UI state.
@MainActor
final class TerminalController: ObservableObject {
    let env: AppEnvironment
    weak var view: TerminalView?
    @Published var showShortcuts = false
    @Published var lockedShortcuts = false
    @Published var showPad = false
    @Published var padLocked = false
    @Published var minimized = false
    @Published var showTools = false
    @Published var showOverview = false
    @Published var showHistory = false
    @Published var showPreview = false
    @Published var keyboardVisible = false
    @Published var ctrl = false
    @Published var alt = false
    @Published var shift = false
    @Published var superKey = false
    @Published var lockedCtrl = false
    @Published var selection = ""
    @Published var zenCenter: CGPoint?
    @Published var zenSelected: Int?
    @Published var group = "Claude"
    var attachments: [ComposerAttachment] { env.composerAttachments }
    var history: [String] { get { UserDefaults.standard.stringArray(forKey: "input.history") ?? [] } set { UserDefaults.standard.set(Array(newValue.prefix(100)), forKey: "input.history") } }

    init(env: AppEnvironment) { self.env = env; minimized = false }
    var prefs: InputPreferences { env.prefs }
    var tab: TerminalTab? { env.sessions.selected }
    var currentAttachments: [ComposerAttachment] { attachments.filter { $0.tabID == tab?.id } }
    var hideTouchControls: Bool { env.hardwareKeyboard.connected && prefs.flag("hide_with_hardware", default: false) }

    // MARK: sending

    func send(_ data: Data) { guard let t = tab else { return }; env.sessions.send(t, data) }
    func sendText(_ text: String) { view?.sendText(text) }
    func key(_ k: TerminalSession.Key, _ mods: TerminalSession.Modifiers = []) { view?.sendKey(k, modifiers: mods) }
    func paste() {
        guard let text = UIPasteboard.general.string, !text.isEmpty else { return }
        if tab?.draftMode == true { tab?.draft += text } else { view?.pasteText(text) }
    }
    func syncModifiers() {
        guard let v = view else { return }
        var pend: TerminalSession.Modifiers = []
        if ctrl && !lockedCtrl { pend.insert(.ctrl) }; if alt { pend.insert(.alt) }; if shift { pend.insert(.shift) }; if superKey { pend.insert(.super) }
        v.pendingModifiers = pend; v.lockedModifiers = lockedCtrl ? .ctrl : []
    }
    /// Called by the terminal view when a one-shot modifier was consumed by a keypress.
    func modifiersConsumed() {
        guard let v = view else { return }
        if v.pendingModifiers.isEmpty { ctrl = lockedCtrl; alt = false; shift = false; superKey = false }
    }

    // MARK: actions

    /// Runs a binding grammar string (`C-b, c`, `text:/clear`, `ENTER` ...).
    func runBinding(_ binding: String, enter: Bool = false) {
        guard let strokes = try? InputActions.parse(binding), let v = view else { return }
        if binding.hasPrefix("action:") { perform(String(binding.dropFirst(7))); return }
        for s in strokes {
            if s.paste { v.pasteText(s.text) }
            else if let k = s.key { v.sendKey(k, modifiers: s.modifiers) }
            else { v.sendCharacter(s.codePoint, text: s.text, modifiers: s.modifiers) }
        }
        if enter { v.sendKey(.enter) }
    }

    func perform(_ id: String) {
        switch id {
        case "ctrl": ctrl.toggle(); if !ctrl { lockedCtrl = false }; syncModifiers()
        case "alt": alt.toggle(); syncModifiers()
        case "shift": shift.toggle(); syncModifiers()
        case "super": superKey.toggle(); syncModifiers()
        case "lock_ctrl": lockedCtrl.toggle(); ctrl = lockedCtrl; syncModifiers()
        case "pad": showPad.toggle()
        case "panel": showTools = true
        case "paste": paste()
        case "history": showHistory = true
        case "chat": setDraftMode(!(tab?.draftMode ?? false))
        case "keyboard": toggleKeyboard()
        case "hide_keyboard": hideKeyboard()
        case "minimize", "zen": minimized.toggle()
        case "shortcuts": showShortcuts.toggle()
        case "lock_shortcuts": lockedShortcuts.toggle(); if lockedShortcuts { showShortcuts = true }
        case "select": view?.selectionEnabled = true; env.showToast(tr("input_hold_to_select"))
        case "mouse": if let v = view { v.mouseSelectionEnabled.toggle() }
        case "right_click": view?.rightClickEnabled.toggle()
        case "drag_arrows": view?.dragArrowsEnabled.toggle()
        case "changes": runBinding("text:/diff", enter: true)
        case "preview": showPreview = true
        case "dictation": env.showToast(tr("voice_input_not_available_yet"))
        case "mux_next": runBinding(prefs.prefix("Tmux") + ", n")
        case "mux_previous": runBinding(prefs.prefix("Tmux") + ", p")
        case "pane_next": runBinding(prefs.prefix("Tmux") + ", o")
        case "pane_previous": runBinding(prefs.prefix("Tmux") + ", ;")
        case "session_next": switchTab(1)
        case "session_previous": switchTab(-1)
        case "close_session": if let t = tab { env.sessions.close(t); if env.sessions.tabs.isEmpty { env.route = nil } }
        case "none": break
        default:
            if let a = prefs.action(id) { runBinding(a.binding, enter: a.enter) }
        }
    }
    func switchTab(_ delta: Int) {
        let tabs = env.sessions.tabs; guard tabs.count > 1, let cur = tabs.firstIndex(where: { $0.id == env.sessions.selectedID }) else { return }
        env.sessions.select(tabs[((cur + delta) % tabs.count + tabs.count) % tabs.count].id)
    }
    var zenIDs: [String] { Array(prefs.list("layout_zen").filter { $0 != "zen" }.prefix(8)) }
    func zen(_ g: TerminalView.Gesture, at p: CGPoint) -> Bool {
        let ids = zenIDs
        switch g {
        case .doubleHoldStart:
            guard !ids.isEmpty else { return false }
            zenCenter = p; zenSelected = nil; haptic(); return true
        case .doubleHoldMove:
            guard let c = zenCenter else { return false }
            let i = ZenRing.index(dx: Double(p.x - c.x), dy: Double(p.y - c.y), count: ids.count)
            if i != zenSelected { zenSelected = i; if i != nil { haptic() } }
            return true
        case .doubleHoldEnd:
            let chosen = zenSelected.map { ids[$0] }
            zenCenter = nil; zenSelected = nil
            if let chosen { perform(chosen) }
            return true
        case .doubleHoldCancel: zenCenter = nil; zenSelected = nil; return true
        default: return false
        }
    }
    func perform(gesture g: TerminalView.Gesture) -> Bool {
        let map: [TerminalView.Gesture: String] = [.singleTap: "single_tap", .doubleTap: "double_tap", .tripleTap: "triple_tap", .pullDown: "pull_down", .swipeLeft: "swipe_left", .swipeRight: "swipe_right",
                                                    .twoLeft: "two_left", .twoRight: "two_right", .twoUp: "two_up", .twoDown: "two_down"]
        guard let key = map[g] else { return false }
        let action = prefs.mapping(key)
        if action == "none" { return false }
        perform(action); return true
    }

    func toggleKeyboard() { guard let v = view else { return }; if v.isFirstResponder { _ = v.resignFirstResponder() } else { _ = v.becomeFirstResponder() } }
    func hideKeyboard() { _ = view?.resignFirstResponder() }
    func setDraftMode(_ on: Bool) {
        tab?.draftMode = on
        if on { hideKeyboard() } else { _ = view?.becomeFirstResponder() }
    }

    // MARK: composer

    /// Sends the draft as one paste; `enter` submits it. Draft stays when the SSH connection is down.
    func sendDraft(enter: Bool) {
        guard let t = tab, let payload = DraftSubmission.payload(text: t.draft, paths: currentAttachments.map { $0.transfer.remotePath }) else { return }
        guard t.isConnected else { env.showToast(tr("ssh_is_disconnected_draft_kept")); return }
        guard let view else { return }
        if !payload.isEmpty {
            view.pasteText(payload)
            if !t.draft.isEmpty { var h = history; h.removeAll { $0 == t.draft }; h.insert(t.draft, at: 0); history = h }
        }
        if enter { view.sendKey(.enter) }
        t.draft = ""
        for item in currentAttachments { remove(item) }
    }

    // MARK: attachments

    func attach(url: URL, name: String? = nil, temporary: Bool) {
        guard let t = tab, t.isConnected else { env.showToast(tr("attachment_requires_ssh")); return }
        guard currentAttachments.count < 4 else { env.showToast(tr("attachment_limit")); return }
        let a = AttachmentTransfer(profileID: t.profileID, store: env.store, source: url, displayName: name)
        // Keep the source until the chip is removed/sent, so a completed upload can still show its thumbnail.
        let item = ComposerAttachment(tabID: t.id, transfer: a, ownsSource: temporary)
        env.composerAttachments.append(item); a.start()
        setDraftMode(true)
        Task { @MainActor [weak env = env, weak a, weak item] in
            while let a, a.isBusy { try? await Task.sleep(nanoseconds: 100_000_000) }
            guard let env, let a, let item, env.composerAttachments.contains(where: { $0.id == item.id }) else { return }
            env.objectWillChange.send()
            guard a.remotePath == nil else { return }
            if case .failed(.tooLarge) = a.state { env.showToast(tr("attachment_too_large")) }
            else if case .failed(.cancelled) = a.state {} else { env.showToast(tr("attachment_upload_failed")) }
        }
    }
    func remove(_ item: ComposerAttachment) { item.discard(); env.composerAttachments.removeAll { $0.id == item.id } }

    /// Applies the app theme to the VT default colors (OSC 10/11), like the Android app.
    func applyTheme(to session: TerminalSession) {
        let t = env.settings.current
        let fg = t.light ? "#202124" : "#d4d4d4", bg = t.light ? "#f5f5f5" : t.id == "black" ? "#000000" : "#1e1e1e"
        _ = try? session.write("\u{1b}]10;\(fg)\u{7}\u{1b}]11;\(bg)\u{7}")
    }
}
