import Foundation
import Combine
import ShelloCore
import ShelloSSH
import ShelloTerminal

@MainActor
public final class TerminalTab: ObservableObject, Identifiable {
    public enum State: Equatable {
        case idle
        case connecting
        case connected
        case needsTrust(HostKeyInfo)
        case failed(Failure, retryable: Bool)
    }
    public enum Failure: Equatable { case hostKeyMismatch, authentication, privateKey, network, timeout, tmuxUnavailable, other(String), closed }

    public let id: String
    public let profileID: String
    public let target: String
    public let session: TerminalSession
    @Published public internal(set) var state: State = .idle
    @Published public var caption = ""
    @Published public var draft = ""
    @Published public var draftMode = false
    @Published public internal(set) var previewLines: [String] = []
    var channel: ShellChannel?
    var generation = 0
    var attempts = 0
    var everConnected = false
    /// When the next automatic reconnect fires (for the countdown banner).
    @Published public internal(set) var reconnectAt: Date?

    init(id: String, profileID: String, target: String, session: TerminalSession) {
        self.id = id; self.profileID = profileID; self.target = target; self.session = session
    }
    public var isRemote: Bool { true }
    public var isConnected: Bool { state == .connected }
    /// Display name for the session part: tmux name, "shell" for plain/ghostty-generated targets.
    public var sessionName: String {
        if !caption.isEmpty { return caption }
        if target.hasPrefix("=") { return String(target.dropFirst()) }
        if target.hasPrefix("plain_") || target.hasPrefix("ghostty_") { return "shell" }
        return target
    }
    public func updatePreview(maxLines: Int = 6) {
        guard let f = try? session.frame() else { return }
        var lines = (0..<f.rows).map { r in (0..<f.columns).map { f.cell(row: r, column: $0).text.isEmpty ? " " : f.cell(row: r, column: $0).text }.joined().replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression) }
        while lines.last?.isEmpty == true { lines.removeLast() }
        previewLines = Array(lines.suffix(maxLines))
    }
}

/// Owns the ordered tabs, their SSH connections and VT sessions. Views only attach/detach.
@MainActor
public final class SessionManager: ObservableObject {
    @Published public private(set) var tabs: [TerminalTab] = []
    @Published public var selectedID: String?
    public let store: ProfileStore
    private let defaults: UserDefaults
    private let ioQueue = DispatchQueue(label: "shello.ssh.connect", qos: .userInitiated, attributes: .concurrent)
    public var columnsRows: (Int, Int) = (80, 24)
    public static let maximumTabs = 16

    public init(store: ProfileStore, defaults: UserDefaults = .standard) {
        self.store = store; self.defaults = defaults
    }

    public var selected: TerminalTab? { tabs.first { $0.id == selectedID } }
    public func tab(_ id: String) -> TerminalTab? { tabs.first { $0.id == id } }

    // MARK: open / close

    /// Opens (or focuses) a tab for `target` on `profile` and starts connecting.
    @discardableResult
    public func open(profile: SSHProfile, target: String? = nil, connect: Bool = true) throws -> TerminalTab {
        let t = target ?? (profile.tmux ? (profile.tmuxSession ?? SessionTarget.fresh(tmux: true)) : SessionTarget.fresh(tmux: false))
        let id = try SessionTarget.id(profile: profile.id, target: t)
        if let existing = tab(id) {
            selectedID = id
            if connect, existing.state == .idle || isRetryableFailure(existing.state) { self.connect(existing) }
            return existing
        }
        guard tabs.count < Self.maximumTabs else { throw ShelloError.invalid("Too many open sessions") }
        let session = try TerminalSession(columns: columnsRows.0, rows: columnsRows.1)
        let tab = TerminalTab(id: id, profileID: profile.id, target: t, session: session)
        tabs.append(tab); selectedID = id; persist()
        if connect { self.connect(tab) }
        return tab
    }
    public func close(_ tab: TerminalTab) {
        tab.generation += 1
        tab.channel?.onClose = nil; tab.channel?.onOutput = nil; tab.channel?.close(); tab.channel = nil
        tabs.removeAll { $0 === tab }
        tab.session.onChange = nil; tab.session.close()
        if selectedID == tab.id { selectedID = tabs.last?.id }
        persist()
    }
    public func closeAll() { for t in tabs { close(t) } }
    /// Drops the SSH client but keeps the tab (tmux keeps the remote session alive).
    public func disconnect(_ tab: TerminalTab) {
        tab.generation += 1
        tab.channel?.onClose = nil; tab.channel?.onOutput = nil; tab.channel?.close(); tab.channel = nil
        tab.state = .idle
    }
    public func select(_ id: String) { if tab(id) != nil { selectedID = id; persist() } }
    public func move(from: Int, to: Int) {
        guard tabs.indices.contains(from), tabs.indices.contains(to), from != to else { return }
        tabs.insert(tabs.remove(at: from), at: to); persist()
    }
    func isRetryableFailure(_ s: TerminalTab.State) -> Bool { if case .failed(_, let r) = s { return r }; return false }

    // MARK: connect

