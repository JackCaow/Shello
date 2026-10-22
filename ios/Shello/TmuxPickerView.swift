import SwiftUI
import ShelloCore
import ShelloServices

/// Choose or create a remote tmux session (or skip to a plain shell) for a saved connection.
struct TmuxPickerView: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    let profile: SSHProfile
    let close: () -> Void
    @State private var entries: [TmuxSessions.Entry]?
    @State private var failure: String?
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("\(profile.user)@\(profile.host)").appFont(size: 14).foregroundColor(theme.secondary)
                    if !profile.tmux { T("install_tmux_on_the_server_to_return_to_your_shell_and_editor_aft").appFont(size: 14).foregroundColor(theme.secondary) }
                    // New session and plain shell stay on top: a host can have dozens of tmux sessions.
                    if profile.tmux { row(title: tr("new_tmux_session_76"), subtitle: nil, icon: "plus") { open(SessionTarget.fresh(tmux: true)) }.accessibilityIdentifier("picker.new") }
                    row(title: tr("skip_tmux_and_open_a_shell"), subtitle: nil, icon: "arrow.right") { open(SessionTarget.fresh(tmux: false)) }.accessibilityIdentifier("picker.shell")
                    if profile.tmux {
                        if let failure {
                            Text(failure).foregroundColor(theme.danger).font(.callout).accessibilityIdentifier("picker.error")
                            SecondaryButton(title: tr("retry")) { Task { await load() } }
                        } else if let entries {
                            if entries.isEmpty { T("no_remote_sessions_create_one_or_skip_to_a_shell").font(.callout).foregroundColor(theme.secondary) }
                            else { SectionLabel(text: tr("remote_sessions")) }
                            ForEach(entries, id: \.name) { e in
                                row(title: e.name, subtitle: trPlural("tmux_window_count", e.windows) + (e.attached > 0 ? " · " + tr("connected") : "")) { open("=" + e.name) }
                                    .accessibilityIdentifier("picker.session.\(e.name)")
                            }
                        } else { HStack { ProgressView(); T("loading_remote_sessions") }.foregroundColor(theme.secondary) }
                    }
                }.padding(16)
            }
            .background(theme.canvas.ignoresSafeArea())
            .chrome(title: profile.name, closeID: "picker.close", close: close)
            .toolbar(.hidden, for: .navigationBar)
        }
        .task { if profile.tmux { await load() } else { open(SessionTarget.fresh(tmux: false)) } }
        .themed(theme)
    }

    private func row(title: String, subtitle: String?, icon: String = "rectangle.stack", action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).foregroundColor(theme.accent).frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).appFont(size: 17).foregroundColor(theme.text).lineLimit(1).truncationMode(.middle).multilineTextAlignment(.leading)
                    if let subtitle { Text(subtitle).appFont(size: 14).foregroundColor(theme.secondary) }
                }
                Spacer(minLength: 8)
            }.padding(.horizontal, 16).frame(minHeight: 64).background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        }
    }
    private func load() async {
        failure = nil; entries = nil
        do { entries = try await ConnectionSetup.listTmux(profileID: profile.id, store: env.store) }
        catch { failure = tr("cannot_load_tmux_sessions_check_your_network_credentials_and_tmux") }
    }
    private func open(_ target: String) { close(); env.open(profile, target: target) }
}
