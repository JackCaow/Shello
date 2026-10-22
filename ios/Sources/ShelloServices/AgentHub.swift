import Foundation
import Combine
import ShelloCore

public typealias JSON = [String: Any]

/// One hook event from a host (`ghostty_hook.py poll`).
public struct AgentEvent: Identifiable {
    public let profile: String, host: String
    public var data: JSON
    public let deadline: Date
    public var id: String { profile + ":" + (data["id"] as? String ?? "") }
    public init(profile: String, host: String, data: JSON, now: Date = Date()) {
        self.profile = profile; self.host = host
        let remaining = max(0, (data["remainingMs"] as? Double) ?? Double((data["remainingMs"] as? Int) ?? 0))
        deadline = now.addingTimeInterval(remaining / 1000)
        var d = data
        if (d["cached"] as? Bool) != true { d["localExpiresAt"] = now.timeIntervalSince1970 * 1000 + remaining }
        self.data = d
    }
    func str(_ k: String) -> String { data[k] as? String ?? "" }
    func num(_ k: String, _ fallback: Double = 0) -> Double { (data[k] as? Double) ?? Double((data[k] as? Int) ?? 0).nonZero ?? fallback }
    public var provider: String { str("provider").isEmpty ? "claude" : str("provider") }
    public var agent: String { provider == "codex" ? "Codex" : "Claude Code" }
    public var created: Double { (data["localCreated"] as? Double) ?? (data["created"] as? Double) ?? 0 }
    public func isPending(now: Date = Date()) -> Bool {
        str("status") == "pending" && (data["cached"] as? Bool) != true && (data["offline"] as? Bool) != true
            && (data["process_alive"] as? Bool) != false && now < deadline
    }
}
private extension Double { var nonZero: Double? { self == 0 ? nil : self } }

/// Derived session state (port of `AgentInbox`); requests keep their exact event identities.
public struct AgentSession: Identifiable {
    public enum State: Int { case needsYou, working, done, interrupted, offline, archived }
    public let key: String, profile: String, provider: String, sessionID: String, host: String
    public var events = [AgentEvent](), requests = [AgentEvent]()
    public var latest: AgentEvent
    public var state = State.offline
    public var archived = false, unresolved = false, attention = false
    public var updated = 0.0
    public var id: String { key }
    init(_ e: AgentEvent) {
        profile = e.profile; provider = e.provider; sessionID = e.str("session_id").isEmpty ? e.id : e.str("session_id"); host = e.host
        key = "\(e.profile):\(provider):\(sessionID)"; latest = e
    }
    public var agent: String { latest.agent }
    public var cwd: String { events.reversed().map { $0.str("cwd") }.first { !$0.isEmpty } ?? "" }
    public var project: String { let c = cwd; if c.isEmpty { return host }; let last = (c as NSString).lastPathComponent; return last.isEmpty ? c : last }
    public var model: String { events.reversed().map { $0.str("model") }.first { !$0.isEmpty } ?? "" }
    var currentPrompt: AgentEvent? { events.last { $0.str("hook_event_name") == "UserPromptSubmit" } }
    public var lastPrompt: String { currentPrompt?.str("prompt_preview").trimmingCharacters(in: .whitespacesAndNewlines) ?? "" }
    public var lastAssistant: String {
        let prompt = currentPrompt; let turn = prompt?.str("turn_id") ?? ""
        for e in events.reversed() {
            if let p = prompt, e.id == p.id { break }
            let et = e.str("turn_id"); if !turn.isEmpty, !et.isEmpty, turn != et { continue }
            let m = e.str("last_assistant_message").trimmingCharacters(in: .whitespacesAndNewlines); if !m.isEmpty { return m }
        }
        return ""
    }
    public var summary: String {
        let r = lastAssistant, p = lastPrompt, m = latest.str("message").trimmingCharacters(in: .whitespacesAndNewlines)
        return !r.isEmpty ? r : !p.isEmpty ? p : !m.isEmpty ? m : project
    }
    /// `=tmux-name` target to jump to this session, when the hook recorded one.
    public var target: String? {
        for e in events.reversed() { let s = e.str("tmux_session"); if !s.isEmpty, (try? SessionTarget.validate("=" + s)) != nil { return "=" + s } }
        return nil
    }
}

