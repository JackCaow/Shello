import SwiftUI
import GameController
import ShelloCore
import ShelloServices
import UserNotifications

// MARK: shared building blocks (Android "settings row" roles: 56pt rows, 17 title, 14 secondary, inset hairlines)

/// Android-style page header: back arrow and a left-aligned 20pt title, fixed above the scrolling content.
struct SettingsHeader: View {
    @Environment(\.theme) var theme
    let title: String; let back: () -> Void; var id = "settings.back"
    var body: some View {
        ZStack {
            Text(title).appFont(size: 17, weight: .semibold).foregroundColor(theme.text).lineLimit(2).multilineTextAlignment(.center).padding(.horizontal, 64).padding(.vertical, 12)
            HStack { CircleButton(systemName: "chevron.left", id: id, label: tr("back"), action: back); Spacer() }
        }.padding(.horizontal, 12).frame(minHeight: 64)
    }
}

struct SettingsPage<C: View>: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    let title: String; @ViewBuilder var content: () -> C
    var body: some View {
        VStack(spacing: 0) {
            SettingsHeader(title: title) { dismiss() }
            ScrollView {
                VStack(alignment: .leading, spacing: 24) { content() }
                    .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 32).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
        }
        .background(theme.canvas.ignoresSafeArea())
        .navigationBarBackButtonHidden(true).toolbar(.hidden, for: .navigationBar)
        .interactiveBackNavigation()
    }
}

struct SettingGroup<C: View>: View {
    @Environment(\.theme) var theme
    var title: String? = nil; var footer: String? = nil
    @ViewBuilder var content: () -> C
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title { SectionLabel(text: title) }
            GroupCard { content() }
            if let footer { Text(footer).appFont(size: 14).lineSpacing(3).foregroundColor(theme.secondary).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true).padding(.horizontal, 4) }
        }
    }
}

struct SettingRow: View {
    @Environment(\.theme) var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    var icon: String? = nil; let title: String; var subtitle: String? = nil; var value: String? = nil; var chevron = false; var check = false
    var body: some View {
        HStack(spacing: 16) {
            if let icon { Image(systemName: icon).font(.system(size: 19)).frame(width: 28).foregroundColor(theme.secondary) }
            VStack(alignment: .leading, spacing: 3) {
                Text(title).appFont(size: 17).foregroundColor(theme.text).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                if let subtitle { Text(subtitle).appFont(size: 14).lineSpacing(2).foregroundColor(theme.secondary).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true) }
                if typeSize.isAccessibilitySize, let value { Text(value).appFont(size: 14).foregroundColor(theme.secondary) }
            }
            Spacer(minLength: 8)
            if !typeSize.isAccessibilitySize, let value { Text(value).appFont(size: 14).foregroundColor(theme.secondary) }
            if check { Image(systemName: "checkmark").foregroundColor(theme.accent) }
            if chevron { Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(theme.secondary) }
        }.padding(.horizontal, 16).padding(.vertical, 10).frame(minHeight: 56).contentShape(Rectangle())
    }
}

struct ToggleRow: View {
    @Environment(\.theme) var theme
    let title: String; var subtitle: String? = nil; @Binding var isOn: Bool; var id: String? = nil
    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).appFont(size: 17).foregroundColor(theme.text).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                if let subtitle { Text(subtitle).appFont(size: 14).lineSpacing(2).foregroundColor(theme.secondary).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true) }
            }
        }.toggleStyle(ShelloToggleStyle()).padding(.horizontal, 16).padding(.vertical, 8).frame(minHeight: 56).accessibilityIdentifier(id ?? title)
    }
}

// MARK: appearance & language

