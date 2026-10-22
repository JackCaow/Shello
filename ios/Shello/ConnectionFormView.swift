import SwiftUI
import UniformTypeIdentifiers
import ShelloCore
import ShelloServices
import ShelloSSH

/// New / edit connection. Nothing is saved until authentication (and tmux, when enabled) verified and the host key was trusted.
struct ConnectionFormView: View {
    struct Prefill { var host = "", name = "" }
    @EnvironmentObject var env: AppEnvironment
    @Environment(\.theme) var theme
    @Environment(\.dismiss) var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    let editing: SSHProfile?
    var prefill: Prefill?
    let onSaved: (SSHProfile) -> Void

    @State private var name = ""
    @State private var host = ""
    @State private var port = "22"
    @State private var user = ""
    @State private var auth = AuthMethod.password
    @State private var password = ""
    @State private var passphrase = ""
    @State private var privateKey = Data()
    @State private var tmux = true
    @State private var importing = false
    @State private var status: String?
    @State private var error: String?
    @State private var busy = false
    @State private var trust: HostKeyInfo?
    @State private var original: (SSHProfile, SSHSecrets)?
    @State private var confirmDiscard = false
    @State private var dirty = false
    @StateObject private var discovery = SSHDiscoveryModel()
    @State private var showDiscovery = false
    @State private var loaded = false
    @State private var lastDiscoveryName: String?

