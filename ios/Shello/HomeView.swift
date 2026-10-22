import SwiftUI
import ShelloCore
import ShelloServices

struct HomeView: View {
    @EnvironmentObject var env: AppEnvironment
    @EnvironmentObject var sessions: SessionManager
    @Environment(\.theme) var theme
    @State private var sheet: HomeSheet?
    @State private var deleting: SSHProfile?
    @State private var reordering = false
    @ScaledMetric(relativeTo: .body) private var tipWidth = 120.0

    enum HomeSheet: Identifiable {
        case choose, form(SSHProfile?, prefill: ConnectionFormView.Prefill?), pair, discover, picker(SSHProfile)
        var id: String {
            switch self {
            case .form(let p, _): return "form-\(p?.id ?? "new")"
            case .choose: return "choose"; case .pair: return "pair"; case .discover: return "discover"; case .picker(let p): return "picker-\(p.id)"
            }
        }
    }

    var body: some View {
        Group {
            ZStack(alignment: .bottomTrailing) {
                HomeBackdrop()
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        Spacer()
                        RoundIconButton(systemName: "tray") { env.route = .agent }
                            .overlay(alignment: .topTrailing) { if env.hub.pending() > 0 { Circle().fill(theme.warning).frame(width: 10, height: 10).offset(x: -6, y: 6) } }
                            .accessibilityIdentifier("home.agent").accessibilityLabel(tr("agent_inbox"))
                        RoundIconButton(systemName: "gearshape") { env.route = .settings(nil) }
                            .accessibilityIdentifier("home.settings").accessibilityLabel(tr("settings"))
                    }.padding(.horizontal, 16).frame(height: 56)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 24) {
                            sessionsSection
                            connectionsSection
                            if env.settings.guide { discover }
                        }.padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 120).frame(maxWidth: 640).frame(maxWidth: .infinity)
                    }
                }
                fab
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .sheet(item: $sheet) { s in
            Group { switch s {
            case .choose: AddChoiceSheet { next in sheet = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { sheet = next } }.presentationDetents([.height(560), .large])
            case .form(let p, let prefill): ConnectionFormView(editing: p, prefill: prefill) { saved in sheet = nil; if p == nil { DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { sheet = .picker(saved) } } }
            case .pair: PairingView { saved in sheet = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { sheet = .picker(saved) } }
            case .discover: DiscoveryView { host in
                sheet = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { sheet = .form(nil, prefill: .init(host: host.address, name: host.name)) } }
            case .picker(let p): TmuxPickerView(profile: p) { sheet = nil }
            } }.sheetStyle(theme)
        }
        .alert(tr("delete_connection_182"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { p in
            Button(tr("delete"), role: .destructive) { env.delete(p) }
            Button(tr("cancel"), role: .cancel) {}
        } message: { p in Text(tr("delete_connection_body", p.name)) }
        .onAppear { sessions.tabs.forEach { $0.updatePreview() } }
        .onChange(of: env.route) { route in if route == nil { sessions.tabs.forEach { $0.updatePreview() } } }
        .onReceive(NotificationCenter.default.publisher(for: .shelloOpenPair)) { _ in sheet = .pair }
    }

    private var fab: some View {
        Button { sheet = .choose } label: {
            Image(systemName: "plus").font(.system(size: 30, weight: .regular)).foregroundColor(theme.light ? theme.onAccent : .black)
                .frame(width: 72, height: 72).background(theme.vivid, in: Circle()).shadow(color: theme.vivid.opacity(0.45), radius: 18, y: 4)
        }
        .padding(.trailing, 24).padding(.bottom, 28)
        .accessibilityIdentifier("home.add").accessibilityLabel(tr("new_connection"))
    }

    // MARK: sections

    private var sessionsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                SectionLabel(text: tr("sessions"))
                Spacer()
                if reordering { Button(tr("done")) { reordering = false }.frame(minHeight: 44).accessibilityIdentifier("home.reorder.done") }
            }
            if sessions.tabs.isEmpty {
                DashedCard {
                    VStack(spacing: 10) {
                        IconTile(systemName: "terminal", size: 60)
                        T("no_active_sessions").appFont(size: 17, weight: .medium).foregroundColor(theme.text).multilineTextAlignment(.center)
                        HStack { T("tap_plus_to_add_connection").appFont(size: 14).foregroundColor(theme.secondary); Spacer(); Image(systemName: "arrow.down.right").font(.system(size: 11)).foregroundColor(theme.vivid) }
                    }.frame(maxWidth: .infinity)
                }.frame(maxWidth: 200).accessibilityIdentifier("home.sessions.empty")
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 14, alignment: .top)], spacing: 18) {
                    ForEach(sessions.tabs) { tab in
                        SessionTile(tab: tab, profile: env.profile(tab.profileID), selected: reordering && tab.id == sessions.selectedID) { sessions.select(tab.id); env.route = .terminal }
                            .accessibilityIdentifier("home.session.\(tab.id)")
                            .contextMenu { if !reordering { SessionMenuActions(tab: tab) { reordering = true } } }
                            .modifier(SessionReorderModifier(tab: tab, enabled: reordering))
                    }
                }
                if reordering { T("long_press_and_drag_sessions_to_reorder").font(.footnote).foregroundColor(theme.secondary) }
            }
        }
        .onChange(of: sessions.tabs.isEmpty) { empty in if empty { reordering = false } }
    }

    private var connectionsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionLabel(text: tr("connections"))
            if env.profiles.isEmpty {
                DashedCard {
                    HStack(spacing: 16) {
                        IconTile(systemName: "server.rack", size: 60)
                        VStack(alignment: .leading, spacing: 6) {
                            T("no_saved_connections").appFont(size: 17, weight: .medium).foregroundColor(theme.text)
                            HStack { T("tap_plus_to_add_connection").appFont(size: 14).foregroundColor(theme.secondary); Spacer(); Image(systemName: "arrow.down.right").font(.system(size: 11)).foregroundColor(theme.vivid) }
                        }
                    }
                }.accessibilityIdentifier("home.connections.empty")
            } else {
                ForEach(env.profiles) { p in
                    Button { sheet = .picker(p) } label: {
                        HStack(spacing: 14) {
                            IconTile(systemName: "server.rack", size: 48)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(p.name).appFont(size: 17, weight: .medium).foregroundColor(theme.text)
                                Text("\(p.user)@\(p.host)\(p.port == 22 ? "" : ":\(p.port)")").appFont(size: 14).foregroundColor(theme.secondary)
                            }
                            Spacer()
                            if env.settings.hookStatus { hookDot(p) }
                            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(theme.secondary)
                        }.padding(14).frame(minHeight: 72).background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("profile.\(p.name)")
                    .contextMenu {
                        Button { sheet = .form(p, prefill: nil) } label: { Label(tr("edit"), systemImage: "pencil") }
                        Button(role: .destructive) { deleting = p } label: { Label(tr("delete"), systemImage: "trash") }
                    }
                }
            }
        }
    }

    private static let tips: [(key: String, icon: String)] = [("qr_pairing", "qrcode"), ("remote_sessions", "server.rack"), ("inbox_and_approvals", "tray"), ("text_draft", "text.bubble"), ("toolbar", "wrench.and.screwdriver"), ("shortcuts", "keyboard")]
    @AppStorage("guide.visited") private var visitedRaw = ""
    private var visited: Set<String> { Set(visitedRaw.split(separator: ",").map(String.init)) }
    @ViewBuilder private func hookDot(_ p: SSHProfile) -> some View {
        let on = env.hub.enabled(p.id)
        Circle().fill(env.hub.pending(profile: p.id) > 0 ? theme.warning : on && env.hub.hostStates[p.id] == .connected ? theme.vivid : theme.secondary.opacity(0.6))
            .frame(width: 8, height: 8).accessibilityLabel(on ? "Hook" : tr("hook_not_installed"))
    }

    private var discover: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { SectionLabel(text: tr("discover_shello")); Spacer(); Text("\(visited.count)/\(Self.tips.count)").appFont(size: 15, weight: .medium).foregroundColor(theme.vivid) }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(Self.tips, id: \.key) { tip in
                        Button {
                            var v = visited; v.insert(tip.key); visitedRaw = v.sorted().joined(separator: ",")
                            switch tip.key {
                            case "qr_pairing": sheet = .pair
                            case "remote_sessions": sheet = .form(nil, prefill: nil)
                            case "inbox_and_approvals": env.route = .agent
                            case "text_draft": env.route = .settings("draft")
                            case "toolbar": env.route = .settings("toolbar")
                            default: env.route = .settings("shortcuts")
                            }
                        } label: {
                            VStack(spacing: 10) {
                                Image(systemName: tip.icon).font(.system(size: 24)).foregroundColor(theme.vivid)
                                Text(tr(tip.key)).appFont(size: 17).foregroundColor(theme.secondary).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.9)
                            }.padding(.vertical, 16).frame(width: tipWidth).frame(minHeight: 104).background(Color(white: theme.light ? 0.94 : 0.105), in: RoundedRectangle(cornerRadius: 18))
                        }.buttonStyle(.plain).accessibilityIdentifier("tip.\(tip.key)")
                    }
                }.padding(10)
            }.background(theme.surface, in: RoundedRectangle(cornerRadius: 22))
            Button(tr("got_it")) { env.settings.set("guide", false) }.appFont(size: 15, weight: .medium).foregroundColor(theme.vivid)
                .frame(minHeight: 48).padding(.horizontal, 4).accessibilityIdentifier("guide.dismiss")
        }
    }
}