struct AppearanceSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @State private var language = AppLanguage.choice
    var body: some View {
        SettingsPage(title: tr("appearance_and_language")) {
            SettingGroup(title: tr("theme"), footer: tr("appearance_theme_description")) {
                ForEach(Array(Theme.all.enumerated()), id: \.element.id) { i, t in
                    if i > 0 { Hairline(inset: 16) }
                    Button { env.settings.theme = t.id } label: {
                        HStack(spacing: 16) {
                            ZStack { RoundedRectangle(cornerRadius: 8).fill(t.canvas); RoundedRectangle(cornerRadius: 4).fill(t.surface).frame(width: 26, height: 14).offset(y: 4)
                                Circle().fill(t.accent).frame(width: 6, height: 6).offset(x: -10, y: -8) }
                                .frame(width: 44, height: 32).overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border))
                            Text(t.id == "shello" ? "Shello" : t.id == "black" ? tr("black") : tr("light")).appFont(size: 17).foregroundColor(theme.text)
                            Spacer(); if env.settings.theme == t.id { Image(systemName: "checkmark").foregroundColor(theme.accent) }
                        }.padding(.horizontal, 16).frame(minHeight: 60).contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityIdentifier("theme.\(t.id)")
                }
            }
            SettingGroup(title: tr("app_language"), footer: tr("language_help")) {
                ForEach(Array(AppLanguage.options.enumerated()), id: \.element.code) { i, o in
                    if i > 0 { Hairline(inset: 16) }
                    Button { language = o.code; AppLanguage.choice = o.code; env.objectWillChange.send() } label: {
                        SettingRow(title: o.code == "system" ? tr("system_language") : o.name, check: language == o.code)
                    }.buttonStyle(.plain).accessibilityIdentifier("language.\(o.code)")
                }
            }
        }
    }
}

// MARK: font

struct FontSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    var body: some View {
        SettingsPage(title: tr("font_and_size")) {
            SettingGroup(title: tr("terminal_text"), footer: tr("chinese_and_emoji_font_fallback_supported_font_size_10_24_sp")) {
                HStack(spacing: 16) {
                    Image(systemName: "textformat.size").font(.system(size: 19)).frame(width: 28).foregroundColor(theme.secondary)
                    Text(tr("font_size")).appFont(size: 17).foregroundColor(theme.text)
                    Spacer()
                    StepperPill(value: "\(env.settings.fontSize)pt", minus: { env.settings.set("fontSize", max(10, env.settings.fontSize - 1)) }, plus: { env.settings.set("fontSize", min(24, env.settings.fontSize + 1)) }, idPrefix: "font")
                }.padding(.horizontal, 16).frame(minHeight: 60)
                Hairline()
                SettingRow(icon: "textformat", title: tr("font"), value: tr("system_monospace"))
            }
            SettingGroup(title: tr("input_live_preview")) {
                Text(tr("font_preview_sample")).font(.system(size: CGFloat(env.settings.fontSize), design: .monospaced)).lineSpacing(2).foregroundColor(.white.opacity(0.92))
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Color(white: 0.115)).accessibilityIdentifier("font.preview")
            }
        }
    }
}

// MARK: advanced / notifications / draft / hardware

struct AdvancedSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    var body: some View {
        SettingsPage(title: tr("advanced")) {
            SettingGroup(title: tr("home")) {
                ToggleRow(title: tr("show_hook_status"), subtitle: tr("show_status_dots_on_saved_connections"), isOn: Binding(get: { env.settings.hookStatus }, set: { env.settings.set("hookStatus", $0) }), id: "advanced.hookstatus")
                Hairline(inset: 16)
                ToggleRow(title: tr("show_feature_guide"), subtitle: tr("show_feature_tips_on_the_home_screen"), isOn: Binding(get: { env.settings.guide }, set: { env.settings.set("guide", $0) }), id: "advanced.guide")
            }
            SettingGroup(title: tr("background_activity"), footer: tr("when_networking_returns_or_the_app_reopens_shello_reconnects_to_t")) {
                Button { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } } label: {
                    SettingRow(icon: "gearshape", title: tr("battery_and_background_activity"), subtitle: tr("open_app_info"), chevron: true)
                }.buttonStyle(.plain).accessibilityIdentifier("advanced.appinfo")
            }
        }
    }
}

