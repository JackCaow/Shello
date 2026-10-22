import SwiftUI
import ShelloCore
import ShelloServices
import UserNotifications
import GameController

struct SettingsView: View {
    @Environment(\.theme) var theme
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            VStack(spacing: 0) {
            SettingsHeader(title: tr("settings"), back: { dismiss() }, id: "settings.close")
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    group(tr("app_preferences")) { row("appearance", tr("appearance_and_language"), "paintpalette", value: Theme.named(env.settings.theme).id == "shello" ? "Shello" : env.settings.theme == "black" ? tr("black") : tr("light")) }
                    group(tr("terminal")) {
                        row("font", tr("font_and_size"), "textformat.size", value: "\(env.settings.fontSize) pt"); Hairline()
                        row("advanced", tr("advanced"), "slider.horizontal.3")
                    }
                    group(tr("agents")) {
                        row("hook", "Hook", "link"); Hairline()
                        row("notifications", tr("notifications"), "bell"); Hairline()
                        row("draft", tr("text_draft"), "text.bubble")
                    }
                    group(tr("input")) {
                        if env.hardwareKeyboard.connected { row("hardware", tr("input_physical_keyboard"), "keyboard"); Hairline() }
                        row("toolbar", tr("toolbar"), "wrench.and.screwdriver"); Hairline()
                        row("shortcuts", tr("shortcuts"), "command"); Hairline()
                        row("gestures", tr("gestures"), "hand.draw")
                    }
                    group(tr("about")) {
                        Link(destination: URL(string: "https://github.com/JackCaow/Shello")!) {
                            HStack(spacing: 16) { Image(systemName: "info.circle").font(.system(size: 19)).frame(width: 28).foregroundColor(theme.secondary)
                                VStack(alignment: .leading, spacing: 2) { Text("Shello").appFont(size: 17).foregroundColor(theme.text)
                                    Text("\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""))").appFont(size: 14).foregroundColor(theme.secondary) }
                                Spacer() }.padding(.horizontal, 16).padding(.vertical, 10).frame(minHeight: 56)
                        }.accessibilityIdentifier("settings.about")
                    }
                }.padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 24).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
            }
            .background(theme.canvas.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }.themed(theme)
    }
    private func group<C: View>(_ title: String, @ViewBuilder _ content: @escaping () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) { SectionLabel(text: title); GroupCard { content() } }
    }
    private func row(_ page: String, _ title: String, _ icon: String, value: String? = nil) -> some View {
        NavigationLink(value: AppEnvironment.Route.settings(page)) {
            SettingRow(icon: icon, title: title, value: value, chevron: true)
        }.buttonStyle(.plain).accessibilityIdentifier("settings.\(page)")
    }
}


/// Settings destinations share the app stack, including entry from a home tip or Hook setup.
struct SettingsDestination: View {
    let page: String
    @ViewBuilder var body: some View {
        switch page {
        case "appearance": AppearanceSettings()
        case "font": FontSettings()
        case "advanced": AdvancedSettings()
        case "hook": HookSettings()
        case "notifications": NotificationSettings()
        case "draft": DraftSettings()
        case "hardware": HardwareSettings()
        case "toolbar": ToolbarSettings()
        case "shortcuts": ShortcutSettings()
        default: GestureSettings()
        }
    }
}
