import SwiftUI
import Combine
import UserNotifications
import ShelloCore
import ShelloServices
import ShelloTerminal

/// Process-wide services. UI tests can launch with `-SHELLO_TEST_PROFILE_DIR <dir>` to use an isolated, in-memory-secret store.
@MainActor
final class AppEnvironment: ObservableObject {
    let store: ProfileStore
    let sessions: SessionManager
    let hub: AgentHub
    let prefs: InputPreferences
    let hardwareKeyboard: HardwareKeyboardMonitor
    let settings: AppSettings
    @Published var navigationPath: [Route] = []
    /// Existing callers express a screen intent; one stack owns buttons, deep links and interactive back.
    var route: Route? {
        get { navigationPath.last }
        set {
            switch newValue {
            case .settings(let page):
                var path = navigationPath
                if let index = path.firstIndex(of: .settings(nil)) { path = Array(path.prefix(index + 1)) }
                else { path.append(.settings(nil)) }
                if let page { path.append(.settings(page)) }
                navigationPath = path
            case .some(let screen):
                if let index = navigationPath.firstIndex(of: screen) { navigationPath = Array(navigationPath.prefix(index + 1)) }
                else { navigationPath.append(screen) }
            case .none: navigationPath = []
            }
        }
    }
    @Published var toast: String?
    @Published var profiles: [SSHProfile] = []
    @Published var composerAttachments: [ComposerAttachment] = []
    let testMode: Bool
    private var cancellables = Set<AnyCancellable>()

    enum Route: Identifiable, Hashable {
        case terminal, agent, settings(String?)
        var id: String { switch self { case .terminal: return "terminal"; case .agent: return "agent"; case .settings(let p): return "settings-\(p ?? "")" } }
    }