struct DraftSettings: View {
    @EnvironmentObject var env: AppEnvironment
    var body: some View {
        SettingsPage(title: tr("text_draft")) {
            SettingGroup {
                ToggleRow(title: tr("start_with_a_text_draft"), subtitle: tr("edit_before_sending_when_a_terminal_opens"), isOn: Binding(get: { env.settings.draftDefault }, set: { env.settings.set("draftDefault", $0) }), id: "draft.default")
            }
        }
    }
}

struct HardwareSettings: View {
    @EnvironmentObject var env: AppEnvironment
    var body: some View {
        SettingsPage(title: tr("input_physical_keyboard")) {
            SettingGroup {
                ToggleRow(title: tr("input_option_as_meta"), subtitle: tr("input_send_alt_option_as_the_terminal_meta_modifier"), isOn: Binding(get: { env.prefs.flag("option_as_meta", default: true) }, set: { env.prefs.setFlag("option_as_meta", $0); env.objectWillChange.send() }), id: "hardware.meta")
                Hairline(inset: 16)
                ToggleRow(title: tr("input_hide_toolbar_automatically"), subtitle: tr("input_hide_touch_controls_while_a_physical_keyboard_is_connected"), isOn: Binding(get: { env.prefs.flag("hide_with_hardware", default: false) }, set: { env.prefs.setFlag("hide_with_hardware", $0); env.objectWillChange.send() }), id: "hardware.hide_toolbar")
            }
        }
    }
}

struct NotificationSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @State private var allowed = false
    var body: some View {
        SettingsPage(title: tr("notifications")) {
            SettingGroup(footer: tr("receive_approval_requests_questions_and_completion_notifications")) {
                ToggleRow(title: tr("agent_event_notifications"), isOn: Binding(get: { UserDefaults.standard.object(forKey: "agent.notifications") as? Bool ?? true }, set: { UserDefaults.standard.set($0, forKey: "agent.notifications"); env.objectWillChange.send() }), id: "notifications.toggle")
            }
            SettingGroup(footer: tr("active_ssh_sessions_keep_fetching_events_in_the_background_if_no")) {
                Button { env.requestNotificationPermission(); refresh() } label: { SettingRow(icon: "bell", title: tr("request_notification_permission"), subtitle: allowed ? tr("enabled") : tr("notifications_off").trimmingCharacters(in: CharacterSet(charactersIn: " ·"))) }.buttonStyle(.plain)
                Hairline()
                Button { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } } label: { SettingRow(icon: "gearshape", title: tr("system_notification_settings"), chevron: true) }.buttonStyle(.plain)
            }
        }.onAppear(perform: refresh)
    }
    private func refresh() { UNUserNotificationCenter.current().getNotificationSettings { s in DispatchQueue.main.async { allowed = s.authorizationStatus == .authorized } } }
}

struct HookSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @State private var busy: String?
    @State private var confirm: (SSHProfile, Bool)?
    var body: some View {
        SettingsPage(title: "Hook") {
            SettingGroup(footer: tr("installation_and_sync_use_ssh_no_extra_ports_are_needed_installat")) {
                SettingRow(icon: "link", title: tr("install_claude_code_hook_on_your_ssh_host"))
            }
            if env.profiles.isEmpty { SettingGroup { SettingRow(title: tr("add_an_ssh_connection_first")) } }
            else {
                SettingGroup {
                    ForEach(Array(env.profiles.enumerated()), id: \.element.id) { i, p in
                        if i > 0 { Hairline(inset: 16) }
                        let on = env.hub.enabled(p.id)
                        HStack {
                            SettingRow(title: p.name, subtitle: on ? (env.hub.hostStates[p.id] == .connected ? tr("connected") : tr("waiting_to_sync")) : tr("not_installed"))
                            if busy == p.id { ProgressView().padding(.trailing, 16) }
                            else if on {
                                Button(tr("update")) { confirm = (p, true) }.buttonStyle(.bordered).accessibilityIdentifier("hook.update.\(p.name)")
                                Button(tr("uninstall"), role: .destructive) { confirm = (p, false) }.buttonStyle(.bordered).padding(.trailing, 12).accessibilityIdentifier("hook.uninstall.\(p.name)")
                            } else { Button(tr("install")) { confirm = (p, true) }.buttonStyle(.borderedProminent).padding(.trailing, 12).accessibilityIdentifier("hook.install.\(p.name)") }
                        }
                    }
                }
            }
        }
        .alert(confirm?.1 == true ? tr("install_hook_387") : tr("uninstall_hook"), isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } })) {
            Button(confirm?.1 == true ? tr("install") : tr("uninstall"), role: confirm?.1 == true ? nil : .destructive) {
                guard let (p, install) = confirm else { return }
                busy = p.id
                Task { let ok = await env.hub.configure(profile: p.id, install: install); busy = nil
                    env.showToast(ok ? (install ? tr("hook_installed_start_a_new_claude_code_session_to_use_it") : tr("hook_uninstalled")) : tr("operation_failed_check_ssh_python_3_and_remote_directory_permissi")) }
            }.accessibilityIdentifier("hook.confirm")
            Button(tr("cancel"), role: .cancel) {}
        } message: { Text(confirm?.1 == true ? tr("existing_settings_are_kept_start_a_new_claude_code_session_after") : tr("only_the_claude_code_hook_registered_by_this_app_will_be_removed")) }
    }
}

