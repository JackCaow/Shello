import Foundation
import CSSH2
import Darwin

public struct HostKeyInfo: Equatable {
    public let type: String
    /// `"<type>\nSHA256:<base64>"`, the format Android shows in its trust prompt.
    public let fingerprint: String
    /// A known_hosts line to persist if the user trusts the key.
    public let knownHostsLine: String
}

public enum SSHError: Error, Equatable, LocalizedError {
    case network(String), hostKeyUnknown(HostKeyInfo), hostKeyMismatch, authentication, privateKey, timeout, protocolError(String), closed
    public var errorDescription: String? {
        switch self {
        case .network(let m): return "network: \(m)"
        case .hostKeyUnknown: return "unknown host key"
        case .hostKeyMismatch: return "host key mismatch"
        case .authentication: return "authentication failed"
        case .privateKey: return "cannot read private key"
        case .timeout: return "timed out"
        case .protocolError(let m): return m
        case .closed: return "closed"
        }
    }
    public var isNetworkFailure: Bool { if case .network = self { return true }; return self == .timeout || self == .closed }
}

public enum SSHCredentials {
    case password(String)
    case privateKey(Data, passphrase: String?)
}

private let eagain = Int32(LIBSSH2_ERROR_EAGAIN)

/// One SSH session over a TCP socket. Not thread-safe: use from one thread, or hand it to a `ShellChannel` pump.
public final class SSHConnection {
    public let host: String, port: Int, user: String
    var session: OpaquePointer?
    var socketFD: Int32 = -1
    private static let initOnce: Void = { _ = libssh2_init(0) }()

    public init(host: String, port: Int, user: String) {
        _ = SSHConnection.initOnce
        self.host = host; self.port = port; self.user = user
    }
    deinit { disconnect() }

    // MARK: connection

    public func connect(credentials: SSHCredentials, knownHosts: String, timeout: TimeInterval = 10) throws {
        let deadline = Date().addingTimeInterval(timeout)
        socketFD = try Self.openSocket(host: host, port: port, timeout: timeout)
        guard let s = libssh2_session_init_ex(nil, nil, nil, nil) else { throw SSHError.protocolError("session") }
        session = s
        libssh2_session_set_blocking(s, 0)
        libssh2_session_set_timeout(s, Int(timeout * 1000))
        // Prefer the algorithms of already-pinned keys so a server that offers several host keys is not reported as changed.
        let pinned = Self.pinnedAlgorithms(knownHosts)
        if !pinned.isEmpty { _ = libssh2_session_method_pref(s, Int32(LIBSSH2_METHOD_HOSTKEY), pinned.joined(separator: ",")) }
        try wait(deadline) { libssh2_session_handshake(s, self.socketFD) }
        try verifyHostKey(knownHosts)
        try authenticate(credentials, deadline: deadline)
        libssh2_keepalive_config(s, 1, 10)
    }

    public func disconnect() {
        if let s = session {
            libssh2_session_set_blocking(s, 0)
            _ = libssh2_session_disconnect_ex(s, SSH_DISCONNECT_BY_APPLICATION, "bye", "")
            libssh2_session_free(s)
            session = nil
        }
        if socketFD >= 0 { Darwin.close(socketFD); socketFD = -1 }
    }

    static func pinnedAlgorithms(_ knownHosts: String) -> [String] {
        var out = [String]()
        for line in knownHosts.split(separator: "\n") {
            let f = line.split(separator: " ")
            guard f.count >= 3 else { continue }
            let algs: [String]
            switch String(f[1]) {
            case "ssh-rsa": algs = ["rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"]
            case let t: algs = [t]
            }
            for a in algs where !out.contains(a) { out.append(a) }
        }
        return out
    }