    init(profileDirectory: URL? = nil, defaults suppliedDefaults: UserDefaults? = nil, secrets suppliedSecrets: SecretStore? = nil, hardwareKeyboard: HardwareKeyboardMonitor? = nil) {
        self.hardwareKeyboard = hardwareKeyboard ?? HardwareKeyboardMonitor()
        let args = ProcessInfo.processInfo.arguments
        testMode = args.contains("-SHELLO_UI_TEST")
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = profileDirectory ?? support.appendingPathComponent("profiles", isDirectory: true)
        if testMode { dir = FileManager.default.temporaryDirectory.appendingPathComponent("shello-qa-" + UUID().uuidString, isDirectory: true) }
        if testMode, let i = args.firstIndex(of: "-SHELLO_TEST_PROFILE_DIR"), i + 1 < args.count { dir = URL(fileURLWithPath: args[i + 1]) }
        let secrets: SecretStore = suppliedSecrets ?? (testMode ? FileSecretStore(directory: dir.appendingPathComponent("secrets")) : KeychainSecretStore())
        let defaults = suppliedDefaults ?? (testMode ? UserDefaults(suiteName: "shello.uitest") ?? .standard : .standard)
        if testMode { defaults.removePersistentDomain(forName: "shello.uitest") }   // UI tests start from the system language
        store = (try? ProfileStore(directory: dir, secrets: secrets)) ?? (try! ProfileStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("p-\(UUID())"), secrets: MemorySecretStore()))
        sessions = SessionManager(store: store, defaults: defaults)
        prefs = InputPreferences(defaults: defaults)
        settings = AppSettings(defaults: defaults)
        let script = Bundle.main.url(forResource: "ghostty_hook", withExtension: "py").flatMap { try? Data(contentsOf: $0) } ?? Data()
        var hookConfig = AgentHub.Config(script: script)
        if testMode, let i = args.firstIndex(of: "-SHELLO_TEST_HOOK_STATE"), i + 1 < args.count {
            let state = args[i + 1], quoted = ShellQuote.quote(state)
            let prefix = "GHOSTTY_HOOK_STATE=" + quoted + " GHOSTTY_CLAUDE_SETTINGS=" + ShellQuote.quote(state + "/claude.json") + " GHOSTTY_CODEX_SETTINGS=" + ShellQuote.quote(state + "/codex.json") + " "
            let command = prefix + "python3 " + ShellQuote.quote(state + "/ghostty_hook.py") + " "
            hookConfig = .init(script: script, command: command, installCommand: "mkdir -p " + quoted + " && cat > " + ShellQuote.quote(state + "/ghostty_hook.py") + " && " + command + "install")
            defaults.set(true, forKey: "agent.host_00000000-0000-4000-8000-000000000001")
        }
        hub = AgentHub(store: store, defaults: defaults, config: hookConfig, cacheURL: (testMode ? dir : support).appendingPathComponent("agent-cache.json"))
        if testMode, let k = args.firstIndex(of: "-SHELLO_TEST_SEED_KEY_B64"), let p = args.firstIndex(of: "-SHELLO_TEST_SEED_PUB"), let u = args.firstIndex(of: "-SHELLO_TEST_USER"), max(k, p, u) + 1 < args.count {
            let host = args.firstIndex(of: "-SHELLO_TEST_HOST").map { args[$0 + 1] } ?? "127.0.0.1"
            let port = args.firstIndex(of: "-SHELLO_TEST_PORT").flatMap { Int(args[$0 + 1]) } ?? 2299
            seed(key: Data(base64Encoded: args[k + 1]) ?? Data(), pub: args[p + 1], user: args[u + 1], host: host, port: port)
        }
        profiles = store.list()
        settings.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        self.hardwareKeyboard.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        hub.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        sessions.$tabs.dropFirst().sink { [weak self] tabs in
            guard let self else { return }
            let active = Set(tabs.map(\.id))
            for item in self.composerAttachments where !active.contains(item.tabID) { item.discard() }
            self.composerAttachments.removeAll { !active.contains($0.tabID) }
        }.store(in: &cancellables)
        hub.onNotify = { [weak self] e in self?.notify(e) }
        if !testMode && suppliedDefaults == nil { sessions.restore() }
    }

    /// UI-test fixture: a saved connection to the test sshd (see scripts/ios-test-sshd.sh). Secrets arrive as launch arguments so
    /// the same fixture works on a physical device, where the runner and the Mac do not share a filesystem.
    private func seed(key: Data, pub: String, user: String, host: String, port: Int) {
        let f = pub.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ")
        guard !key.isEmpty, f.count >= 2 else { return }
        let p = SSHProfile(id: "00000000-0000-4000-8000-000000000001", name: "Local sshd", host: host, port: port, user: user, auth: .key, tmux: true)
        try? store.save(p, secrets: SSHSecrets(privateKey: key, knownHosts: Data("[\(host)]:\(port) \(f[0]) \(f[1])\n".utf8)))
    }

    func reload() { profiles = store.list() }
    func showToast(_ text: String) {
        toast = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in if self?.toast == text { self?.toast = nil } }
    }
    func profile(_ id: String) -> SSHProfile? { profiles.first { $0.id == id } }

    func delete(_ p: SSHProfile) {
        for t in sessions.tabs where t.profileID == p.id { sessions.close(t) }
        try? store.delete(p.id); reload()
    }

    func notify(_ e: AgentEvent) {
        guard UIApplication.shared.applicationState != .active else { return }
        let c = UNMutableNotificationContent()
        c.title = "\(e.agent) · " + (e.isPending() ? tr("an_agent_needs_your_response") : tr("new_agent_event"))
        c.body = e.host; c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: e.id, content: c, trigger: nil))
    }
    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// Opens (or focuses) a terminal tab for a saved connection and shows the terminal.
    func open(_ p: SSHProfile, target: String? = nil) {
        do { try sessions.open(profile: p, target: target); route = .terminal }
        catch { showToast(error.localizedDescription) }
    }
}

/// File-backed secret store for UI tests only (the Keychain needs a signed host and is exercised in ShelloAppTests).
final class FileSecretStore: SecretStore {
    let dir: URL
    init(directory: URL) { dir = directory; try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) }
    func read(_ key: String) throws -> Data? { try? Data(contentsOf: dir.appendingPathComponent(key)) }
    func write(_ key: String, _ value: Data) throws { try value.write(to: dir.appendingPathComponent(key)) }
    func delete(_ key: String) throws { try? FileManager.default.removeItem(at: dir.appendingPathComponent(key)) }
}

/// Human text for connection failures (keys shared with Android).
func failureText(_ f: TerminalTab.Failure) -> String {
    switch f {
    case .hostKeyMismatch: return tr("server_fingerprint_mismatch_connection_stopped_verify_the_key_wit")
    case .authentication: return tr("authentication_failed_check_your_username_password_or_private_key")
    case .privateKey: return tr("cannot_read_the_private_key_check_its_format_and_passphrase")
    case .network, .timeout: return tr("cannot_reach_the_server_check_the_address_port_and_network_then_t")
    case .tmuxUnavailable: return tr("tmux_is_unavailable_install_it_on_the_server_or_turn_off_keep_rem")
    case .other, .closed: return tr("ssh_connection_failed_check_authentication_and_server_settings")
    }
}
