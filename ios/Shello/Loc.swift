import Foundation
import SwiftUI

/// App-language aware lookup of the shared Android-derived string catalog. Keys equal Android resource names.
enum AppLanguage {
    static let key = "language.selected"
    private static var defaults: UserDefaults {
        ProcessInfo.processInfo.arguments.contains("-SHELLO_UI_TEST") ? (UserDefaults(suiteName: "shello.uitest") ?? .standard) : .standard
    }
    static var choice: String { get { defaults.string(forKey: key) ?? "system" } set { defaults.set(newValue, forKey: key); cached = nil } }
    private static var cached: Bundle?
    static var bundle: Bundle {
        if let cached { return cached }
        var resolved = Bundle.main
        let code = choice == "system" ? (Bundle.preferredLocalizations(from: ["en", "zh-Hans"], forPreferences: Locale.preferredLanguages).first ?? "en") : choice
        if let path = Bundle.main.path(forResource: code, ofType: "lproj"), let b = Bundle(path: path) { resolved = b }
        cached = resolved; return resolved
    }
    static var locale: Locale { Locale(identifier: choice == "system" ? (Locale.preferredLanguages.first ?? "en") : choice) }
    static let options: [(code: String, name: String)] = [("system", ""), ("en", "English"), ("zh-Hans", "简体中文")]
}

func tr(_ key: String) -> String { AppLanguage.bundle.localizedString(forKey: key, value: key, table: nil) }
func tr(_ key: String, _ args: CVarArg...) -> String { String(format: tr(key), locale: AppLanguage.locale, arguments: args) }
func trPlural(_ key: String, _ n: Int) -> String { String.localizedStringWithFormat(AppLanguage.bundle.localizedString(forKey: key, value: key, table: nil), n) }

/// SwiftUI text that follows the in-app language choice.
struct T: View {
    let key: String; var args: [CVarArg] = []
    init(_ key: String, _ args: CVarArg...) { self.key = key; self.args = args }
    var body: some View { Text(args.isEmpty ? tr(key) : String(format: tr(key), locale: AppLanguage.locale, arguments: args)) }
}
