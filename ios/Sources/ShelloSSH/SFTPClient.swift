import Foundation
import CSSH2

/// Minimal blocking SFTP client over an authenticated `SSHConnection` (one thread at a time).
public final class SFTPClient {
    public struct Stat { public let exists: Bool, isDirectory: Bool, isLink: Bool, size: UInt64 }
    private let connection: SSHConnection
    private var sftp: OpaquePointer?
    private let eagain = Int32(LIBSSH2_ERROR_EAGAIN)
    private var deadline: Date { Date().addingTimeInterval(timeout) }
    public var timeout: TimeInterval = 15

    public init(connection: SSHConnection) throws {
        guard let s = connection.session else { throw SSHError.closed }
        self.connection = connection
        let d = Date().addingTimeInterval(15)
        while sftp == nil {
            sftp = libssh2_sftp_init(s)
            if sftp == nil { if libssh2_session_last_errno(s) != eagain { throw SSHError.protocolError("sftp") }; try connection.waitSocket(d) }
        }
    }
    deinit { close() }
    public func close() {
        guard let s = sftp else { return }
        let d = Date().addingTimeInterval(3)
        while libssh2_sftp_shutdown(s) == eagain, d > Date() { try? connection.waitSocket(d) }
        sftp = nil
    }
    private func live() throws -> OpaquePointer { guard let s = sftp else { throw SSHError.closed }; return s }

    public func realpath(_ path: String) throws -> String {
        let s = try live(); var buf = [CChar](repeating: 0, count: 4096); let d = deadline
        let n = try connection.wait(d) { libssh2_sftp_symlink_ex(s, path, UInt32(path.utf8.count), &buf, UInt32(buf.count), LIBSSH2_SFTP_REALPATH) }
        return String(decoding: buf.prefix(Int(n)).map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }
    public func lstat(_ path: String) throws -> Stat {
        let s = try live(); var attrs = LIBSSH2_SFTP_ATTRIBUTES(); let d = deadline
        while true {
            let r = libssh2_sftp_stat_ex(s, path, UInt32(path.utf8.count), LIBSSH2_SFTP_LSTAT, &attrs)
            if r == eagain { try connection.waitSocket(d); continue }
            if r < 0 {
                if libssh2_sftp_last_error(s) == UInt(LIBSSH2_FX_NO_SUCH_FILE) { return Stat(exists: false, isDirectory: false, isLink: false, size: 0) }
                throw SSHError.protocolError("sftp stat")
            }
            let type = UInt(attrs.permissions) & 0o170000
            return Stat(exists: true, isDirectory: type == 0o040000, isLink: type == 0o120000, size: UInt64(attrs.filesize))
        }
    }
    public func mkdir(_ path: String, mode: Int = 0o700) throws {
        let s = try live(); let d = deadline
        try connection.wait(d) { libssh2_sftp_mkdir_ex(s, path, UInt32(path.utf8.count), mode) }
        try chmod(path, mode: mode)
    }
    public func chmod(_ path: String, mode: Int) throws {
        let s = try live(); var attrs = LIBSSH2_SFTP_ATTRIBUTES(); attrs.flags = UInt(LIBSSH2_SFTP_ATTR_PERMISSIONS); attrs.permissions = UInt(mode); let d = deadline
        try connection.wait(d) { libssh2_sftp_stat_ex(s, path, UInt32(path.utf8.count), LIBSSH2_SFTP_SETSTAT, &attrs) }
    }
    public func rename(_ from: String, to: String) throws {
        let s = try live(); let d = deadline
        try connection.wait(d) { libssh2_sftp_rename_ex(s, from, UInt32(from.utf8.count), to, UInt32(to.utf8.count), Int(LIBSSH2_SFTP_RENAME_OVERWRITE | LIBSSH2_SFTP_RENAME_ATOMIC | LIBSSH2_SFTP_RENAME_NATIVE)) }
    }
    public func remove(_ path: String) throws { let s = try live(); let d = deadline; try connection.wait(d) { libssh2_sftp_unlink_ex(s, path, UInt32(path.utf8.count)) } }
    public func rmdir(_ path: String) throws { let s = try live(); let d = deadline; try connection.wait(d) { libssh2_sftp_rmdir_ex(s, path, UInt32(path.utf8.count)) } }

    public final class File {
        fileprivate var handle: OpaquePointer?
        fileprivate let client: SFTPClient
        init(_ h: OpaquePointer, _ c: SFTPClient) { handle = h; client = c }
        public func write(_ data: Data, deadline: Date) throws {
            guard let h = handle else { throw SSHError.closed }
            var sent = 0
            try data.withUnsafeBytes { raw in
                let base = raw.bindMemory(to: CChar.self).baseAddress
                while sent < data.count {
                    let n = libssh2_sftp_write(h, base! + sent, min(32768, data.count - sent))
                    if n == Int(LIBSSH2_ERROR_EAGAIN) { try client.connection.waitSocket(deadline); continue }
                    if n < 0 { throw SSHError.protocolError("sftp write \(n)") }
                    sent += n
                }
            }
        }
        public func close() {
            guard let h = handle else { return }
            let d = Date().addingTimeInterval(5)
            while libssh2_sftp_close_handle(h) == Int32(LIBSSH2_ERROR_EAGAIN), d > Date() { try? client.connection.waitSocket(d) }
            handle = nil
        }
        deinit { close() }
    }
    /// Creates (or truncates) a file. The mode applies at creation.
    public func create(_ path: String, mode: Int = 0o600) throws -> File {
        let s = try live(); let d = deadline
        let flags = UInt(LIBSSH2_FXF_WRITE | LIBSSH2_FXF_CREAT | LIBSSH2_FXF_TRUNC)
        while true {
            if let h = libssh2_sftp_open_ex(s, path, UInt32(path.utf8.count), flags, mode, Int32(LIBSSH2_SFTP_OPENFILE)) { return File(h, self) }
            guard let sess = connection.session, libssh2_session_last_errno(sess) == eagain else { throw SSHError.protocolError("sftp open") }
            try connection.waitSocket(d)
        }
    }
}
