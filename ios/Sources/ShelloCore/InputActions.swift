import Foundation
import ShelloTerminal

/// Shared catalog and validated, transport-independent shortcut grammar (port of Android `InputActions`).
public enum InputActions {
    public struct Action: Equatable {
        public let id: String, label: String, icon: String, binding: String, enter: Bool
    }
    public struct Stroke: Equatable {
        public var key: TerminalSession.Key?
        public var codePoint: UInt32
        public var text: String
        public var modifiers: TerminalSession.Modifiers
        public var paste: Bool
    }

    public static let catalog: [String: Action] = buildCatalog().0
    public static let catalogOrder: [String] = buildCatalog().1
    public static let groups: [String: [String]] = buildGroups()
    public static let groupOrder = ["Favorites", "Tmux", "Claude", "Ctrl", "Codex", "Herdr", "Copilot", "Cursor", "OpenCode", "Zellij"]

    static let builtins: [(String, String, String)] = [
        ("ctrl", "Ctrl", "keyboard"), ("alt", "Opt", "keyboard"), ("shift", "Shift", "keyboard"), ("super", "Cmd", "keyboard"),
        ("pad", "input_d_pad", "pad"), ("panel", "input_sidebar", "panel"), ("paste", "input_paste", "paste"), ("history", "input_input_history", "history"),
        ("chat", "input_text_input", "chat"), ("keyboard", "input_keyboard", "keyboard"), ("shortcuts", "input_shortcut_panel", "shortcuts"),
        ("minimize", "input_minimize", "down"), ("hide_keyboard", "input_hide_keyboard", "keyboard"), ("zen", "input_zen_ring", "toolbox"),
        ("select", "input_text_selection", "pad"), ("mouse", "input_mouse_selection", "pad"), ("right_click", "input_right_click", "pad"),
        ("drag_arrows", "input_drag_arrow_keys", "pad"), ("changes", "input_code_changes", "edit"), ("preview", "input_web_preview", "server"),
        ("dictation", "input_dictation", "mic"), ("none", "input_none", "close"),
    ]

    public static func keyLabel(_ key: TerminalSession.Key) -> String {
        switch key {
        case .up: return "↑"; case .down: return "↓"; case .left: return "←"; case .right: return "→"
        case .pageUp: return "PgUp"; case .pageDown: return "PgDn"; case .escape: return "Esc"; case .backspace: return "⌫"
        default: return keyName(key).capitalized
        }
    }
    /// Android's enum names (UP, PAGE_UP, F1 ...) so stored bindings stay portable between platforms.
    public static func keyName(_ key: TerminalSession.Key) -> String {
        switch key {
        case .up: return "UP"; case .down: return "DOWN"; case .left: return "LEFT"; case .right: return "RIGHT"
        case .pageUp: return "PAGE_UP"; case .pageDown: return "PAGE_DOWN"; case .home: return "HOME"; case .end: return "END"
        case .enter: return "ENTER"; case .tab: return "TAB"; case .escape: return "ESCAPE"; case .backspace: return "BACKSPACE"
        case .delete: return "DELETE"; case .insert: return "INSERT"
        default: return "F\(key.rawValue - TerminalSession.Key.f1.rawValue + 1)"
        }
    }
    static func key(named name: String) -> TerminalSession.Key? { TerminalSession.Key.allCases.first { keyName($0) == name } }

