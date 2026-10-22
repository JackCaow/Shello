import SwiftUI
import ShelloCore
import ShelloServices

/// One foreground discovery run, shared by the connection form and its server picker.
@MainActor
final class SSHDiscoveryModel: ObservableObject {
    typealias Sources = (ProfileStore) -> [AsyncStream<Discovery.Host>]
    @Published private(set) var hosts: [Discovery.Host] = []
    @Published private(set) var scanning = false
    private var found: [String: Discovery.Host] = [:]
    private var runID: UUID?
    private var task: Task<Void, Never>?
    private let sources: Sources
    private let duration: UInt64
    var buttonTitle: String { hosts.isEmpty ? tr(scanning ? "discovering" : "discover_servers") : trPlural("servers_found", hosts.count) }

    init(duration: UInt64 = 25_000_000_000, sources: Sources? = nil) {
        self.duration = duration; self.sources = sources ?? Self.liveSources
    }
    func resume(store: ProfileStore) { if runID == nil { start(store: store) } }

    func start(store: ProfileStore) {
        stop()
        found = [:]; hosts = []; scanning = true
        let id = UUID(); runID = id
        let streams = sources(store), duration = duration
        task = Task { [weak self] in
            await withTaskGroup(of: Bool.self) { group in
                for stream in streams {
                    group.addTask { [weak self] in
                        for await host in stream {
                            guard !Task.isCancelled else { break }
                            await self?.receive(host, run: id)
                        }
                        return true
                    }
                }
                group.addTask { try? await Task.sleep(nanoseconds: duration); return false }
                var remaining = streams.count
                while remaining > 0, let finished = await group.next() {
                    if !finished || Task.isCancelled { break }
                    remaining -= 1
                }
                group.cancelAll()
            }
            guard let self, self.runID == id, !Task.isCancelled else { return }
            self.scanning = false; self.task = nil
        }
    }
    func stop() {
        runID = nil; task?.cancel(); task = nil; scanning = false
    }
    private func receive(_ host: Discovery.Host, run: UUID) {
        guard runID == run else { return }
        // Prefer a useful service/peer name over an IP-only reverse-DNS fallback.
        if let old = found[host.address], old.name != old.address && host.name == host.address { return }
        found[host.address] = host
        hosts = found.values.sorted { $0.address.compare($1.address, options: .numeric) == .orderedAscending }
    }

    private static func liveSources(store: ProfileStore) -> [AsyncStream<Discovery.Host>] {
        let args = ProcessInfo.processInfo.arguments
        // UI tests still use real sockets/banners, on an isolated loopback fixture rather than scanning the user's LAN.
        if args.contains("-SHELLO_UI_TEST"), let i = args.firstIndex(of: "-SHELLO_TEST_DISCOVERY_PORT"), i + 1 < args.count, let port = Int(args[i + 1]), (1...65535).contains(port) {
            return [Discovery.scan(addresses: ["127.0.0.1"], port: port, resolveNames: false)]
        }
        var inputs: [AsyncStream<Discovery.Host>] = []
        if let wifi = Discovery.wifiAddress() {
            inputs.append(Discovery.scan(addresses: Discovery.targets(local: wifi.address, prefix: wifi.prefix)))
        }
        inputs.append(verified { Discovery.bonjour() })
        inputs.append(AsyncStream { continuation in
            let task = Task.detached {
                for peer in await Discovery.tailscalePeers(store: store) {
                    guard !Task.isCancelled else { break }
                    if Discovery.probe(host: peer.address) != nil { continuation.yield(peer) }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        })
        return inputs
    }
    /// A Bonjour advertisement alone is insufficient; verify SSH on port 22 before listing it.
    private static func verified(_ source: @escaping () -> AsyncStream<Discovery.Host>) -> AsyncStream<Discovery.Host> {
        AsyncStream { continuation in
            let task = Task.detached {
                for await peer in source() {
                    guard !Task.isCancelled else { break }
                    if Discovery.probe(host: peer.address) != nil { continuation.yield(peer) }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
