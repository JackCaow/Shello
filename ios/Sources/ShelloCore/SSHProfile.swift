import Foundation

public enum AuthMethod: String, Codable { case password, key }

/// A saved connection. Secrets live in the `SecretStore` (Keychain on device), never in the metadata file.
public struct SSHProfile: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var host: String
    public var port: Int
    public var user: String
    public var auth: AuthMethod
    public var tmux: Bool
    public var tmuxSession: String?

    public init(id: String = UUID().uuidString.lowercased(), name: String = "", host: String = "", port: Int = 22, user: String = "",
                auth: AuthMethod = .password, tmux: Bool = true, tmuxSession: String? = SessionTarget.fresh(tmux: true)) {
        self.id = id; self.name = name; self.host = host; self.port = port; self.user = user; self.auth = auth; self.tmux = tmux; self.tmuxSession = tmuxSession
    }
}

/// Secret material for a profile.
public struct SSHSecrets: Codable, Equatable {
    public var password = "", passphrase = ""
    public var privateKey = Data(), knownHosts = Data()
    public init(password: String = "", passphrase: String = "", privateKey: Data = Data(), knownHosts: Data = Data()) {
        self.password = password; self.passphrase = passphrase; self.privateKey = privateKey; self.knownHosts = knownHosts
    }
}

public enum ProfileValidation {
    /// Returns a user-facing error or nil. Mirrors the Android `Draft.validate()` rules.
    public static func validate(_ p: SSHProfile, _ s: SSHSecrets) -> String? {
        let h = p.host
        if h.isEmpty || h.count > 253 || h.rangeOfCharacter(from: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "/@\\"))) != nil || h.hasPrefix("-")
            || (h.contains(":") && h.range(of: "^[0-9a-fA-F:.%]+$", options: .regularExpression) == nil) { return "invalid_host" }
        if !(1...65535).contains(p.port) { return "invalid_port" }
        if p.user.isEmpty || p.user.rangeOfCharacter(from: CharacterSet.whitespacesAndNewlines.union(.controlCharacters)) != nil { return "invalid_user" }
        switch p.auth {
        case .password: if s.password.isEmpty { return "password_required" }
        case .key: if s.privateKey.isEmpty { return "key_required" }
        }
        return nil
    }
}

public protocol SecretStore {
    func read(_ key: String) throws -> Data?
    func write(_ key: String, _ value: Data) throws
    func delete(_ key: String) throws
}

public final class MemorySecretStore: SecretStore {
    private var items = [String: Data]()
    public init() {}
    public func read(_ key: String) throws -> Data? { items[key] }
    public func write(_ key: String, _ value: Data) throws { items[key] = value }
    public func delete(_ key: String) throws { items[key] = nil }
}

/// Profile metadata in a JSON file (Data Protection applied by the app); secrets in a `SecretStore`.
public final class ProfileStore {
    private let directory: URL
    private let secrets: SecretStore
    public init(directory: URL, secrets: SecretStore) throws {
        self.directory = directory; self.secrets = secrets
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    private func file(_ id: String) throws -> URL {
        guard id.range(of: "^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$", options: .regularExpression) != nil else { throw ShelloError.invalid("Invalid profile ID") }
        return directory.appendingPathComponent(id + ".json")
    }
    public func list() -> [SSHProfile] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return urls.filter { $0.pathExtension == "json" }.compactMap { try? JSONDecoder().decode(SSHProfile.self, from: Data(contentsOf: $0)) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
    public func save(_ profile: SSHProfile, secrets s: SSHSecrets) throws {
        if let e = ProfileValidation.validate(profile, s) { throw ShelloError.invalid(e) }
        guard !s.knownHosts.isEmpty else { throw ShelloError.invalid("Unverified profile") }
        let url = try file(profile.id)
        try secrets.write(profile.id, try JSONEncoder().encode(s))
        try JSONEncoder().encode(profile).write(to: url, options: [.atomic, .completeFileProtection])
    }
    public func read(_ id: String) throws -> (SSHProfile, SSHSecrets) {
        let url = try file(id)
        guard let data = try? Data(contentsOf: url), let p = try? JSONDecoder().decode(SSHProfile.self, from: data),
              let raw = try secrets.read(id), let s = try? JSONDecoder().decode(SSHSecrets.self, from: raw) else { throw ShelloError.failed("Profile missing") }
        return (p, s)
    }
    public func delete(_ id: String) throws {
        try secrets.delete(id)
        let url = try file(id)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}