    private static func buildCatalog() -> ([String: Action], [String]) {
        var map = [String: Action](), order = [String]()
        func add(_ id: String, _ label: String, _ icon: String, _ binding: String, _ enter: Bool = false) { map[id] = Action(id: id, label: label, icon: icon, binding: binding, enter: enter); order.append(id) }
        for (id, label, icon) in builtins { add(id, label, icon, "action:" + id) }
        for k in TerminalSession.Key.allCases { add("key_" + keyName(k), keyLabel(k), "keyboard", keyName(k)) }
        for c in ["|", "/", "\\", "~", "-", "_"] { add("char_\(c.unicodeScalars.first!.value)", c, "keyboard", "text:" + c) }
        for c in "cdzlaeuw" { add("ctrl_\(c)", "Ctrl+\(c.uppercased())", "keyboard", "C-\(c)") }
        for (group, items) in presets {
            for (i, item) in items.enumerated() {
                let parts = item.split(separator: ":", maxSplits: 1).map(String.init)
                let label = parts.count == 2 ? parts[0] : item
                add("preset_\(group)_\(i)", label, "keyboard", parts.count == 2 ? parts[1] : "text:" + item, !(parts.count == 2) && item.hasPrefix("/"))
            }
        }
        return (map, order)
    }
    static let presets: [(String, [String])] = [
        ("Tmux", ["new win:C-b, c", "next:C-b, n", "prev:C-b, p", "detach:C-b, d", "windows:C-b, w", "zoom:C-b, z", "kill:C-b, x", "last:C-b, l"]),
        ("Claude", ["/clear", "Shift+Tab:S-TAB", "/compact", "/resume", "/model", "/permissions", "/diff", "/usage"]),
        ("Ctrl", ["Ctrl+C:C-c", "Ctrl+D:C-d", "Ctrl+Z:C-z", "Ctrl+L:C-l", "Ctrl+A:C-a", "Ctrl+E:C-e", "Ctrl+U:C-u", "Ctrl+W:C-w"]),
        ("Codex", ["/new", "/compact", "/model", "/permissions", "/plan", "/resume", "/diff", "/status"]),
        ("Herdr", ["new tab:C-b, c", "next:C-b, n", "prev:C-b, p", "detach:C-b, q", "spaces:C-b, w", "goto:C-b, g", "zoom:C-b, z", "kill:C-b, x"]),
        ("Copilot", ["/clear", "/model", "/agent", "/delegate", "/share", "/session", "/mcp", "/help", "/add-dir", "/list-dirs", "/cwd", "/theme", "/terminal-setup", "/reset-allowed-tools", "/feedback", "/login", "/logout", "/user"]),
        ("Cursor", ["/new-chat", "/plan", "/ask", "/compress", "/resume", "/model", "/auto-run", "/usage", "/max-mode", "/sandbox", "/vim", "/about", "/help", "/copy-request-id", "/copy-conversation-id", "/setup-terminal", "/mcp list", "/mcp enable", "/mcp disable", "/rules"]),
        ("OpenCode", ["/new", "/sessions", "/models", "/init", "/review", "/skills", "/status", "/themes"]),
        ("Zellij", ["new tab:C-t, n", "close tab:C-t, x", "rename:C-t, r", "next:C-t, l", "prev:C-t, h", "sync:C-t, s", "break:C-t, b", "toggle:C-t, TAB"]),
    ]
    private static func buildGroups() -> [String: [String]] {
        var g = [String: [String]](); g["Favorites"] = []
        for (name, items) in presets { g[name] = items.indices.map { "preset_\(name)_\($0)" } }
        return g
    }

    /// Parses `action:id`, `text:literal`, or a comma/space separated key sequence such as `C-b, c` or `Ctrl+Shift+Tab`.
    public static func parse(_ binding: String?) throws -> [Stroke] {
        guard let binding, !binding.trimmingCharacters(in: .whitespaces).isEmpty else { throw ShelloError.invalid("input_enter_a_key_or_text_binding") }
        if binding.hasPrefix("action:") {
            guard catalog[String(binding.dropFirst(7))] != nil else { throw ShelloError.invalid("input_unknown_action") }
            return []
        }
        if binding.hasPrefix("text:") { return [Stroke(key: nil, codePoint: 0, text: String(binding.dropFirst(5)), modifiers: [], paste: true)] }
        var result = [Stroke]()
        let normalized = binding.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "\\s*,\\s*", with: ",", options: .regularExpression)
        for step in normalized.components(separatedBy: CharacterSet(charactersIn: ", \t")) {
            var value = step; var mods: TerminalSession.Modifiers = []
            while let r = value.range(of: "^(ctrl|control|alt|opt|option|shift|cmd|super|meta|[cmasd])[-+]", options: [.regularExpression, .caseInsensitive]) {
                let m = value[r].dropLast().lowercased()
                switch m { case "c", "ctrl", "control": mods.insert(.ctrl); case "s", "shift": mods.insert(.shift); case "d", "cmd", "super": mods.insert(.super); default: mods.insert(.alt) }
                value.removeSubrange(r)
            }
            if value.isEmpty { throw ShelloError.invalid("input_the_key_sequence_contains_an_empty_step") }
            var name = value.uppercased().replacingOccurrences(of: "PAGEUP", with: "PAGE_UP").replacingOccurrences(of: "PAGEDOWN", with: "PAGE_DOWN")
            switch name { case "ESC": name = "ESCAPE"; case "RETURN": name = "ENTER"; case "SPACE": name = " "; case "DASH": name = "-"; case "PLUS": name = "+"; case "COMMA": name = ","; case "PGUP": name = "PAGE_UP"; case "PGDN": name = "PAGE_DOWN"; default: break }
            if let k = key(named: name) { result.append(Stroke(key: k, codePoint: 0, text: "", modifiers: mods, paste: false)) }
            else if name == " " { result.append(Stroke(key: nil, codePoint: 32, text: " ", modifiers: mods, paste: false)) }
            else if ["-", "+", ","].contains(name) { result.append(Stroke(key: nil, codePoint: name.unicodeScalars.first!.value, text: name, modifiers: mods, paste: false)) }
            else if value.unicodeScalars.count == 1 {
                let cp = value.unicodeScalars.first!.value
                result.append(Stroke(key: nil, codePoint: cp, text: mods.contains(.shift) ? value.uppercased() : value, modifiers: mods, paste: false))
            } else { throw ShelloError.invalid("input_unrecognized_key") }
        }
        if result.count > 64 { throw ShelloError.invalid("input_up_to_64_keystrokes_are_supported") }
        return result
    }
}