public enum AgentInbox {
    public static func sessions(_ events: [AgentEvent], defaults: UserDefaults, now: Date = Date()) -> [AgentSession] {
        var unique = [String: AgentEvent](); var order = [String]()
        for e in events { if unique[e.id] == nil { order.append(e.id) }; unique[e.id] = e }
        let ordered = order.compactMap { unique[$0] }.sorted { $0.created < $1.created }
        var grouped = [String: AgentSession](); var keys = [String]()
        for e in ordered {
            var s = AgentSession(e)
            if grouped[s.key] == nil { grouped[s.key] = s; keys.append(s.key) }
            s = grouped[s.key]!
            s.events.append(e); s.latest = e; s.updated = e.created
            if e.isPending(now: now) { s.requests.append(e) }
            if e.str("status") == "pending" { s.unresolved = true }
            grouped[s.key] = s
        }
        let nowSec = now.timeIntervalSince1970
        for k in keys {
            var s = grouped[k]!
            let turn = s.currentPrompt?.str("turn_id") ?? ""
            if !turn.isEmpty {
                for e in s.events.reversed() { let et = e.str("turn_id"); if et.isEmpty || et == turn { s.latest = e; s.updated = e.created; break } }
            }
            let archiveAt = defaults.double(forKey: "agent.archive_" + s.key)
            let kind = s.latest.str("hook_event_name")
            let working = ["SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse"].contains(kind)
            let ld = s.latest.data
            let stale = (working || s.latest.str("status") == "pending") && ((ld["cached"] as? Bool) == true || (ld["offline"] as? Bool) == true
                || (ld["process_alive"] as? Bool) == false || (working && (ld["process_alive"] as? Bool) != true && nowSec - s.updated > 120))
            let restored = now.timeIntervalSince1970 * 1000 - defaults.double(forKey: "agent.restore_" + s.key) < 600_000
            s.archived = !s.unresolved && ((archiveAt > 0 && s.updated * 1000 <= archiveAt) || (!restored && (nowSec - s.updated > 21600 || (!working && nowSec - s.updated > 600))))
            s.attention = kind == "Notification" && ["permission_prompt", "idle_prompt"].contains(s.latest.str("notification_type"))
            if !s.requests.isEmpty || (s.attention && !s.archived) { s.state = .needsYou }
            else if s.archived { s.state = .archived } else if stale { s.state = .offline } else if working { s.state = .working }
            else if kind == "Interrupt" || kind == "StopFailure" { s.state = .interrupted }
            else if kind == "Stop" || kind == "SessionEnd" { s.state = .done } else { s.state = .offline }
            grouped[k] = s
        }
        return grouped.values.sorted { $0.state.rawValue != $1.state.rawValue ? $0.state.rawValue < $1.state.rawValue : $0.updated > $1.updated }
    }
    public static func matches(_ s: AgentSession, query: String?, provider: String?, host: String?, project: String?) -> Bool {
        if let provider, provider != s.provider { return false }
        if let host, host != s.profile { return false }
        if let project, project != s.cwd { return false }
        let needle = (query ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        return needle.isEmpty || "\(s.agent) \(s.host) \(s.project) \(s.cwd) \(s.summary) \(s.lastPrompt) \(s.model)".lowercased().contains(needle)
    }
}

/// Host events and quota snapshots over pinned SSH. Cached requests can never grant permission.
@MainActor
public final class AgentHub: ObservableObject {
    public struct Config {
        /// Remote command prefix (ends with a space). Tests point this at an isolated state directory.
        public var command = AgentCommand.base
        public var installCommand: String? = nil
        public var script: Data
        public init(script: Data, command: String = AgentCommand.base, installCommand: String? = nil) { self.script = script; self.command = command; self.installCommand = installCommand }
    }
    public enum HostState: Equatable { case connected, unreadable, waiting }
    @Published public private(set) var events = [AgentEvent]()
    @Published public private(set) var hostStates = [String: HostState]()
    @Published public private(set) var usages = [String: JSON]()
    @Published public private(set) var diagnostics = [String: JSON]()
    @Published public private(set) var busy = false
    public var onNotify: ((AgentEvent) -> Void)?
    private let store: ProfileStore, defaults: UserDefaults, config: Config, cacheURL: URL?
    private var notified = Set<String>(); private var users = 0; private var loop: Task<Void, Never>?; private var lastUsage = Date.distantPast

