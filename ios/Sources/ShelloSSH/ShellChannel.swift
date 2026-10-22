import Foundation
import CSSH2
import Darwin

/// Interactive PTY channel. A dedicated thread owns the libssh2 session: it reads output, writes queued input,
/// applies resizes and sends keepalives. Callbacks are delivered on `callbackQueue` (main by default).
public final class ShellChannel {
    public var onOutput: ((Data) -> Void)?
    public var onClose: ((SSHError?) -> Void)?
    private let connection: SSHConnection
    private var channel: OpaquePointer?
    private let lock = NSLock()
    private var pending = Data()
    private var resizeTo: (Int, Int)?
    private var stopRequested = false
    private var thread: Thread?
    private var wake = [Int32](repeating: -1, count: 2)
    private let callbackQueue: DispatchQueue
    /// Bounds undelivered output so a fast PTY cannot enqueue unlimited UI work.
    private let slots = DispatchSemaphore(value: 4)
    public static let maxPendingInput = 1 << 20

    /// Opens a PTY and either a login shell (`command == nil`) or an exec command.
    public init(connection: SSHConnection, command: String?, columns: Int, rows: Int, term: String = "xterm-256color", callbackQueue: DispatchQueue = .main) throws {
        guard let s = connection.session else { throw SSHError.closed }
        self.connection = connection; self.callbackQueue = callbackQueue
        let deadline = Date().addingTimeInterval(10)
        var ch: OpaquePointer?
        while ch == nil {
            ch = libssh2_channel_open_ex(s, "session", 7, SSHConstants.window, SSHConstants.packet, nil, 0)
            if ch == nil { if libssh2_session_last_errno(s) != Int32(LIBSSH2_ERROR_EAGAIN) { throw SSHError.protocolError("open channel") }; try connection.waitSocket(deadline) }
        }
        channel = ch
        let c = ch!
        // Ask for a UTF-8 locale so the remote line editor echoes non-ASCII input correctly; servers may refuse (ignored).
        for (name, value) in [("LANG", "en_US.UTF-8"), ("LC_CTYPE", "en_US.UTF-8")] {
            let d = Date().addingTimeInterval(2)
            while Date() < d {
                let r = libssh2_channel_setenv_ex(c, name, UInt32(name.utf8.count), value, UInt32(value.utf8.count))
                if r == Int32(LIBSSH2_ERROR_EAGAIN) { try? connection.waitSocket(d); continue }
                break
            }
        }
        try connection.wait(deadline) { libssh2_channel_request_pty_ex(c, term, UInt32(term.utf8.count), nil, 0, Int32(columns), Int32(rows), 0, 0) }
        if let command {
            try connection.wait(deadline) { libssh2_channel_process_startup(c, "exec", 4, command, UInt32(command.utf8.count)) }
        } else {
            try connection.wait(deadline) { libssh2_channel_process_startup(c, "shell", 5, nil, 0) }
        }
        pipe(&wake)
        for fd in wake { _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK) }
    }
    deinit { for fd in wake where fd >= 0 { Darwin.close(fd) } }

    public func start() {
        let t = Thread { [self] in pump() }
        t.name = "shello-ssh-pump"; t.qualityOfService = .userInitiated
        thread = t; t.start()
    }

    /// Queues input. Returns false when the bounded queue is full (caller should surface a failure).
    @discardableResult
    public func write(_ data: Data) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if stopRequested { return false }
        if pending.count + data.count > Self.maxPendingInput { return false }
        pending.append(data)
        kick()
        return true
    }
    public func resize(columns: Int, rows: Int) {
        lock.lock(); resizeTo = (columns, rows); lock.unlock(); kick()
    }
    public func close() {
        lock.lock(); stopRequested = true; lock.unlock(); kick()
    }
    private func kick() { var b: UInt8 = 1; _ = Darwin.write(wake[1], &b, 1) }

    private func pump() {
        guard let s = connection.session, let ch = channel else { return }
        var buf = [CChar](repeating: 0, count: 32768)
        var failure: SSHError?
        var lastKeepalive = Date()
        loop: while true {
            lock.lock()
            let stop = stopRequested
            var toSend = pending; pending = Data()
            let size = resizeTo; resizeTo = nil
            lock.unlock()
            if stop { break }
            if let (c, r) = size {
                var rc: Int32
                repeat { rc = libssh2_channel_request_pty_size_ex(ch, Int32(c), Int32(r), 0, 0) } while rc == Int32(LIBSSH2_ERROR_EAGAIN) && waitBrief()
            }
            var progressed = false
            // Writes: keep unsent remainder at the front of the queue.
            while !toSend.isEmpty {
                let n = toSend.withUnsafeBytes { libssh2_channel_write_ex(ch, 0, $0.bindMemory(to: CChar.self).baseAddress, toSend.count) }
                if n == Int(LIBSSH2_ERROR_EAGAIN) { break }
                if n < 0 { failure = connection.mapError(Int32(n)); break loop }
                toSend.removeFirst(n); progressed = true
            }
            if !toSend.isEmpty { lock.lock(); pending = toSend + pending; lock.unlock() }
            // Reads
            var gotOutput = false
            while true {
                let n = libssh2_channel_read_ex(ch, 0, &buf, buf.count)
                if n > 0 { deliver(Data(bytes: buf, count: n)); gotOutput = true; progressed = true; continue }
                if n == Int(LIBSSH2_ERROR_EAGAIN) || n == 0 { break }
                failure = connection.mapError(Int32(n)); break loop
            }
            while true { // merge stderr into the terminal like a PTY does
                let n = libssh2_channel_read_ex(ch, 1, &buf, buf.count)
                if n > 0 { deliver(Data(bytes: buf, count: n)); gotOutput = true; continue }
                break
            }
            if libssh2_channel_eof(ch) != 0 && !gotOutput { break }
            if Date().timeIntervalSince(lastKeepalive) > 5 {
                var next: Int32 = 0
                let rc = libssh2_keepalive_send(s, &next)
                if rc < 0 && rc != Int32(LIBSSH2_ERROR_EAGAIN) { failure = connection.mapError(rc); break }
                lastKeepalive = Date()
            }
            if !progressed { pollOnce(s) }
        }
        _ = libssh2_channel_close(ch)
        libssh2_channel_free(ch); channel = nil
        connection.disconnect()
        let err = failure
        callbackQueue.async { [weak self] in self?.onClose?(err) }
    }
    private func waitBrief() -> Bool { usleep(5000); return true }
    private func pollOnce(_ s: OpaquePointer) {
        var fds = [pollfd(fd: connection.socketFD, events: Int16(POLLIN), revents: 0), pollfd(fd: wake[0], events: Int16(POLLIN), revents: 0)]
        let dir = libssh2_session_block_directions(s)
        if dir & LIBSSH2_SESSION_BLOCK_OUTBOUND != 0 { fds[0].events |= Int16(POLLOUT) }
        _ = poll(&fds, 2, 1000)
        if fds[1].revents != 0 { var junk = [UInt8](repeating: 0, count: 64); while Darwin.read(wake[0], &junk, 64) > 0 {} }
    }
    private func deliver(_ d: Data) {
        slots.wait()
        callbackQueue.async { [weak self] in
            self?.onOutput?(d)
            self?.slots.signal()
        }
    }
}