/// Persisted toolbar layout, groups, custom buttons and gesture mappings (UserDefaults), same defaults as Android.
public final class InputPreferences {
    private let defaults: UserDefaults
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func defaultsFor(_ key: String) -> [String] {
        switch key {
        case "layout_toolbar": return ["ctrl", "key_ESCAPE", "key_TAB", "pad", "panel", "paste", "history", "chat", "keyboard"]
        case "layout_composer": return ["key_ESCAPE", "pad", "history", "shortcuts"]
        case "layout_zen": return ["ctrl", "key_ESCAPE", "key_TAB", "pad", "paste", "shortcuts", "keyboard", "minimize"]
        case "groups": return ["Favorites", "Tmux", "Claude", "Ctrl", "Codex", "Herdr"]
        default:
            guard key.hasPrefix("group_") else { return [] }
            let g = String(key.dropFirst(6)); let all = InputActions.groups[g] ?? []
            return Array(all.prefix(["Tmux", "Herdr", "Zellij"].contains(g) ? 4 : 8))
        }
    }
    public func list(_ key: String) -> [String] {
        guard let raw = defaults.string(forKey: "input_" + key), let data = raw.data(using: .utf8), let ids = try? JSONDecoder().decode([String].self, from: data) else { return defaultsFor(key) }
        var out = [String]()
        for id in ids where !out.contains(id) && (key == "groups" ? InputActions.groups[id] != nil : action(id) != nil) { out.append(id) }
        return out
    }
    public func save(_ key: String, _ ids: [String]) {
        var unique = [String](); for id in ids where !unique.contains(id) { unique.append(id) }
        if key == "layout_toolbar", !unique.contains("ctrl") { unique.append("ctrl") }
        defaults.set(String(decoding: (try? JSONEncoder().encode(unique)) ?? Data("[]".utf8), as: UTF8.self), forKey: "input_" + key)
    }
    public func reset(_ key: String) { defaults.removeObject(forKey: "input_" + key) }

    public struct Custom: Codable, Equatable { public var label: String; public var binding: String; public var enter: Bool }
    public func customs() -> [String: Custom] {
        guard let d = defaults.data(forKey: "input_customs") else { return [:] }
        return (try? JSONDecoder().decode([String: Custom].self, from: d)) ?? [:]
    }
    public func action(_ id: String) -> InputActions.Action? {
        if let c = customs()[id] { return InputActions.Action(id: id, label: c.label, icon: "keyboard", binding: c.binding, enter: c.enter) }
        guard let a = InputActions.catalog[id] else { return nil }
        for g in ["Tmux", "Herdr"] where id.hasPrefix("preset_\(g)_") && a.binding.hasPrefix("C-b") {
            return InputActions.Action(id: a.id, label: a.label, icon: a.icon, binding: prefix(g) + a.binding.dropFirst(3), enter: a.enter)
        }
        return a
    }
    @discardableResult
    public func put(id: String? = nil, label: String, binding: String, enter: Bool) throws -> String {
        _ = try InputActions.parse(binding)
        guard !label.trimmingCharacters(in: .whitespaces).isEmpty else { throw ShelloError.invalid("input_enter_a_button_name") }
        let key = id ?? "custom_" + UUID().uuidString
        var all = customs(); all[key] = Custom(label: label, binding: binding, enter: enter)
        defaults.set(try JSONEncoder().encode(all), forKey: "input_customs"); return key
    }
    public func removeCustom(_ id: String) {
        var all = customs(); all[id] = nil
        defaults.set(try? JSONEncoder().encode(all), forKey: "input_customs")
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("input_layout_") || key.hasPrefix("input_group_") {
            let k = String(key.dropFirst(6)); save(k, list(k).filter { $0 != id })
        }
    }
    public func prefix(_ group: String) -> String { defaults.string(forKey: "input_prefix_" + group) ?? "C-b" }
    public func setPrefix(_ group: String, _ value: String) { defaults.set(value, forKey: "input_prefix_" + group) }

    static let gestureDefaults: [String: String] = [
        "single_tap": "none", "triple_tap": "none", "double_tap": "paste", "pull_down": "hide_keyboard", "toolbar_down": "hide_keyboard", "toolbar_up": "minimize", "keyboard_tap": "keyboard",
        "keyboard_double": "key_ENTER", "keyboard_long": "dictation", "ctrl_double": "lock_ctrl", "ctrl_long": "shortcuts", "shortcut_double": "lock_shortcuts",
        "swipe_left": "mux_next", "swipe_right": "mux_previous", "two_left": "pane_next", "two_right": "pane_previous", "two_up": "session_next",
        "two_down": "session_previous", "corner_ul": "key_BACKSPACE", "corner_ur": "ctrl_c",
    ]
    public func mapping(_ key: String) -> String { defaults.string(forKey: "input_gesture_" + key) ?? Self.gestureDefaults[key] ?? "none" }
    public func setMapping(_ key: String, _ action: String) { defaults.set(action, forKey: "input_gesture_" + key) }
    public func flag(_ key: String, default value: Bool) -> Bool { defaults.object(forKey: "input_" + key) as? Bool ?? value }
    public func setFlag(_ key: String, _ value: Bool) { defaults.set(value, forKey: "input_" + key) }
}