struct SessionTile: View {
    @Environment(\.theme) var theme
    @ObservedObject var tab: TerminalTab
    let profile: SSHProfile?
    var selected = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                ZStack(alignment: .topLeading) {
                    RoundedRectangle(cornerRadius: 22).fill(theme.terminalSheet)
                    Text(tab.previewLines.joined(separator: "\n")).font(.system(size: 8, design: .monospaced)).foregroundColor(theme.previewText).lineLimit(12).padding(10).padding(.top, 32)
                    HStack { Text(profile?.name ?? "?").appFont(size: 15, weight: .medium).foregroundColor(theme.text).lineLimit(1); Spacer()
                        Text(chip).appFont(size: 11, weight: .medium).foregroundColor(chipColor).padding(.horizontal, 8).padding(.vertical, 3).background(chipColor.opacity(0.18), in: Capsule()) }.padding(10)
                }.aspectRatio(1, contentMode: .fit)
                    .overlay(RoundedRectangle(cornerRadius: 22).stroke(selected ? theme.accent : Color.clear, lineWidth: 1.5))
                Text(tab.sessionName).appFont(size: 17).foregroundColor(theme.text).lineLimit(1)
                Text("›_ \(profile?.host ?? "")").appFont(size: 15, design: .monospaced).foregroundColor(theme.accent).lineLimit(1)
            }
        }.buttonStyle(.plain)
    }
    private var chip: String { switch tab.state { case .connected: return "SSH"; case .connecting: return "…"; case .failed: return "!"; default: return "SSH" } }
    private var chipColor: Color { switch tab.state { case .connected: return Color(red: 0.35, green: 0.62, blue: 1); case .failed: return theme.danger; default: return theme.warning } }
}