// MARK: toolbar

struct ToolbarSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @State private var zen = false
    var body: some View {
        let ids = zen ? env.prefs.list("layout_zen") : env.prefs.list("layout_toolbar")
        SettingsPage(title: tr("toolbar")) {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: tr("input_live_preview"))
                ZStack(alignment: .bottom) {
                    RoundedRectangle(cornerRadius: 24).fill(LinearGradient(colors: [Color(red: 0.05, green: 0.30, blue: 0.12), Color(red: 0.08, green: 0.22, blue: 0.24)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    if zen { Text(tr("input_zen_mode_double_tap_and_hold_the_terminal_then_drag_to_an_action_on_th")).appFont(size: 17).foregroundColor(.white.opacity(0.75)).multilineTextAlignment(.center).padding(24).frame(maxHeight: .infinity) }
                    else {
                        Text(tr("live_toolbar_preview")).appFont(size: 17).foregroundColor(.white.opacity(0.6)).frame(maxHeight: .infinity)
                        ToolbarStrip(ids: ids) { id in ToolKeyFace(id: id, prefs: env.prefs).accessibilityElement(children: .ignore).accessibilityLabel(InputText.label(for: id, env.prefs)).accessibilityIdentifier("preview.key.\(id)") }.padding(12)
                    }
                }.frame(height: 200).accessibilityIdentifier("toolbar.preview")
                HStack(spacing: 0) {
                    SegmentedChoice(options: [(tr("input_toolbar"), false, "toolbar.mode.toolbar"), (tr("input_zen_mode"), true, "toolbar.mode.zen")], selection: $zen, compact: true)
                    Spacer()
                }

            }
            SettingGroup {
                ToggleRow(title: tr("input_use_icons_for_modifiers"), isOn: Binding(get: { env.prefs.flag("modifier_icons", default: false) }, set: { env.prefs.setFlag("modifier_icons", $0); env.objectWillChange.send() }), id: "toolbar.modifier_icons")
            }
            SettingGroup(title: tr("input_layouts")) {
                NavigationLink { LayoutEditor(key: "layout_toolbar", title: tr("input_configure_toolbar"), pinned: true) } label: { SettingRow(icon: "wrench.and.screwdriver", title: tr("input_configure_toolbar"), chevron: true) }.buttonStyle(.plain).accessibilityIdentifier("toolbar.configure")
                Hairline()
                NavigationLink { LayoutEditor(key: "layout_zen", title: tr("input_configure_zen_ring"), pinned: false) } label: { SettingRow(icon: "wrench.and.screwdriver", title: tr("input_configure_zen_ring"), chevron: true) }.buttonStyle(.plain).accessibilityIdentifier("toolbar.configure.zen")
                Hairline()
                NavigationLink { LayoutEditor(key: "layout_composer", title: tr("input_configure_input_box"), pinned: false) } label: { SettingRow(icon: "text.bubble", title: tr("input_configure_input_box"), chevron: true) }.buttonStyle(.plain).accessibilityIdentifier("toolbar.configure.composer")
            }
        }
    }
}

