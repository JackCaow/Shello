import Foundation
import ShelloCore

/// Labels and SF Symbols for toolbar actions.
enum InputText {
    static func label(for id: String, _ prefs: InputPreferences) -> String {
        let named: [String: String] = ["none": "input_none", "mux_next": "input_next_tmux_window", "mux_previous": "input_previous_tmux_window", "pane_next": "input_next_tmux_pane",
            "pane_previous": "input_previous_tmux_pane", "session_next": "input_next_session", "session_previous": "input_previous_session", "lock_ctrl": "input_lock_ctrl",
            "lock_shortcuts": "input_lock_shortcut_panel", "close_session": "input_close_current_session"]
        if let key = named[id] { return tr(key) }
        if let a = prefs.action(id) {
            if id.hasPrefix("custom_") || id.hasPrefix("preset_") || id.hasPrefix("key_") || id.hasPrefix("char_") || id.hasPrefix("ctrl_") { return a.label }
            let key = "input_" + (["pad": "d_pad", "panel": "sidebar", "history": "input_history", "chat": "text_input", "shortcuts": "shortcut_panel", "zen": "zen_ring", "select": "text_selection", "mouse": "mouse_selection", "drag_arrows": "drag_arrow_keys", "changes": "code_changes", "preview": "web_preview"][id] ?? id)
            let t = tr(key); return t == key ? a.label : t
        }
        return id
    }
    static func icon(for id: String) -> String? {
        switch id {
        case "pad": return "arrow.up.and.down.and.arrow.left.and.right"
        case "panel": return "sidebar.right"
        case "paste": return "doc.on.clipboard"
        case "history": return "clock.arrow.circlepath"
        case "chat": return "text.bubble"
        case "keyboard": return "keyboard"
        case "shortcuts": return "command"
        case "minimize": return "chevron.down"
        case "hide_keyboard": return "keyboard.chevron.compact.down"
        case "zen": return "circle.dashed"
        case "dictation": return "mic"
        case "changes": return "plusminus"
        case "preview": return "safari"
        default: return nil
        }
    }
}