    private func verifyHostKey(_ knownHosts: String) throws {
        guard let s = session else { throw SSHError.closed }
        var len = 0; var type: Int32 = 0
        guard let raw = libssh2_session_hostkey(s, &len, &type), len > 0 else { throw SSHError.protocolError("no host key") }
        let key = Data(bytes: raw, count: len)
        let typeName: String
        switch type {
        case LIBSSH2_HOSTKEY_TYPE_RSA: typeName = "ssh-rsa"
        case LIBSSH2_HOSTKEY_TYPE_ED25519: typeName = "ssh-ed25519"
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_256: typeName = "ecdsa-sha2-nistp256"
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_384: typeName = "ecdsa-sha2-nistp384"
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_521: typeName = "ecdsa-sha2-nistp521"
        default: throw SSHError.protocolError("unsupported host key")
        }
        let b64 = key.base64EncodedString()
        let hostField = port == 22 ? host : "[\(host)]:\(port)"
        // known_hosts is compared as text so that a pin recorded by the pairing flow behaves as on Android: exact key or reject.
        var sameHostLines = [(String, String)]()
        for line in knownHosts.split(separator: "\n") {
            let f = line.split(separator: " ")
            if f.count >= 3, String(f[0]) == hostField { sameHostLines.append((String(f[1]), String(f[2]))) }
        }
        if sameHostLines.contains(where: { $0.0 == typeName && $0.1 == b64 }) { return }
        if !sameHostLines.isEmpty { throw SSHError.hostKeyMismatch }
        if !knownHosts.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw SSHError.hostKeyMismatch }
        let digest = SHA256Digest.hash(key).base64EncodedString().replacingOccurrences(of: "=", with: "")
        throw SSHError.hostKeyUnknown(HostKeyInfo(type: typeName, fingerprint: "\(typeName)\nSHA256:\(digest)", knownHostsLine: "\(hostField) \(typeName) \(b64)\n"))
    }

    private func authenticate(_ c: SSHCredentials, deadline: Date) throws {
        guard let s = session else { throw SSHError.closed }
        switch c {
        case .password(let p):
            do {
                try wait(deadline) { libssh2_userauth_password_ex(s, self.user, UInt32(self.user.utf8.count), p, UInt32(p.utf8.count), nil) }
            } catch SSHError.protocolError {
                throw SSHError.authentication
            }
        case .privateKey(let key, let pass):
            do {
                try key.withUnsafeBytes { raw in
                    let ptr = raw.bindMemory(to: CChar.self).baseAddress
                    try wait(deadline) {
                        libssh2_userauth_publickey_frommemory(s, self.user, self.user.utf8.count, nil, 0, ptr, key.count, pass)
                    }
                }
            } catch SSHError.protocolError(let m) {
                throw m == "privkey" ? SSHError.privateKey : SSHError.authentication
            }
        }
    }

    // MARK: I/O helpers

    /// Runs a libssh2 call until it stops returning EAGAIN, waiting on the socket. Negative results throw.
    @discardableResult
    func wait(_ deadline: Date, _ call: () -> Int32) throws -> Int32 {
        while true {
            let r = call()
            if r != eagain {
                if r < 0 { throw mapError(r) }
                return r
            }
            try waitSocket(deadline)
        }
    }
    func waitSocket(_ deadline: Date, milliseconds: Int32? = nil) throws {
        guard let s = session else { throw SSHError.closed }
        let remaining = deadline.timeIntervalSinceNow
        if remaining <= 0 { throw SSHError.timeout }
        let dir = libssh2_session_block_directions(s)
        var pfd = pollfd(fd: socketFD, events: 0, revents: 0)
        if dir & LIBSSH2_SESSION_BLOCK_INBOUND != 0 { pfd.events |= Int16(POLLIN) }
        if dir & LIBSSH2_SESSION_BLOCK_OUTBOUND != 0 { pfd.events |= Int16(POLLOUT) }
        _ = poll(&pfd, 1, milliseconds ?? Int32(min(remaining * 1000, 1000)))
    }
    func mapError(_ code: Int32) -> SSHError {
        switch code {
        case Int32(LIBSSH2_ERROR_TIMEOUT): return .timeout
        case Int32(LIBSSH2_ERROR_SOCKET_DISCONNECT), Int32(LIBSSH2_ERROR_SOCKET_SEND), Int32(LIBSSH2_ERROR_SOCKET_RECV), Int32(LIBSSH2_ERROR_SOCKET_TIMEOUT): return .network("socket \(code)")
        case Int32(LIBSSH2_ERROR_AUTHENTICATION_FAILED), Int32(LIBSSH2_ERROR_PUBLICKEY_UNVERIFIED), Int32(LIBSSH2_ERROR_PUBLICKEY_UNRECOGNIZED): return .protocolError("auth")
        case Int32(LIBSSH2_ERROR_FILE), Int32(LIBSSH2_ERROR_KEYFILE_AUTH_FAILED): return .protocolError("privkey")
        default:
            var msg: UnsafeMutablePointer<CChar>?; var l: Int32 = 0
            if let s = session { _ = libssh2_session_last_error(s, &msg, &l, 0) }
            return .protocolError(msg.map { String(cString: $0) } ?? "ssh error \(code)")
        }
    }

    public static func openSocket(host: String, port: Int, timeout: TimeInterval) throws -> Int32 {
        var hints = addrinfo(); hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM
        var res: UnsafeMutablePointer<addrinfo>?
        let rc = getaddrinfo(host, String(port), &hints, &res)
        guard rc == 0, let first = res else { throw SSHError.network("resolve: \(String(cString: gai_strerror(rc)))") }
        defer { freeaddrinfo(res) }
        var lastError = "connect failed"
        var p: UnsafeMutablePointer<addrinfo>? = first
        while let ai = p {
            let fd = socket(ai.pointee.ai_family, ai.pointee.ai_socktype, ai.pointee.ai_protocol)
            if fd >= 0 {
                var one: Int32 = 1
                setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
                setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
                let flags = fcntl(fd, F_GETFL, 0); _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
                let c = Darwin.connect(fd, ai.pointee.ai_addr, ai.pointee.ai_addrlen)
                if c == 0 { return fd }
                if errno == EINPROGRESS {
                    var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                    if poll(&pfd, 1, Int32(timeout * 1000)) > 0 {
                        var err: Int32 = 0; var len = socklen_t(MemoryLayout<Int32>.size)
                        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len)
                        if err == 0 { return fd }
                        lastError = String(cString: strerror(err))
                    } else { lastError = "timed out" }
                } else { lastError = String(cString: strerror(errno)) }
                Darwin.close(fd)
            }
            p = ai.pointee.ai_next
        }
        throw SSHError.network(lastError)
    }

    // MARK: exec

    /// Runs a bounded command on its own channel and returns stdout. stderr is discarded (bounded).
    public func run(_ command: String, input: Data? = nil, maxOutput: Int = 1_048_576, timeout: TimeInterval = 20) throws -> (output: Data, status: Int32) {
        guard let s = session else { throw SSHError.closed }
        let deadline = Date().addingTimeInterval(timeout)
        var channel: OpaquePointer?
        while channel == nil {
            channel = libssh2_channel_open_ex(s, "session", 7, SSHConstants.window, SSHConstants.packet, nil, 0)
            if channel == nil {
                if libssh2_session_last_errno(s) != eagain { throw SSHError.protocolError("open channel") }
                try waitSocket(deadline)
            }
        }
        guard let ch = channel else { throw SSHError.closed }
        defer { libssh2_channel_free(ch) }
        try wait(deadline) { libssh2_channel_process_startup(ch, "exec", 4, command, UInt32(command.utf8.count)) }
        if let input, !input.isEmpty {
            var sent = 0
            try input.withUnsafeBytes { raw in
                let base = raw.bindMemory(to: CChar.self).baseAddress!
                while sent < input.count {
                    let n = libssh2_channel_write_ex(ch, 0, base + sent, input.count - sent)
                    if n == Int(eagain) { try waitSocket(deadline); continue }
                    if n < 0 { throw mapError(Int32(n)) }
                    sent += n
                }
            }
        }
        try wait(deadline) { libssh2_channel_send_eof(ch) }
        var out = Data(); var buf = [CChar](repeating: 0, count: 16384); var errDiscarded = 0
        while true {
            var progressed = false
            let n = libssh2_channel_read_ex(ch, 0, &buf, buf.count)
            if n > 0 { if out.count + n > maxOutput { throw SSHError.protocolError("response too large") }; out.append(Data(bytes: buf, count: n)); progressed = true }
            else if n < 0 && n != Int(eagain) { throw mapError(Int32(n)) }
            let e = libssh2_channel_read_ex(ch, 1, &buf, buf.count)
            if e > 0 { errDiscarded += e; if errDiscarded > maxOutput { throw SSHError.protocolError("error response too large") }; progressed = true }
            if libssh2_channel_eof(ch) != 0 && n <= 0 && e <= 0 { break }
            if !progressed { try waitSocket(deadline) }
            if Date() > deadline { throw SSHError.timeout }
        }
        try wait(deadline) { libssh2_channel_close(ch) }
        return (out, Int32(libssh2_channel_get_exit_status(ch)))
    }

    // MARK: SFTP upload

    /// Uploads `data` to `path` (mode 0600 by default) over SFTP.
    public func upload(_ data: Data, to path: String, mode: Int = 0o600, timeout: TimeInterval = 60) throws {
        guard let s = session else { throw SSHError.closed }
        let deadline = Date().addingTimeInterval(timeout)
        var sftp: OpaquePointer?
        while sftp == nil {
            sftp = libssh2_sftp_init(s)
            if sftp == nil { if libssh2_session_last_errno(s) != eagain { throw SSHError.protocolError("sftp") }; try waitSocket(deadline) }
        }
        defer { while let sf = sftp, libssh2_sftp_shutdown(sf) == eagain { try? waitSocket(deadline) } }
        var handle: OpaquePointer?
        let flags = UInt(LIBSSH2_FXF_WRITE | LIBSSH2_FXF_CREAT | LIBSSH2_FXF_TRUNC)
        while handle == nil {
            handle = libssh2_sftp_open_ex(sftp, path, UInt32(path.utf8.count), flags, mode, Int32(LIBSSH2_SFTP_OPENFILE))
            if handle == nil { if libssh2_session_last_errno(s) != eagain { throw SSHError.protocolError("sftp open") }; try waitSocket(deadline) }
        }
        defer { while let h = handle, libssh2_sftp_close_handle(h) == eagain { try? waitSocket(deadline) } }
        var sent = 0
        try data.withUnsafeBytes { raw in
            let base = raw.bindMemory(to: CChar.self).baseAddress
            while sent < data.count {
                let n = libssh2_sftp_write(handle, base! + sent, min(32768, data.count - sent))
                if n == Int(eagain) { try waitSocket(deadline); continue }
                if n < 0 { throw SSHError.protocolError("sftp write \(n)") }
                sent += n
            }
        }
    }
}

enum SSHConstants {
    // LIBSSH2_CHANNEL_WINDOW_DEFAULT / LIBSSH2_CHANNEL_PACKET_DEFAULT are expression macros Swift cannot import.
    static let window: UInt32 = 2 * 1024 * 1024
    static let packet: UInt32 = 32768
}