/// Selected buttons (remove, drag to reorder) and the remaining actions to add, for one layout list.
/// Drop target of a reorderable row: moves the dragged item to this row's position.
struct RowDrop: DropDelegate {
    let move: (String) -> Void
    func validateDrop(info: DropInfo) -> Bool { info.hasItemsConforming(to: [.plainText]) }
    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }
    func performDrop(info: DropInfo) -> Bool {
        guard let provider = info.itemProviders(for: [.plainText]).first else { return false }
        provider.loadObject(ofClass: NSString.self) { object, _ in
            if let s = object as? String { DispatchQueue.main.async { move(s) } }
        }
        return true
    }
}

/// One reorderable row: remove button, title, optional subtitle and a drag handle (press, drag over another row, drop).
/// VoiceOver gets "move earlier / later" actions instead of a drag.
struct ReorderRow: View {
    @Environment(\.theme) var theme
    let title: String; var subtitle: String? = nil; let id: String; let item: String
    var canRemove = true; var canUp = true; var canDown = true
    let remove: () -> Void; let up: () -> Void; let down: () -> Void; let moveHere: (String) -> Void
    var body: some View {
        HStack(spacing: 4) {
            Button(action: remove) { Image(systemName: "minus").foregroundColor(theme.danger).frame(width: 44, height: 48) }.buttonStyle(.plain).disabled(!canRemove).opacity(canRemove ? 1 : 0.3)
                .accessibilityLabel(tr("input_remove") + title).accessibilityIdentifier("remove.\(id)")
            VStack(alignment: .leading, spacing: 3) {
                Text(title).appFont(size: 17).foregroundColor(theme.text).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                if let subtitle, !subtitle.isEmpty { Text(subtitle).appFont(size: 14).foregroundColor(theme.secondary).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 4)
            Image(systemName: "line.3.horizontal").font(.system(size: 18)).foregroundColor(theme.secondary).frame(width: 48, height: 48).contentShape(Rectangle())
                .onDrag { NSItemProvider(object: item as NSString) }
                .accessibilityLabel(tr("input_drag_to_reorder") + title).accessibilityIdentifier("handle.\(id)")
        }
        .padding(.horizontal, 8).frame(minHeight: 56).contentShape(Rectangle())
        .onDrop(of: [.plainText], delegate: RowDrop(move: moveHere))
        .accessibilityAction(named: tr("move_earlier")) { if canUp { up() } }
        .accessibilityAction(named: tr("move_later")) { if canDown { down() } }
    }
}

struct LayoutEditor: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    let key: String; let title: String; let pinned: Bool
    @State private var ids: [String] = []
    private var fixed: [String] { pinned ? ["chat", "keyboard"] : [] }
    private var available: [String] {
        (InputActions.catalogOrder.filter { !$0.hasPrefix("preset_") && !fixed.contains($0) } + env.prefs.customs().keys.sorted()).filter { !ids.contains($0) }
    }
    var body: some View {
        SettingsPage(title: title) {
            SettingGroup(title: tr("input_selected_buttons_drag_handles_to_reorder"), footer: pinned ? tr("input_toolbar_pinned_hint") : nil) {
                ForEach(Array(ids.enumerated()), id: \.element) { i, id in
                    if i > 0 { Hairline(inset: 16) }
                    ReorderRow(title: InputText.label(for: id, env.prefs), id: "layout." + id, item: id, canRemove: !(id == "ctrl" && key == "layout_toolbar"), canUp: i > 0, canDown: i < ids.count - 1,
                               remove: { remove(id) }, up: { ids.swapAt(i, i - 1); save() }, down: { ids.swapAt(i, i + 1); save() },
                               moveHere: { dragged in guard let from = ids.firstIndex(of: dragged), from != i else { return }; ids.move(fromOffsets: IndexSet(integer: from), toOffset: from < i ? i + 1 : i); save() })
                }
            }
            SettingGroup(title: tr("input_add")) {
                ForEach(Array(available.enumerated()), id: \.element) { i, id in
                    if i > 0 { Hairline(inset: 16) }
                    Button { ids.append(id); save() } label: { SettingRow(title: InputText.label(for: id, env.prefs), value: nil).overlay(alignment: .trailing) { Image(systemName: "plus").foregroundColor(theme.accent).padding(.trailing, 16) } }
                        .buttonStyle(.plain).accessibilityIdentifier("layout.add.\(id)")
                }
            }
            SecondaryButton(title: tr("input_restore_defaults")) { env.prefs.reset(key); load(); env.objectWillChange.send() }.accessibilityIdentifier("layout.reset")
        }.onAppear(perform: load)
    }
    private func load() { ids = env.prefs.list(key).filter { !fixed.contains($0) } }
    private func remove(_ id: String) { ids.removeAll { $0 == id }; save() }
    private func save() { env.prefs.save(key, ids + fixed); env.objectWillChange.send() }
}