    public init(store: ProfileStore, defaults: UserDefaults = .standard, config: Config, cacheURL: URL? = nil) {
        self.store = store; self.defaults = defaults; self.config = config; self.cacheURL = cacheURL
        loadCache()
    }
    public func enabled(_ profile: String) -> Bool { defaults.bool(forKey: "agent.host_" + profile) }
    public func isRead(_ e: AgentEvent) -> Bool { defaults.bool(forKey: "agent.read_" + e.id) }
    public func markRead(_ e: AgentEvent) {
        guard !isRead(e) else { return }
        defaults.set(true, forKey: "agent.read_" + e.id); objectWillChange.send()
    }
    public func markSessionRead(_ s: AgentSession) { for e in s.events { markRead(e) } }
    public var sessions: [AgentSession] { AgentInbox.sessions(events, defaults: defaults) }
    public func pending(profile: String? = nil) -> Int { events.filter { (profile == nil || $0.profile == profile) && $0.isPending() }.count }
    public func archive(_ s: AgentSession, _ value: Bool) {
        if value && s.unresolved { return }
        if value { defaults.set(Date().timeIntervalSince1970 * 1000, forKey: "agent.archive_" + s.key) }
        else { defaults.removeObject(forKey: "agent.archive_" + s.key); defaults.set(Date().timeIntervalSince1970 * 1000, forKey: "agent.restore_" + s.key) }
        objectWillChange.send(); persist()
    }

    // MARK: polling

