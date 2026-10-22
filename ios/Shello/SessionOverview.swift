import SwiftUI
import ShelloServices
import UniformTypeIdentifiers

struct SessionOverview: View {
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var env: AppEnvironment
    @EnvironmentObject var sessions: SessionManager
    @ObservedObject var controller: TerminalController
    @State private var reordering = false
    let refresh = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ScrollView {
                if sessions.tabs.isEmpty { T("no_active_sessions").foregroundColor(theme.secondary).padding(40) }
                else {
                    if reordering { T("long_press_and_drag_sessions_to_reorder").font(.footnote).foregroundColor(theme.secondary).padding(.horizontal) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16, alignment: .top)], spacing: 12) {
                        ForEach(Array(sessions.tabs.enumerated()), id: \.element.id) { index, tab in card(tab, index) }
                    }.padding(16)
                }
            }
            .background(theme.canvas.ignoresSafeArea())
            .chrome(title: tr("sessions"), closeID: "overview.done", trailing: {
                Menu {
                    Button(tr("reorder")) { reordering.toggle() }
                    Button(tr("disconnect_all")) { for t in sessions.tabs { sessions.disconnect(t) } }
                    Button(tr("close_all"), role: .destructive) { sessions.closeAll(); dismiss(); env.route = nil }.accessibilityIdentifier("overview.closeall")
                } label: { Image(systemName: "ellipsis").font(.system(size: 18)).foregroundColor(theme.text).frame(width: 48, height: 48) }.accessibilityIdentifier("overview.menu")
            }) { dismiss() }
            .toolbar(.hidden, for: .navigationBar)
            .onReceive(refresh) { _ in for t in sessions.tabs { t.updatePreview() } }
            .onAppear { for t in sessions.tabs { t.updatePreview() } }
        }.themed(theme)
    }

    private func card(_ tab: TerminalTab, _ index: Int) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { sessions.select(tab.id); dismiss() } label: {
                ZStack(alignment: .topLeading) {
                    RoundedRectangle(cornerRadius: 24).fill(theme.terminalSheet)
                    Text(tab.previewLines.isEmpty ? tr("no_preview_yet") : tab.previewLines.joined(separator: "\n"))
                        .appFont(size: 7, design: .monospaced).foregroundColor(theme.previewText).lineLimit(14).padding(12).padding(.top, 36)
                    HStack {
                        Text(env.profile(tab.profileID)?.name ?? "?").appFont(size: 16, weight: .medium).foregroundColor(theme.text).lineLimit(1)
                        Spacer()
                        Text(tab.isConnected ? "SSH" : "···").appFont(size: 12, weight: .medium).foregroundColor(Color(red: 0.4, green: 0.65, blue: 1))
                            .padding(.horizontal, 10).padding(.vertical, 4).background(Color(red: 0.4, green: 0.65, blue: 1).opacity(0.18), in: Capsule())
                    }.padding(12)
                }.aspectRatio(1, contentMode: .fit)
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(tab.id == sessions.selectedID ? theme.accent : Color.clear, lineWidth: 1.5))
            }.buttonStyle(.plain).accessibilityIdentifier("overview.card.\(tab.sessionName)")
            Text(tab.sessionName).appFont(size: 18).foregroundColor(theme.text).lineLimit(1)
            Text("›_ \(env.profile(tab.profileID)?.host ?? "")").appFont(size: 16, design: .monospaced).foregroundColor(theme.accent).lineLimit(1)
            if reordering {
                HStack {
                    Button { sessions.move(from: index, to: index - 1) } label: { Image(systemName: "arrow.left").frame(width: 44, height: 44) }.disabled(index == 0).accessibilityLabel(tr("move_earlier"))
                    Spacer()
                    Button { sessions.move(from: index, to: index + 1) } label: { Image(systemName: "arrow.right").frame(width: 44, height: 44) }.disabled(index == sessions.tabs.count - 1).accessibilityLabel(tr("move_later"))
                }.foregroundColor(theme.text)
            }
        }
        .contextMenu { if !reordering { SessionMenuActions(tab: tab) { reordering = true } } }
        .modifier(SessionReorderModifier(tab: tab, enabled: reordering))
    }
}

/// The same actions are reachable on home and in the terminal's session overview.
struct SessionMenuActions: View {
    @EnvironmentObject private var sessions: SessionManager
    @EnvironmentObject private var env: AppEnvironment
    let tab: TerminalTab
    let reorder: () -> Void
    var body: some View {
        Button(action: reorder) { Label(tr("reorder"), systemImage: "arrow.up.arrow.down") }.accessibilityIdentifier("session.reorder")
        Button(role: .destructive) { sessions.close(tab); if sessions.tabs.isEmpty { env.route = nil } } label: { Label(tr("close"), systemImage: "xmark") }.accessibilityIdentifier("session.close")
        Button(role: .destructive) { sessions.closeAll(); env.route = nil } label: { Label(tr("close_all"), systemImage: "xmark.circle") }.accessibilityIdentifier("session.closeall")
    }
}

struct SessionReorderModifier: ViewModifier {
    @EnvironmentObject private var sessions: SessionManager
    let tab: TerminalTab
    let enabled: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if enabled {
            content.onDrag { NSItemProvider(object: tab.id as NSString) }
                .onDrop(of: [.plainText], delegate: RowDrop { dragged in
                    guard let from = sessions.tabs.firstIndex(where: { $0.id == dragged }), let to = sessions.tabs.firstIndex(where: { $0.id == tab.id }) else { return }
                    sessions.move(from: from, to: to)
                })
                .accessibilityAction(named: Text(tr("move_earlier"))) { move(-1) }
                .accessibilityAction(named: Text(tr("move_later"))) { move(1) }
        } else { content }
    }
    private func move(_ delta: Int) {
        if let from = sessions.tabs.firstIndex(where: { $0.id == tab.id }) { sessions.move(from: from, to: from + delta) }
    }
}