// MARK: shortcuts

struct ShortcutSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @State private var enabled: [String] = []
    private var available: [String] { InputActions.groupOrder.filter { !enabled.contains($0) } }
    var body: some View {
        SettingsPage(title: tr("shortcuts")) {
            SettingGroup(title: tr("input_enabled_groups_drag_to_reorder")) {
                ForEach(Array(enabled.enumerated()), id: \.element) { i, g in
                    if i > 0 { Hairline(inset: 16) }
                    HStack(spacing: 0) {
                        ReorderRow(title: g == "Favorites" ? tr("input_favorites") : g, subtitle: "\(env.prefs.list("group_" + g).count)" + tr("input_shortcuts_2"), id: "group." + g, item: g,
                                   canUp: i > 0, canDown: i < enabled.count - 1, remove: { enabled.removeAll { $0 == g }; save() },
                                   up: { enabled.swapAt(i, i - 1); save() }, down: { enabled.swapAt(i, i + 1); save() },
                                   moveHere: { dragged in guard let from = enabled.firstIndex(of: dragged), from != i else { return }; enabled.move(fromOffsets: IndexSet(integer: from), toOffset: from < i ? i + 1 : i); save() })
                        NavigationLink { GroupEditor(group: g) } label: { Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(theme.secondary).frame(width: 40, height: 48) }
                            .buttonStyle(.plain).accessibilityIdentifier("shortcuts.group.\(g)").accessibilityLabel(g)
                    }
                }
            }
            if !available.isEmpty {
                SettingGroup(title: tr("input_available_groups")) {
                    ForEach(Array(available.enumerated()), id: \.element) { i, g in
                        if i > 0 { Hairline(inset: 16) }
                        Button { enabled.append(g); save() } label: { SettingRow(icon: "plus", title: g, chevron: true) }.buttonStyle(.plain).accessibilityIdentifier("shortcuts.add.\(g)")
                    }
                }
            }
            SettingGroup(title: tr("input_panel")) {
                ToggleRow(title: tr("input_show_group_icons_only"), isOn: Binding(get: { env.prefs.flag("group_icons_only", default: false) }, set: { env.prefs.setFlag("group_icons_only", $0); env.objectWillChange.send() }), id: "shortcuts.icons_only")
                Hairline(inset: 16)
                NavigationLink { CustomShortcuts() } label: { SettingRow(icon: "keyboard", title: tr("input_custom_shortcut"), chevron: true) }.buttonStyle(.plain).accessibilityIdentifier("shortcuts.custom")
            }
            SecondaryButton(title: tr("input_restore_default_groups")) { env.prefs.reset("groups"); load() }
        }.onAppear(perform: load)
    }
    private func load() { enabled = env.prefs.list("groups") }
    private func save() { env.prefs.save("groups", enabled); env.objectWillChange.send() }
}