extension Notification.Name { static let shelloOpenPair = Notification.Name("shello.open.pair") }

/// "+" sheet: two rich choices (QR pairing recommended, own SSH) and a LAN discovery link, like Moshi's half-height sheet.
struct AddChoiceSheet: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    let choose: (HomeView.HomeSheet) -> Void
    var body: some View {
        ScrollView { VStack(spacing: 14) {
            card(label: tr("quickest"), title: tr("pair_with_a_qr_code"), text: tr("run_one_command_on_mac_or_linux_and_scan_its_qr_code_shello_insta"), meta: tr("recommended_about_1_minute"), icon: "qrcode", featured: true, id: "home.add.pair") { choose(.pair) }
            card(label: tr("know_ssh"), title: tr("use_your_own_ssh"), text: tr("enter_a_host_username_and_key_or_password_keep_remote_sessions_wi"), meta: tr("about_3_minutes_ssh_credentials_needed"), icon: "server.rack", featured: false, id: "home.add.ssh") { choose(.form(nil, prefill: nil)) }
            Button { choose(.discover) } label: {
                HStack(spacing: 10) { Image(systemName: "dot.radiowaves.left.and.right"); Text(tr("discover_ssh_servers_on_your_network")).appFont(size: 16) }.foregroundColor(theme.secondary).frame(maxWidth: .infinity, minHeight: 52)
            }.accessibilityIdentifier("home.add.discover")
            Spacer(minLength: 0)
        }.padding(.horizontal, 16).padding(.top, 28).padding(.bottom, 24) }.frame(maxWidth: .infinity, maxHeight: .infinity).background(theme.canvas.ignoresSafeArea()).sheetStyle(theme).themed(theme)
    }
    private func card(label: String, title: String, text: String, meta: String, icon: String, featured: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 16) {
                Image(systemName: icon).font(.system(size: 26)).foregroundColor(theme.vivid).frame(width: 68, height: 68).background(theme.vivid.opacity(0.13), in: RoundedRectangle(cornerRadius: 20))
                VStack(alignment: .leading, spacing: 6) {
                    Text(label).appFont(size: 14, weight: .medium).foregroundColor(theme.vivid)
                    Text(title).appFont(size: 22, weight: .semibold).foregroundColor(theme.text).multilineTextAlignment(.leading)
                    Text(text).appFont(size: 16).lineSpacing(3).foregroundColor(theme.secondary).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                    Text(meta).appFont(size: 14).foregroundColor(theme.secondary.opacity(0.9)).padding(.top, 2)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 15, weight: .semibold)).foregroundColor(featured ? theme.vivid : theme.secondary).padding(.top, 28)
            }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
                .background(featured ? theme.vivid.opacity(0.10) : theme.surface, in: RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(.plain).accessibilityIdentifier(id)
    }
}
