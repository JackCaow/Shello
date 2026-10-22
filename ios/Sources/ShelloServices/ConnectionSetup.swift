import Foundation
import ShelloCore
import ShelloSSH

/// Verifies a connection draft without opening a shell or sending terminal input (port of `SshConnectionSetup`).
public enum ConnectionSetup {
    public enum Outcome: Equatable {
        case verified
        case trustNeeded(HostKeyInfo)
        case failed(TerminalTab.Failure)
    }

    /// Runs on a background queue and returns the outcome. An existing pin is never replaced.
    public static func verify(profile: SSHProfile, secrets: SSHSecrets, timeout: TimeInterval = 10) async -> Outcome {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async { cont.resume(returning: verifySync(profile: profile, secrets: secrets, timeout: timeout)) }
        }
    }
    static func verifySync(profile: SSHProfile, secrets: SSHSecrets, timeout: TimeInterval) -> Outcome {
        let c = SSHConnection(host: profile.host, port: profile.port, user: profile.user)
        defer { c.disconnect() }
        do {
            let creds: SSHCredentials = profile.auth == .key
                ? .privateKey(secrets.privateKey, passphrase: secrets.passphrase.isEmpty ? nil : secrets.passphrase) : .password(secrets.password)
            try c.connect(credentials: creds, knownHosts: String(decoding: secrets.knownHosts, as: UTF8.self), timeout: timeout)
            if profile.tmux {
                let r = try c.run(SessionTarget.tmuxLookup + "\"$T\" -V", timeout: 5)
                guard r.status == 0, String(decoding: r.output, as: UTF8.self).hasPrefix("tmux ") else { return .failed(.tmuxUnavailable) }
            }
            return .verified
        } catch let e as SSHError {
            switch e {
            case .hostKeyUnknown(let i): return .trustNeeded(i)
            case .hostKeyMismatch: return .failed(.hostKeyMismatch)
            case .authentication: return .failed(.authentication)
            case .privateKey: return .failed(.privateKey)
            case .timeout: return .failed(.timeout)
            case .network, .closed: return .failed(.network)
            case .protocolError(let m): return .failed(.other(m))
            }
        } catch { return .failed(.other(error.localizedDescription)) }
    }

    /// Lists tmux sessions on the host (empty when the tmux server is not running).
    public static func listTmux(profileID: String, store: ProfileStore) async throws -> [TmuxSessions.Entry] {
        let out = try await RemoteCommand.run(profileID: profileID, store: store, command: TmuxSessions.command, input: nil, timeout: 12)
        return try TmuxSessions.parse(String(decoding: out, as: UTF8.self))
    }
}

/// Bounded auxiliary SSH commands on a fresh pinned connection (port of `SshCommand`).
public enum RemoteCommand {
    public static func run(profileID: String, store: ProfileStore, command: String, input: Data?, timeout: TimeInterval = 20, maxOutput: Int = 1_048_576) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let (p, s) = try store.read(profileID)
                    let c = SSHConnection(host: p.host, port: p.port, user: p.user); defer { c.disconnect() }
                    let creds: SSHCredentials = p.auth == .key
                        ? .privateKey(s.privateKey, passphrase: s.passphrase.isEmpty ? nil : s.passphrase) : .password(s.password)
                    try c.connect(credentials: creds, knownHosts: String(decoding: s.knownHosts, as: UTF8.self))
                    let r = try c.run(command, input: input, maxOutput: maxOutput, timeout: timeout)
                    guard r.status == 0 else { throw ShelloError.failed("Remote command failed (\(r.status))") }
                    cont.resume(returning: r.output)
                } catch { cont.resume(throwing: error) }
            }
        }
    }
}
