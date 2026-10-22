import Foundation
import Network
import Darwin
import ShelloCore
import ShelloSSH

/// Foreground-only SSH discovery. No authentication, credentials or terminal traffic: it only reads an SSH identification banner.
public enum Discovery {
    public struct Host: Equatable, Hashable, Identifiable {
        public enum Source: String { case lan, tailscale, bonjour }
        public let address: String, name: String, source: Source
        public var id: String { address }
        public init(address: String, name: String, source: Source) { self.address = address; self.name = name; self.source = source }
    }
    public static let maxPeers = 128

    // MARK: addresses

    public static func number(_ a: [Int]) -> UInt32 { UInt32(a[0]) << 24 | UInt32(a[1]) << 16 | UInt32(a[2]) << 8 | UInt32(a[3]) }
    public static func dotted(_ n: UInt32) -> String { "\(n >> 24).\((n >> 16) & 255).\((n >> 8) & 255).\(n & 255)" }
    /// Never scans outside the assigned subnet and caps broad networks at one /24 around the device.
    public static func targets(local: String, prefix: Int) -> [String] {
        guard let a = NetworkAddress.ipv4(local), (0...32).contains(prefix) else { return [] }
        let p = max(prefix, 24); let ip = number(a)
        let mask: UInt32 = p == 0 ? 0 : UInt32.max << UInt32(32 - p)
        let base = ip & mask, end = base | ~mask
        let first = p >= 31 ? base : base + 1, last = p >= 31 ? end : end - 1
        guard first <= last else { return [] }
        return (first...last).filter { $0 != ip }.map(dotted)
    }
    /// IPv4 address and prefix of the Wi-Fi interface (en0), if connected.
    public static func wifiAddress() -> (address: String, prefix: Int)? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let i = p {
            defer { p = i.pointee.ifa_next }
            guard let addr = i.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET), String(cString: i.pointee.ifa_name) == "en0",
                  let mask = i.pointee.ifa_netmask else { continue }
            var ip = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            let m = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &ip, &buf, socklen_t(INET_ADDRSTRLEN))
            return (String(cString: buf), m.nonzeroBitCount)
        }
        return nil
    }

    // MARK: probing

    /// Connects and requires an `SSH-` identification banner, so arbitrary open ports are never listed.
    public static func probe(host: String, port: Int = 22, connectTimeout: TimeInterval = 0.9, bannerTimeout: TimeInterval = 1.5) -> String? {
        guard let fd = try? SSHConnection.openSocket(host: host, port: port, timeout: connectTimeout) else { return nil }
        defer { close(fd) }
        var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        guard poll(&pfd, 1, Int32(bannerTimeout * 1000)) > 0 else { return nil }
        var buf = [UInt8](repeating: 0, count: 255)
        let n = read(fd, &buf, buf.count)
        guard n >= 4, String(decoding: buf.prefix(n), as: UTF8.self).hasPrefix("SSH-") else { return nil }
        return String(decoding: buf.prefix(n), as: UTF8.self).split(separator: "\r\n", omittingEmptySubsequences: true).first.map(String.init)
    }
    /// Reverse DNS with a short wait; falls back to the address.
    public static func name(for address: String) -> String {
        var sa = sockaddr_in(); sa.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); sa.sin_family = sa_family_t(AF_INET)
        inet_pton(AF_INET, address, &sa.sin_addr)
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let r = withUnsafePointer(to: &sa) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getnameinfo($0, socklen_t(MemoryLayout<sockaddr_in>.size), &host, socklen_t(host.count), nil, 0, NI_NAMEREQD) } }
        guard r == 0 else { return address }
        let n = String(cString: host); return n.hasSuffix(".local") ? String(n.dropLast(6)) : n
    }

    /// Scans `addresses` with bounded concurrency; results arrive as they are found. Cancel the task to stop.
    public static func scan(addresses: [String], port: Int = 22, source: Host.Source = .lan, workers: Int = 16,
                            connectTimeout: TimeInterval = 0.6, resolveNames: Bool = true) -> AsyncStream<Host> {
        AsyncStream { cont in
            let task = Task.detached {
                await withTaskGroup(of: Host?.self) { group in
                    var iterator = addresses.makeIterator(); var running = 0
                    func next() -> Bool {
                        guard let a = iterator.next() else { return false }
                        group.addTask {
                            if Task.isCancelled { return nil }
                            guard probe(host: a, port: port, connectTimeout: connectTimeout) != nil else { return nil }
                            return Host(address: a, name: resolveNames ? name(for: a) : a, source: source)
                        }
                        return true
                    }
                    while running < workers, next() { running += 1 }
                    for await result in group {
                        if let h = result { cont.yield(h) }
                        if !Task.isCancelled, next() {} else { running -= 1 }
                    }
                }
                cont.finish()
            }
            cont.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: Bonjour

    /// Browses `_ssh._tcp` services until cancelled. Requires the local-network permission (Info.plist `NSBonjourServices`).
    public static func bonjour() -> AsyncStream<Host> {
        AsyncStream { cont in
            let browser = NWBrowser(for: .bonjour(type: "_ssh._tcp", domain: nil), using: .tcp)
            var seen = Set<String>()
            browser.browseResultsChangedHandler = { results, _ in
                for r in results {
                    guard case let .service(name, _, _, _) = r.endpoint, seen.insert(name).inserted else { continue }
                    let params = NWParameters.tcp; (params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4   // SSH targets here are IPv4 only
                    let conn = NWConnection(to: r.endpoint, using: params)
                    conn.stateUpdateHandler = { state in
                        if case .ready = state, case let .hostPort(host, _)? = conn.currentPath?.remoteEndpoint {
                            var address = "\(host)"; if let i = address.firstIndex(of: "%") { address = String(address[..<i]) }
                            if NetworkAddress.ipv4(address) != nil { cont.yield(Host(address: address, name: name, source: .bonjour)) }
                            conn.cancel()
                        } else if case .failed = state { conn.cancel() }
                    }
                    conn.start(queue: .global())
                    DispatchQueue.global().asyncAfter(deadline: .now() + 5) { conn.cancel() }
                }
            }
            browser.stateUpdateHandler = { if case .failed = $0 { cont.finish() } }
            browser.start(queue: .global())
            cont.onTermination = { _ in browser.cancel() }
        }
    }

    // MARK: Tailscale

    public static let tailscaleCommand = "if command -v tailscale >/dev/null 2>&1; then exec tailscale status --json; fi; "
        + "for T in /Applications/Tailscale.app/Contents/MacOS/Tailscale /opt/homebrew/bin/tailscale /usr/local/bin/tailscale /usr/bin/tailscale; do "
        + "if [ -x \"$T\" ]; then exec \"$T\" status --json; fi; done; exit 127"

    static func isPeerAddress(_ ip: String) -> Bool { NetworkAddress.isTailscale(ip) && ip != "100.100.100.100" }
    /// Parses `tailscale status --json`: the source computer plus online peers, bounded and deduplicated.
    public static func parseTailscale(_ json: Data) throws -> [Host] {
        guard json.count <= 1_048_576 else { throw ShelloError.invalid("Too many peers") }
        guard let o = try JSONSerialization.jsonObject(with: json) as? [String: Any], (o["BackendState"] as? String) == "Running" else { return [] }
        var result = [String: Host]()
        func add(_ peer: [String: Any]?, self isSelf: Bool) {
            guard let peer, isSelf || (peer["Online"] as? Bool) == true, let ips = peer["TailscaleIPs"] as? [String] else { return }
            func clean(_ s: String) -> String { s.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }.map(String.init).joined().trimmingCharacters(in: .whitespaces) }
            var name = clean(peer["HostName"] as? String ?? "")
            if name.isEmpty { name = clean(peer["DNSName"] as? String ?? ""); if name.hasSuffix(".") { name.removeLast() } }
            name = String(name.prefix(80))
            for ip in ips where result.count < maxPeers && isPeerAddress(ip) { result[ip] = Host(address: ip, name: name.isEmpty ? ip : name, source: .tailscale) }
        }
        add(o["Self"] as? [String: Any], self: true)
        if let peers = o["Peer"] as? [String: Any] { for k in peers.keys.sorted() where result.count < maxPeers { add(peers[k] as? [String: Any], self: false) } }
        return result.values.sorted { $0.address < $1.address }
    }
    /// Reads the tailnet peer list from up to four saved SSH hosts (existing auth and strict host-key verification).
    public static func tailscalePeers(store: ProfileStore, activeProfile: String? = nil, command: String = tailscaleCommand) async -> [Host] {
        var sources = [SSHProfile](); var endpoints = Set<String>()
        for p in store.list().sorted(by: { ($0.id == activeProfile ? 0 : 1) < ($1.id == activeProfile ? 0 : 1) }) {
            if sources.count >= 4 { break }
            if endpoints.insert("\(p.user)@\(p.host):\(p.port)").inserted { sources.append(p) }
        }
        var found = [String: Host]()
        for p in sources {
            guard let out = try? await RemoteCommand.run(profileID: p.id, store: store, command: command, input: nil, timeout: 12),
                  let peers = try? parseTailscale(out) else { continue }
            for h in peers { found[h.address] = h }
        }
        return found.values.sorted { $0.address < $1.address }
    }
}