    init(editing: SSHProfile?, prefill: Prefill? = nil, onSaved: @escaping (SSHProfile) -> Void) { self.editing = editing; self.prefill = prefill; self.onSaved = onSaved }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Field(label: tr("name"), text: $name, placeholder: tr("my_mac"), id: "form.name")
                    if editing == nil {
                        Button { showDiscovery = true } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "network")
                                Text(discovery.buttonTitle).appFont(size: 15, weight: .medium)
                                Spacer(minLength: 8)
                                if discovery.scanning { ProgressView().tint(theme.accent) }
                                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                            }.foregroundColor(theme.accent).padding(.horizontal, 16).frame(minHeight: 48)
                                .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
                        }.buttonStyle(.plain).accessibilityIdentifier("form.discovery")
                    }
                    AdaptiveRow(spacing: 12) {
                        Field(label: tr("host"), text: $host, placeholder: tr("ssh_host_address"), keyboard: .URL, id: "form.host", mono: true)
                        Field(label: tr("port"), text: $port, keyboard: .numberPad, id: "form.port").frame(width: typeSize.isAccessibilitySize ? nil : 96)
                    }
                    Field(label: tr("username"), text: $user, placeholder: tr("username_on_the_server"), id: "form.user")
                    VStack(alignment: .leading, spacing: 10) {
                        Text(tr("authentication")).appFont(size: 18, weight: .medium).foregroundColor(theme.text)
                        SegmentedChoice(options: [(tr("password"), AuthMethod.password, "form.auth.password"), (tr("private_key"), AuthMethod.key, "form.auth.key")], selection: $auth)
                            .accessibilityIdentifier("form.auth")
                    }
                    if auth == .password {
                        Field(label: tr("login_password"), text: $password, placeholder: editing != nil ? tr("password_saved_leave_blank_to_keep") : tr("ssh_login_password"), secure: true, id: "form.password")
                    } else {
                        Button { importing = true } label: {
                            HStack { Image(systemName: "key"); Text(privateKey.isEmpty ? tr("choose_private_key") : tr("private_key_bytes", privateKey.count)).appFont(size: 17); Spacer() }
                                .padding(.horizontal, 16).frame(minHeight: 56).background(theme.input, in: RoundedRectangle(cornerRadius: 20)).foregroundColor(theme.text)
                        }.accessibilityIdentifier("form.key")
                        Field(label: tr("ssh_key_passphrase"), text: $passphrase, placeholder: editing != nil ? tr("passphrase_saved_leave_blank_to_keep") : tr("key_passphrase_optional"), secure: true, id: "form.passphrase")
                    }
                    GroupCard { ToggleRow(title: tr("keep_remote_sessions_with_tmux"), isOn: $tmux, id: "form.tmux") }
                    if let error { Text(error).appFont(size: 15).foregroundColor(theme.danger).accessibilityIdentifier("form.error") }
                    if let status { Text(status).appFont(size: 15).foregroundColor(theme.secondary).accessibilityIdentifier("form.status") }
                    PrimaryButton(title: editing == nil ? tr("save_and_connect_over_ssh") : tr("save_changes_and_connect"), busy: busy) { Task { await verify() } }
                        .accessibilityIdentifier("form.save")
                    T("verify_the_server_fingerprint_on_first_connection_connections_are").appFont(size: 14).lineSpacing(3).foregroundColor(theme.secondary).multilineTextAlignment(.leading)
                }.padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(theme.canvas.ignoresSafeArea())
            .chrome(title: editing == nil ? tr("new_ssh_connection") : tr("edit_connection"), closeID: "form.cancel", trailing: {
                CircleButton(systemName: "checkmark", tint: theme.vivid, id: "form.save.header", label: tr("save_and_connect")) { Task { await verify() } }
            }) { if dirty { confirmDiscard = true } else { dismiss() } }
            .toolbar(.hidden, for: .navigationBar)
            .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button(tr("done")) { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }.accessibilityIdentifier("keyboard.done") } }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .text, .item]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let d = try? Data(contentsOf: url), d.count <= 1_048_576, !d.isEmpty { privateKey = d; error = nil; dirty = true }
            else { error = tr("cannot_read_this_key_choose_an_openssh_pem_private_key_no_larger") }
        }
        .sheet(item: Binding(get: { trust.map { TrustItem(info: $0) } }, set: { if $0 == nil { trust = nil } })) { item in
            TrustSheet(info: item.info, onTrust: { Task { await trusted(item.info) } }, onCancel: { trust = nil; busy = false; status = nil; error = tr("connection_cancelled_server_not_saved") })
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showDiscovery) {
            DiscoveryView(scanner: discovery) { server in
                host = server.address; port = "22"
                if name.trimmingCharacters(in: .whitespaces).isEmpty || name == lastDiscoveryName { name = server.name; lastDiscoveryName = server.name }
                showDiscovery = false
            }.presentationDetents([.medium, .large]).sheetStyle(theme)
        }
        .alert(tr("discard_changes"), isPresented: $confirmDiscard) {
            Button(tr("discard_changes_71"), role: .destructive) { dismiss() }
            Button(tr("keep_editing"), role: .cancel) {}
        } message: { T("unsaved_changes_will_be_lost_your_original_connection_will_be_kep") }
        .onAppear {
            if !loaded { loaded = true; load() }
            if editing == nil { discovery.resume(store: env.store) }
        }
        .onDisappear { if !showDiscovery { discovery.stop() } }
        .onChange(of: name) { _ in dirty = true }.onChange(of: host) { _ in dirty = true }.onChange(of: user) { _ in dirty = true }
        .interactiveDismissDisabled(dirty)
        .themed(theme)
    }

    struct TrustItem: Identifiable { let info: HostKeyInfo; var id: String { info.fingerprint } }

    private func load() {
        if let p = editing, let (prof, sec) = try? env.store.read(p.id) {
            original = (prof, sec)
            name = prof.name; host = prof.host; port = String(prof.port); user = prof.user; auth = prof.auth; tmux = prof.tmux
            privateKey = sec.privateKey
        } else if let prefill { host = prefill.host; name = prefill.name }
        if env.testMode, let i = ProcessInfo.processInfo.arguments.firstIndex(of: "-SHELLO_TEST_KEY_B64"), i + 1 < ProcessInfo.processInfo.arguments.count,
           let d = Data(base64Encoded: ProcessInfo.processInfo.arguments[i + 1]) { auth = .key; privateKey = d }
        DispatchQueue.main.async { dirty = false }
    }

    private func draft() -> (SSHProfile, SSHSecrets)? {
        var profile = editing ?? SSHProfile()
        profile.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? host : name.trimmingCharacters(in: .whitespaces)
        profile.host = host.trimmingCharacters(in: .whitespaces); profile.port = Int(port) ?? 0; profile.user = user.trimmingCharacters(in: .whitespaces)
        profile.auth = auth; profile.tmux = tmux
        if tmux, profile.tmuxSession == nil { profile.tmuxSession = SessionTarget.fresh(tmux: true) }
        var secrets = original?.1 ?? SSHSecrets()
        // Editing keeps stored secrets when the fields are left blank; a changed endpoint drops the old host pin.
        if auth == .password { if !password.isEmpty { secrets.password = password }; secrets.privateKey = Data() }
        else { secrets.privateKey = privateKey; if !passphrase.isEmpty { secrets.passphrase = passphrase }; secrets.password = "" }
        if let o = original?.0, o.host != profile.host || o.port != profile.port { secrets.knownHosts = Data() }
        return (profile, secrets)
    }

    private func verify() async {
        error = nil
        guard let (profile, secrets) = draft() else { return }
        var check = secrets
        if let msg = ProfileValidation.validate(profile, check) { error = validationText(msg); return }
        busy = true; status = tr("verifying_connection")
        switch await ConnectionSetup.verify(profile: profile, secrets: check) {
        case .verified: finish(profile, check)
        case .trustNeeded(let info): status = tr("waiting_for_fingerprint_confirmation"); trust = info
        case .failed(let f): busy = false; status = nil; error = failureText(f)
        }
        check = secrets
    }
    private func trusted(_ info: HostKeyInfo) async {
        trust = nil
        guard let (profile, base) = draft() else { return }
        var secrets = base
        secrets.knownHosts = Data(info.knownHostsLine.utf8)
        status = tr("verifying_connection")
        switch await ConnectionSetup.verify(profile: profile, secrets: secrets) {
        case .verified: finish(profile, secrets)
        case .trustNeeded, .failed: busy = false; status = nil; error = tr("connection_failed_check_your_network_credentials_and_server_finge")
        }
    }
    private func finish(_ profile: SSHProfile, _ secrets: SSHSecrets) {
        do {
            try env.store.save(profile, secrets: secrets); env.reload()
            for t in env.sessions.tabs where t.profileID == profile.id && editing != nil { env.sessions.disconnect(t) }
            busy = false; dirty = false; onSaved(profile)
        } catch { busy = false; status = nil; self.error = tr("connection_verified_but_saving_failed_try_again") }
    }
    private func validationText(_ code: String) -> String {
        switch code {
        case "invalid_host": return tr("enter_a_hostname_or_ip_address_without_a_protocol_or_port")
        case "invalid_port": return tr("port_must_be_between_1_and_65535")
        case "invalid_user": return tr("enter_a_valid_ssh_username")
        case "password_required": return tr("enter_your_login_password")
        case "key_required": return tr("choose_an_ssh_private_key_file")
        default: return tr("invalid_configuration")
        }
    }
}

struct TrustSheet: View {
    @Environment(\.theme) var theme
    let info: HostKeyInfo; let onTrust: () -> Void; let onCancel: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            T("verify_server_fingerprint").appFont(size: 22, weight: .semibold).foregroundColor(theme.text)
            T("compare_this_fingerprint_with_your_server_s_fingerprint_login_cre").appFont(size: 15).lineSpacing(3).foregroundColor(theme.secondary)
            Text(info.fingerprint).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
                .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(theme.input, in: RoundedRectangle(cornerRadius: 20))
                .foregroundColor(theme.text).accessibilityIdentifier("trust.fingerprint")
            Spacer()
            PrimaryButton(title: tr("trust_and_connect"), action: onTrust).accessibilityIdentifier("trust.accept")
            SecondaryButton(title: tr("cancel"), action: onCancel).accessibilityIdentifier("trust.cancel")
        }.padding(20).background(theme.canvas.ignoresSafeArea()).themed(theme)
    }
}