    public func attach() { users += 1; startLoop(); Task { await refresh() } }
    public func detach() { users = max(0, users - 1); if users == 0 { loop?.cancel(); loop = nil } }
    private func startLoop() {
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                await self?.refresh()
            }
        }
    }

    public func refresh() async {
        guard !busy else { return }
        busy = true; defer { busy = false }
        let profiles = store.list(); let known = Set(profiles.map(\.id))
        var next = [AgentEvent](); var status = [String: HostState](); var info = [String: JSON](); var ok = Set<String>()
        for p in profiles where enabled(p.id) {
            do {
                let out = try await RemoteCommand.run(profileID: p.id, store: store, command: config.command + "poll", input: nil)
                guard let r = try JSONSerialization.jsonObject(with: out) as? JSON, let rows = r["events"] as? [JSON] else { throw ShelloError.failed("bad poll") }
                let now = (r["now"] as? Double ?? Date().timeIntervalSince1970)
                let local = Date().timeIntervalSince1970
                for var row in rows.prefix(400) {
                    let created = row["created"] as? Double ?? now
                    row["localCreated"] = local + created - now; row["cached"] = false; row["offline"] = false
                    row["remainingMs"] = max(0, Int(((row["expires"] as? Double ?? 0) - now) * 1000))
                    next.append(AgentEvent(profile: p.id, host: p.name, data: row))
                }
                status[p.id] = .connected; ok.insert(p.id)
                if let d = r["diagnostics"] as? JSON { info[p.id] = d }
            } catch { status[p.id] = .unreadable }
        }
        var merged = [String: AgentEvent](); var order = [String]()
        let incoming = Set(next.map(\.id)); let nowSec = Date().timeIntervalSince1970
        for var old in events where known.contains(old.profile) && enabled(old.profile) && !incoming.contains(old.id) && nowSec - old.num("created") < 604800 {
            old.data["cached"] = true; old.data["offline"] = !ok.contains(old.profile)
            merged[old.id] = old; order.append(old.id)
        }
        for e in next { if merged[e.id] == nil { order.append(e.id) }; merged[e.id] = e }
        var all = order.compactMap { merged[$0] }.sorted { $0.num("created") > $1.num("created") }
        if all.count > 400 { all.removeLast(all.count - 400) }
        events = all; hostStates = status
        for (k, v) in info { diagnostics[k] = v }
        diagnostics = diagnostics.filter { known.contains($0.key) }; usages = usages.filter { known.contains($0.key) }
        for e in next {
            let fresh = nowSec - e.num("created") < 120
            if notified.insert(e.id).inserted, !isRead(e), defaults.object(forKey: "agent.notifications") as? Bool ?? true,
               e.isPending() || (fresh && e.str("hook_event_name") == "Stop") { onNotify?(e) }
        }
        if notified.count > 1000 { notified = Set(notified.suffix(1000)) }
        persist()
        await refreshUsage(force: false)
    }

    public func refreshUsage(force: Bool) async {
        guard defaults.object(forKey: "agent.usageCollection") as? Bool ?? true, force || Date().timeIntervalSince(lastUsage) > 60 else { return }
        lastUsage = Date()
        for p in store.list() where enabled(p.id) {
            do {
                let out = try await RemoteCommand.run(profileID: p.id, store: store, command: config.command + "usage" + (force ? " --refresh" : ""), input: nil, timeout: 40)
                if let r = try JSONSerialization.jsonObject(with: out) as? JSON { usages[p.id] = r }
            } catch {
                if usages[p.id] != nil { usages[p.id]?["status"] = "offline" } else { usages[p.id] = ["status": "offline", "accounts": [JSON]()] }
            }
        }
        persist()
    }

    // MARK: configure / respond

    public var installCommand: String {
        config.installCommand ?? "umask 077; mkdir -p \"$HOME/.local/state/ghostty-android\" && cat > \"$HOME/.local/state/ghostty-android/ghostty_hook.py.new\" && mv \"$HOME/.local/state/ghostty-android/ghostty_hook.py.new\" \"$HOME/.local/state/ghostty-android/ghostty_hook.py\" && " + config.command + "install"
    }
    @discardableResult
    public func configure(profile: String, install: Bool) async -> Bool {
        do {
            let out = try await RemoteCommand.run(profileID: profile, store: store, command: install ? installCommand : config.command + "uninstall", input: install ? config.script : nil, timeout: 30)
            let ok = ((try JSONSerialization.jsonObject(with: out) as? JSON)?["ok"] as? Bool) == true
            if ok { defaults.set(install, forKey: "agent.host_" + profile) }
            await refresh(); return ok
        } catch { return false }
    }
    /// Answers a pending permission request or question. Expired or cached events are refused locally.
    public func respond(_ event: AgentEvent, behavior: String, answers: JSON? = nil) async -> Bool {
        guard event.isPending(), let id = event.data["id"] as? String else { return false }
        var body: JSON = ["id": id, "behavior": behavior]
        if let answers { body["answers"] = answers }
        do {
            let out = try await RemoteCommand.run(profileID: event.profile, store: store, command: config.command + "respond", input: try JSONSerialization.data(withJSONObject: body))
            let ok = ((try JSONSerialization.jsonObject(with: out) as? JSON)?["ok"] as? Bool) == true
            if ok, let i = events.firstIndex(where: { $0.id == event.id }) { events[i].data["status"] = "answered"; markRead(event); persist() }
            await refresh(); return ok
        } catch { return false }
    }

    // MARK: cache

    private func persist() {
        guard let url = cacheURL else { return }
        var bytes = 0; var rows = [JSON]()
        for e in events {
            var d = e.data
            if let s = try? JSONSerialization.data(withJSONObject: d), s.count > 65536 { d["tool_input"] = nil; d["payloadNotCached"] = true }
            let size = (try? JSONSerialization.data(withJSONObject: d).count) ?? 0
            if bytes + size > 1_000_000 { continue }
            bytes += size; rows.append(["profile": e.profile, "host": e.host, "data": d])
        }
        let value: JSON = ["events": rows, "usages": usages]
        if let data = try? JSONSerialization.data(withJSONObject: value) { try? data.write(to: url, options: [.atomic, .completeFileProtection]) }
    }
    private func loadCache() {
        guard let url = cacheURL, let d = try? Data(contentsOf: url), let v = try? JSONSerialization.jsonObject(with: d) as? JSON else { return }
        for row in (v["events"] as? [JSON] ?? []).prefix(400) {
            guard let profile = row["profile"] as? String, let host = row["host"] as? String, var data = row["data"] as? JSON, enabled(profile) else { continue }
            data["cached"] = true; let e = AgentEvent(profile: profile, host: host, data: data); events.append(e); notified.insert(e.id)
        }
        if let q = v["usages"] as? [String: JSON] { for (k, u) in q where enabled(k) { usages[k] = u } }
    }
}
