import SwiftUI
import UIKit

/// Port of Android `UiTheme`: dark ("shello"), black and light palettes. Terminal cells are never remapped.
struct Theme: Equatable {
    let id: String, light: Bool
    let canvas: Color, surface: Color, input: Color, text: Color, secondary: Color, accent: Color, onAccent: Color, border: Color, separator: Color, danger: Color, warning: Color
    /// Saturated green of the primary buttons, the add button and switches (Moshi / Android home).
    var vivid: Color { light ? accent : Theme.rgb(0x00f000) }

    static func rgb(_ v: UInt32) -> Color { Color(red: Double((v >> 16) & 255) / 255, green: Double((v >> 8) & 255) / 255, blue: Double(v & 255) / 255) }
    static let shello = Theme(id: "shello", light: false, canvas: rgb(0x000000), surface: rgb(0x131416), input: rgb(0x1a1b1d), text: rgb(0xe6e6e8), secondary: rgb(0x96999f),
                              accent: rgb(0x00d86c), onAccent: rgb(0x062519), border: rgb(0x2a2b2d), separator: rgb(0x1d1e20), danger: rgb(0xff7078), warning: rgb(0xffc33d))
    static let black = Theme(id: "black", light: false, canvas: rgb(0x000000), surface: rgb(0x0b0b0c), input: rgb(0x111315), text: rgb(0xe6e6e8), secondary: rgb(0x96999f),
                             accent: rgb(0x00d86c), onAccent: rgb(0x062519), border: rgb(0x222428), separator: rgb(0x1b1c1f), danger: rgb(0xff7078), warning: rgb(0xffc33d))
    static let lightTheme = Theme(id: "light", light: true, canvas: rgb(0xf6f7f9), surface: rgb(0xffffff), input: rgb(0xf1f3f5), text: rgb(0x20252b), secondary: rgb(0x626b75),
                                  accent: rgb(0x087442), onAccent: rgb(0xffffff), border: rgb(0xe5e9ee), separator: rgb(0xedf0f3), danger: rgb(0xb42332), warning: rgb(0x875400))
    static let all = [shello, black, lightTheme]
    static func named(_ id: String) -> Theme { all.first { $0.id == id } ?? .shello }
    var scheme: ColorScheme { light ? .light : .dark }
    /// Terminal sheet colour (the default VT background the app applies, so chrome and cells meet seamlessly).
    var terminalSheet: Color { light ? Theme.rgb(0xf5f5f5) : id == "black" ? Theme.rgb(0x000000) : Theme.rgb(0x1e1e1e) }
    var previewText: Color { light ? Theme.rgb(0x414a54) : Theme.rgb(0xd4d4d4) }
    var chrome: Color { light ? Theme.rgb(0xffffff) : Theme.rgb(0x000000) }
    var key: Color { light ? Theme.rgb(0xf0f2f5) : Theme.rgb(0x161719) }
    var uiSurface: UIColor { UIColor(surface) }
}

struct ThemeKey: EnvironmentKey { static let defaultValue = Theme.shello }
extension EnvironmentValues { var theme: Theme { get { self[ThemeKey.self] } set { self[ThemeKey.self] = newValue } } }

/// Persisted appearance settings; same keys and defaults as the Android `appearance` preferences.
final class AppSettings: ObservableObject {
    private let d: UserDefaults
    init(defaults: UserDefaults = .standard) { d = defaults; theme = d.string(forKey: "appearance.theme") ?? "shello" }
    @Published var theme: String { didSet { d.set(theme, forKey: "appearance.theme") } }
    var current: Theme { Theme.named(theme) }
    func int(_ k: String, _ def: Int) -> Int { d.object(forKey: "appearance." + k) as? Int ?? def }
    func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: "appearance." + k) as? Bool ?? def }
    func set(_ k: String, _ v: Any) { objectWillChange.send(); d.set(v, forKey: "appearance." + k) }
    var fontSize: Int { int("fontSize", 14) }
    var selection: Bool { bool("selection", true) }
    var guide: Bool { bool("guide", true) }
    var hookStatus: Bool { bool("hookStatus", true) }
    var draftDefault: Bool { bool("draftDefault", false) }
    var keepScreenOn: Bool { bool("keepScreenOn", false) }
    var haptics: Bool { bool("haptics", true) }
}