    public func connect(_ tab: TerminalTab) {
        guard tab.state != .connecting, tab.state != .connected else { return }
        tab.reconnectAt = nil
        tab.generation += 1
        let generation = tab.generation
        tab.state = .connecting
        let cols = tab.session.columns, rows = tab.session.rows
        let profileID = tab.profileID, target = tab.target
        let store = self.store
        ioQueue.async { [weak self] in
            var channel: ShellChannel?
            var failure: (TerminalTab.Failure, Bool)?
            var trust: HostKeyInfo?
            do {
                let (profile, secrets) = try store.read(profileID)
                let connection = SSHConnection(host: profile.host, port: profile.port, user: profile.user)
                let credentials: SSHCredentials = profile.auth == .key
                    ? .privateKey(secrets.privateKey, passphrase: secrets.passphrase.isEmpty ? nil : secrets.passphrase) : .password(secrets.password)
                try connection.connect(credentials: credentials, knownHosts: String(decoding: secrets.knownHosts, as: UTF8.self))
                let command: String? = target.hasPrefix("plain_") ? nil : try SessionTarget.command(target)
                if let command, !SessionTarget.isAgent(target) {
                    // Fail early and clearly when tmux is missing instead of showing a dead channel.
                    let probe = try connection.run(SessionTarget.tmuxLookup + "\"$T\" -V", timeout: 8)
                    guard probe.status == 0, String(decoding: probe.output, as: UTF8.self).hasPrefix("tmux ") else { throw TmuxMissing() }
                    _ = command
                }
                channel = try ShellChannel(connection: connection, command: command, columns: cols, rows: rows)
            } catch let e as SSHError {
                switch e {
                case .hostKeyUnknown(let info): trust = info
                case .hostKeyMismatch: failure = (.hostKeyMismatch, false)
                case .authentication: failure = (.authentication, false)
                case .privateKey: failure = (.privateKey, false)
                case .timeout: failure = (.timeout, true)
                case .network, .closed: failure = (.network, true)
                case .protocolError(let m): failure = (.other(m), false)
                }
            } catch is TmuxMissing { failure = (.tmuxUnavailable, false) }
            catch { failure = (.other(error.localizedDescription), false) }
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.finishConnect(tab, generation: generation, channel: channel, failure: failure, trust: trust) }
            }
        }
    }
    private struct TmuxMissing: Error {}

    /// Retryable failures reconnect on their own: 2, 4, 8, 16 then every 30 seconds while the tab exists.
    private func scheduleReconnect(_ tab: TerminalTab) {
        guard isRetryableFailure(tab.state) else { tab.reconnectAt = nil; return }
        tab.attempts += 1
        let delay = min(30.0, Double(1 << min(tab.attempts, 5)))
        tab.reconnectAt = Date().addingTimeInterval(delay)
        let generation = tab.generation
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self, weak tab] in
            guard let self, let tab, tab.generation == generation, self.isRetryableFailure(tab.state), self.tabs.contains(where: { $0 === tab }) else { return }
            tab.reconnectAt = nil; self.connect(tab)
        }
    }

    private func finishConnect(_ tab: TerminalTab, generation: Int, channel: ShellChannel?, failure: (TerminalTab.Failure, Bool)?, trust: HostKeyInfo?) {
        guard tab.generation == generation, tabs.contains(where: { $0 === tab }) else { channel?.close(); return }
        if let trust { tab.state = .needsTrust(trust); return }
        if let (f, retry) = failure { tab.state = .failed(f, retryable: retry); scheduleReconnect(tab); return }
        guard let channel else { tab.state = .failed(.other("no channel"), retryable: true); return }
        if tab.everConnected && !tab.target.hasPrefix("plain_") {
            _ = try? tab.session.write("\u{1b}c")   // tmux redraws the preserved screen over a clean VT
        }
        tab.everConnected = true; tab.attempts = 0; tab.reconnectAt = nil
        tab.channel = channel
        let session = tab.session
        channel.onOutput = { [weak channel] data in
            if let replies = try? session.write(data), !replies.isEmpty { channel?.write(replies) }
        }
        channel.onClose = { [weak self, weak tab] err in
            guard let self, let tab, tab.generation == generation else { return }
            tab.channel = nil
            tab.state = .failed(err.map { $0.isNetworkFailure ? .network : .other($0.localizedDescription) } ?? .closed, retryable: err?.isNetworkFailure ?? false)
            self.scheduleReconnect(tab)
        }
        tab.state = .connected
        channel.start()
    }

    // MARK: IO

    public func send(_ tab: TerminalTab, _ data: Data) {
        if tab.channel?.write(data) == false { tab.state = .failed(.other("input overflow"), retryable: true) }
    }
    public func resize(_ tab: TerminalTab, columns: Int, rows: Int) { tab.channel?.resize(columns: columns, rows: rows) }

    /// Foreground hook: reconnect tabs whose SSH client died while the app was suspended.
    public func appDidBecomeActive() {
        for t in tabs where t.state == .idle && t.everConnected || isRetryableFailure(t.state) { connect(t) }
    }
    public func appDidEnterBackground() { for t in tabs { t.updatePreview() } }

    // MARK: persistence

    private func persist() {
        defaults.set(tabs.map(\.id), forKey: "terminal_tabs.order")
        defaults.set(selectedID, forKey: "terminal_tabs.selected")
    }
    /// Recreates tabs from the last run (disconnected; they connect when selected or on foreground).
    public func restore() {
        guard tabs.isEmpty, let ids = defaults.stringArray(forKey: "terminal_tabs.order") else { return }
        for id in ids {
            guard let target = SessionTarget.target(of: id), store.list().contains(where: { $0.id == SessionTarget.profile(of: id) }),
                  let session = try? TerminalSession(columns: columnsRows.0, rows: columnsRows.1) else { continue }
            let tab = TerminalTab(id: id, profileID: SessionTarget.profile(of: id), target: target, session: session)
            tab.everConnected = true
            tabs.append(tab)
        }
        selectedID = defaults.string(forKey: "terminal_tabs.selected").flatMap { s in tabs.contains { $0.id == s } ? s : nil } ?? tabs.last?.id
    }
}