struct GroupEditor: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    let group: String
    @State private var ids: [String] = []
    @State private var prefix = ""
    private var candidates: [String] {
        let own = InputActions.groups[group] ?? []
        return (group == "Favorites" ? InputActions.catalogOrder.filter { !$0.hasPrefix("preset_") } + env.prefs.customs().keys.sorted() : own + env.prefs.customs().keys.sorted()).filter { !ids.contains($0) }
    }
    var body: some View {
        SettingsPage(title: group == "Favorites" ? tr("input_favorites") : group) {
            if group == "Tmux" || group == "Herdr" {
                SettingGroup(title: tr("input_prefix_key"), footer: tr("input_prefix_single_key")) {
                    TextField("C-b", text: $prefix).textInputAutocapitalization(.never).autocorrectionDisabled().padding(.horizontal, 16).frame(minHeight: 56)
                        .onSubmit { savePrefix() }.accessibilityIdentifier("shortcuts.prefix")
                }
            }
            SettingGroup(title: tr("input_selected_buttons_drag_handles_to_reorder")) {
                if ids.isEmpty { SettingRow(title: tr("input_tap_to_add_shortcuts")) }
                ForEach(Array(ids.enumerated()), id: \.element) { i, id in
                    if i > 0 { Hairline(inset: 16) }
                    ReorderRow(title: InputText.label(for: id, env.prefs), subtitle: env.prefs.action(id)?.binding, id: "item." + id, item: id, canUp: i > 0, canDown: i < ids.count - 1,
                               remove: { ids.removeAll { $0 == id }; save() }, up: { ids.swapAt(i, i - 1); save() }, down: { ids.swapAt(i, i + 1); save() },
                               moveHere: { dragged in guard let from = ids.firstIndex(of: dragged), from != i else { return }; ids.move(fromOffsets: IndexSet(integer: from), toOffset: from < i ? i + 1 : i); save() })
                }
            }
            SettingGroup(title: tr("input_add")) {
                ForEach(Array(candidates.enumerated()), id: \.element) { i, id in
                    if i > 0 { Hairline(inset: 16) }
                    Button { ids.append(id); save() } label: { SettingRow(title: InputText.label(for: id, env.prefs), subtitle: env.prefs.action(id)?.binding).overlay(alignment: .trailing) { Image(systemName: "plus").foregroundColor(theme.accent).padding(.trailing, 16) } }.buttonStyle(.plain)
                }
            }
        }.onAppear { ids = env.prefs.list("group_" + group); prefix = env.prefs.prefix(group) }.onDisappear { savePrefix() }
    }
    private func save() { env.prefs.save("group_" + group, ids); env.objectWillChange.send() }
    private func savePrefix() { if group == "Tmux" || group == "Herdr", !prefix.isEmpty, (try? InputActions.parse(prefix)) != nil { env.prefs.setPrefix(group, prefix) } }
}

struct CustomShortcuts: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @State private var label = ""
    @State private var binding = ""
    @State private var enter = false
    @State private var error: String?
    var body: some View {
        SettingsPage(title: tr("input_custom_shortcut")) {
            SettingGroup(title: tr("input_new_shortcut"), footer: tr("input_use_the_text_prefix_for_text")) {
                TextField(tr("name"), text: $label).padding(.horizontal, 16).frame(minHeight: 56).accessibilityIdentifier("shortcuts.label")
                Hairline(inset: 16)
                TextField("C-b, c  /  text:ls", text: $binding).textInputAutocapitalization(.never).autocorrectionDisabled().padding(.horizontal, 16).frame(minHeight: 56).accessibilityIdentifier("shortcuts.binding")
                Hairline(inset: 16)
                ToggleRow(title: tr("enter"), isOn: $enter)
            }
            if let error { Text(error).foregroundColor(theme.danger).font(.footnote) }
            PrimaryButton(title: tr("input_add_to_favorites")) {
                do { let id = try env.prefs.put(label: label, binding: binding, enter: enter); var g = env.prefs.list("group_Favorites"); g.append(id); env.prefs.save("group_Favorites", g); error = nil; label = ""; binding = ""; env.objectWillChange.send() }
                catch let e { error = tr(e.localizedDescription) }
            }.accessibilityIdentifier("shortcuts.add")
            let customs = env.prefs.customs().sorted { $0.key < $1.key }
            if !customs.isEmpty {
                SettingGroup {
                    ForEach(Array(customs.enumerated()), id: \.element.key) { i, item in
                        if i > 0 { Hairline(inset: 16) }
                        HStack { SettingRow(title: item.value.label, subtitle: item.value.binding)
                            Button { env.prefs.removeCustom(item.key); env.objectWillChange.send() } label: { Image(systemName: "trash").foregroundColor(theme.danger).frame(width: 48, height: 48) }.buttonStyle(.plain).accessibilityLabel(tr("input_delete_shortcut")) }
                    }
                }
            }
        }
    }
}

