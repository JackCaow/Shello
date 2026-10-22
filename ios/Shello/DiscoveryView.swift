import SwiftUI
import ShelloCore
import ShelloServices

struct DiscoveryView: View {
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @StateObject private var scanner: SSHDiscoveryModel
    private let ownsScanner: Bool
    let onPick: (Discovery.Host) -> Void

    init(scanner: SSHDiscoveryModel? = nil, onPick: @escaping (Discovery.Host) -> Void) {
        ownsScanner = scanner == nil
        _scanner = StateObject(wrappedValue: scanner ?? SSHDiscoveryModel())
        self.onPick = onPick
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        if scanner.scanning { ProgressView().tint(theme.accent) }
                        Text(scanner.scanning ? tr("looking_for_ssh_servers_on_your_network") : scanner.buttonTitle)
                            .appFont(size: 14).foregroundColor(theme.secondary)
                    }.accessibilityIdentifier("discover.status")
                    T("only_hosts_responding_with_an_ssh_banner_on_port_22_are_shown").appFont(size: 14).foregroundColor(theme.secondary)
                    ForEach(scanner.hosts) { host in
                        Button { onPick(host) } label: {
                            HStack(spacing: 14) {
                                Image(systemName: "server.rack").font(.system(size: 23)).foregroundColor(theme.secondary).frame(width: 32)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(host.name).appFont(size: 18, weight: .medium).foregroundColor(theme.text).multilineTextAlignment(.leading)
                                    Text("\(host.address):22 · \(host.source == .tailscale ? "Tailscale" : "LAN")")
                                        .appFont(size: 14, design: .monospaced).foregroundColor(theme.secondary)
                                }
                                Spacer(minLength: 8)
                                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundColor(theme.secondary)
                            }.padding(16).frame(maxWidth: .infinity, minHeight: 72, alignment: .leading).background(theme.surface, in: RoundedRectangle(cornerRadius: 18))
                        }.buttonStyle(.plain).accessibilityIdentifier("discover.host.\(host.address)")
                    }
                    if !scanner.scanning && scanner.hosts.isEmpty {
                        T("no_servers_found_scan_again_or_enter_an_address_manually").appFont(size: 15).foregroundColor(theme.secondary).padding(.vertical, 16)
                    }
                    SecondaryButton(title: tr("scan_for_servers_again")) { scanner.start(store: env.store) }.accessibilityIdentifier("discover.rescan")
                }.padding(20)
            }
            .background(theme.canvas.ignoresSafeArea())
            .chrome(title: tr("local_ssh_servers"), closeID: "discover.close") { dismiss() }
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear { if ownsScanner { scanner.start(store: env.store) } }
        .onDisappear { if ownsScanner { scanner.stop() } }
        .themed(theme)
    }
}
