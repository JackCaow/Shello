import Foundation
import ShelloCore
import ShelloSSH

/// One bounded streaming SFTP upload into a private random directory under `~/.local/share/shello/attachments`.
/// The file only appears under its final name after the complete stream arrived.
@MainActor
public final class AttachmentTransfer: ObservableObject, Identifiable {
    nonisolated public static let maxBytes: Int64 = 50 * 1024 * 1024
    public enum Failure: Error, Equatable { case tooLarge, cancelled, unreadable, connection(TerminalTab.Failure), remote(String) }
    public enum State: Equatable { case uploading, done(path: String), failed(Failure) }

    public let id = UUID()
    public let source: URL
    public private(set) var name: String
    @Published public private(set) var state = State.uploading
    @Published public private(set) var bytes: Int64 = 0
    public private(set) var total: Int64 = 0
    private let profileID: String, store: ProfileStore
    private var cancelled = false
    private var task: Task<Void, Never>?
    /// Optional cleanup of a temporary source (camera / picker copies).
    public var deleteSourceWhenDone = false

    public init(profileID: String, store: ProfileStore, source: URL, displayName: String? = nil) {
        self.profileID = profileID; self.store = store; self.source = source
        name = Self.safeName(displayName ?? source.lastPathComponent)
    }
    public var isBusy: Bool { state == .uploading }
    public var remotePath: String? { if case .done(let p) = state { return p }; return nil }

    public func start() {
        task = Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            let result = await self.run()
            await MainActor.run { self.state = result; if self.deleteSourceWhenDone { try? FileManager.default.removeItem(at: self.source) } }
        }
    }
    public func cancel() { cancelled = true; task?.cancel() }
    fileprivate func progress(_ n: Int64) { bytes = n }
    fileprivate var isCancelled: Bool { cancelled || Task.isCancelled }

    private nonisolated func run() async -> State {
        let (source, name, profileID, store) = await (self.source, self.name, self.profileID, self.store)
        let scoped = source.startAccessingSecurityScopedResource(); defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        guard let size = (try? source.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init) else { return .failed(.unreadable) }
        if size > Self.maxBytes { return .failed(.tooLarge) }
        await MainActor.run { self.total = size }
        guard let input = try? FileHandle(forReadingFrom: source) else { return .failed(.unreadable) }
        defer { try? input.close() }
        var folder: String?, partial: String?, complete = false
        let conn: SSHConnection
        do {
            let (p, s) = try store.read(profileID)
            conn = SSHConnection(host: p.host, port: p.port, user: p.user)
            let creds: SSHCredentials = p.auth == .key ? .privateKey(s.privateKey, passphrase: s.passphrase.isEmpty ? nil : s.passphrase) : .password(s.password)
            try conn.connect(credentials: creds, knownHosts: String(decoding: s.knownHosts, as: UTF8.self))
        } catch let e as SSHError {
            return .failed(.connection(Self.map(e)))
        } catch { return .failed(.remote(error.localizedDescription)) }
        defer { conn.disconnect() }
        var sftp: SFTPClient?
        do {
            let client = try SFTPClient(connection: conn); sftp = client
            let home = try client.realpath(".")
            guard home.hasPrefix("/"), !home.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { throw ShelloError.failed("Invalid remote home") }
            var base = home
            for part in [".local", "share", "shello", "attachments"] {
                base += "/" + part
                let st = try client.lstat(base)
                if !st.exists { try client.mkdir(base) }
                else if !st.isDirectory || st.isLink { throw ShelloError.failed("Upload directory is not a directory") }
            }
            let dir = base + "/" + UUID().uuidString.lowercased(); folder = dir
            try client.mkdir(dir)
            let part = dir + "/.upload-part", destination = dir + "/" + name; partial = part
            let file = try client.create(part, mode: 0o600)
            var sent: Int64 = 0; let deadline = Date().addingTimeInterval(120); var lastTick = Date.distantPast
            while true {
                if await self.isCancelled { throw SSHError.closed }
                guard let chunk = try input.read(upToCount: 32768), !chunk.isEmpty else { break }
                sent += Int64(chunk.count)
                if sent > Self.maxBytes { file.close(); throw TooLarge() }
                if Date() > deadline { throw SSHError.timeout }
                try file.write(chunk, deadline: deadline)
                if Date().timeIntervalSince(lastTick) > 0.15 { lastTick = Date(); let s = sent; await MainActor.run { self.progress(s) } }
            }
            file.close()
            try client.rename(part, to: destination); complete = true
            let s = sent; await MainActor.run { self.progress(s) }
            client.close()
            return .done(path: destination)
        } catch is TooLarge { cleanup(sftp, folder, partial, complete); return .failed(.tooLarge) }
        catch let e as SSHError {
            cleanup(sftp, folder, partial, complete)
            let wasCancelled = await self.isCancelled
            return .failed(e == .closed && wasCancelled ? .cancelled : .connection(Self.map(e)))
        }
        catch { cleanup(sftp, folder, partial, complete); return .failed(.remote(error.localizedDescription)) }
    }
    private struct TooLarge: Error {}
    private nonisolated func cleanup(_ sftp: SFTPClient?, _ folder: String?, _ partial: String?, _ complete: Bool) {
        guard !complete, let sftp else { return }
        if let p = partial { try? sftp.remove(p) }
        if let f = folder { try? sftp.rmdir(f) }
    }
    nonisolated static func map(_ e: SSHError) -> TerminalTab.Failure {
        switch e {
        case .hostKeyMismatch, .hostKeyUnknown: return .hostKeyMismatch
        case .authentication: return .authentication
        case .privateKey: return .privateKey
        case .timeout: return .timeout
        case .network, .closed: return .network
        case .protocolError(let m): return .other(m)
        }
    }

    nonisolated public static func safeName(_ value: String?) -> String {
        var clean = (value ?? "").unicodeScalars.map { CharacterSet.controlCharacters.contains($0) || $0 == "/" || $0 == "\\" || $0 == "\u{2028}" || $0 == "\u{2029}" ? "_" : String($0) }.joined()
        for c in ["?", "*", "[", "]"] { clean = clean.replacingOccurrences(of: c, with: "_") }
        if clean.trimmingCharacters(in: .whitespaces).isEmpty || clean == "." || clean == ".." || clean == ".upload-part" { clean = "attachment" }
        var out = "", length = 0
        for ch in clean { let n = String(ch).utf8.count; if length + n > 180 { break }; out.append(ch); length += n }
        return out
    }
}