// MARK: gestures

struct GestureSettings: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    static let terminal: [(String, String)] = [("single_tap", "input_single_tap"), ("double_tap", "input_double_tap"), ("triple_tap", "input_triple_tap"), ("pull_down", "input_pull_down"),
        ("swipe_left", "input_swipe_left"), ("swipe_right", "input_swipe_right"), ("two_left", "input_two_finger_swipe_left"), ("two_right", "input_two_finger_swipe_right"),
        ("two_up", "input_two_finger_swipe_up"), ("two_down", "input_two_finger_swipe_down")]
    static let controls: [(String, String)] = [("toolbar_up", "input_swipe_toolbar_up"), ("toolbar_down", "input_swipe_toolbar_down"), ("keyboard_tap", "input_keyboard_single_tap"),
        ("keyboard_double", "input_keyboard_double_tap"), ("keyboard_long", "input_keyboard_long_press"), ("ctrl_double", "input_ctrl_double_tap"), ("shortcut_double", "input_shortcuts_double_tap")]
    static let corners: [(String, String)] = [("corner_ul", "input_d_pad_corners"), ("corner_ur", "input_d_pad_corners")]
    var body: some View {
        SettingsPage(title: tr("gestures")) {
            SettingGroup(title: tr("terminal")) {
                ToggleRow(title: tr("input_hold_to_select"), isOn: Binding(get: { env.settings.selection }, set: { env.settings.set("selection", $0); env.objectWillChange.send() }), id: "gestures.select")
                ForEach(Self.terminal, id: \.0) { k, t in Hairline(); gestureRow(k, tr(t)) }
            }
            SettingGroup(title: tr("input_toolbar")) { ForEach(Array(Self.controls.enumerated()), id: \.element.0) { i, item in if i > 0 { Hairline() }; gestureRow(item.0, tr(item.1)) } }
            SettingGroup(title: tr("direction_pad")) {
                gestureRow("corner_ul", tr("input_d_pad_corners") + " ↖"); Hairline(); gestureRow("corner_ur", tr("input_d_pad_corners") + " ↗")
            }
        }
    }
    private func gestureRow(_ key: String, _ title: String) -> some View {
        NavigationLink { ActionPicker(key: key, title: title) } label: {
            SettingRow(icon: "hand.point.up.left", title: title, value: InputText.label(for: env.prefs.mapping(key), env.prefs), chevron: true)
        }.buttonStyle(.plain).accessibilityIdentifier("gesture.\(key)")
    }
}

struct ActionPicker: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    let key: String; let title: String
    static let extra = ["none", "mux_next", "mux_previous", "pane_next", "pane_previous", "session_next", "session_previous", "lock_ctrl", "lock_shortcuts", "close_session"]
    var choices: [String] { Self.extra + InputActions.catalogOrder.filter { !$0.hasPrefix("preset_") && $0 != "none" } + env.prefs.customs().keys.sorted() }
    var body: some View {
        SettingsPage(title: title) {
            SettingGroup(title: tr("input_choose_action")) {
                ForEach(Array(choices.enumerated()), id: \.element) { i, id in
                    if i > 0 { Hairline(inset: 16) }
                    Button { env.prefs.setMapping(key, id); env.objectWillChange.send(); dismiss() } label: { SettingRow(title: InputText.label(for: id, env.prefs), check: env.prefs.mapping(key) == id) }
                        .buttonStyle(.plain).accessibilityIdentifier("action.\(id)")
                }
            }
        }
    }
}
