import XCTest
import ShelloCore
import ShelloServices
@testable import Shello

@MainActor
final class SSHDiscoveryModelTests: XCTestCase {
    private func store() -> ProfileStore {
        let env = AppEnvironment(profileDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), defaults: UserDefaults(suiteName: UUID().uuidString)!, secrets: MemorySecretStore())
        return env.store
    }
    private func wait(_ condition: @escaping () -> Bool) async {
        await fulfillment(of: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)], timeout: 3)
    }
    func testRealSSHBannerProducesAutomaticResult() async throws {
        guard let portText = ProcessInfo.processInfo.environment["SHELLO_SSHD_PORT"], let port = Int(portText) else { throw XCTSkip("SSH fixture required") }
        let model = SSHDiscoveryModel(sources: { _ in [Discovery.scan(addresses: ["127.0.0.1"], port: port, resolveNames: false)] })
        model.start(store: store())
        await wait { !model.scanning }
        XCTAssertEqual(model.hosts.map(\.address), ["127.0.0.1"])
    }
    func testResumeAfterPresentationCancellationAndNoRescanOnPickerReturn() async {
        var starts = 0
        let model = SSHDiscoveryModel(sources: { _ in
            starts += 1
            return [AsyncStream { c in c.yield(.init(address: "127.0.0.1", name: "Mac", source: .lan)); c.finish() }]
        })
        let store = store()
        model.resume(store: store); model.stop(); model.resume(store: store)
        await wait { !model.scanning }
        XCTAssertEqual(starts, 2)
        model.resume(store: store)
        XCTAssertEqual(starts, 2, "Returning from the picker must keep the completed result")
        XCTAssertEqual(model.hosts.first?.name, "Mac")
    }
    func testCombinesSourcesDeduplicatesAndKeepsResolvedName() async {
        var first: AsyncStream<Discovery.Host>.Continuation!
        var second: AsyncStream<Discovery.Host>.Continuation!
        let a = AsyncStream<Discovery.Host> { first = $0 }, b = AsyncStream<Discovery.Host> { second = $0 }
        let model = SSHDiscoveryModel(sources: { _ in [a, b] })
        model.start(store: store())
        first.yield(.init(address: "192.168.1.10", name: "Mac mini", source: .bonjour))
        await wait { model.hosts.first?.name == "Mac mini" }
        second.yield(.init(address: "192.168.1.10", name: "192.168.1.10", source: .lan))
        second.yield(.init(address: "100.64.0.2", name: "Tailnet host", source: .tailscale))
        first.finish(); second.finish()
        await wait { !model.scanning }
        XCTAssertEqual(model.hosts.count, 2)
        XCTAssertEqual(model.hosts.first?.source, .tailscale)
        XCTAssertEqual(model.hosts.last?.name, "Mac mini")
    }
    func testRestartIgnoresStaleResultsAndOldDeadline() async throws {
        var streams = [AsyncStream<Discovery.Host>.Continuation]()
        let model = SSHDiscoveryModel(duration: 300_000_000, sources: { _ in [AsyncStream { streams.append($0) }] })
        let store = store()
        model.start(store: store)
        try await Task.sleep(nanoseconds: 180_000_000)
        model.start(store: store)
        streams[0].yield(.init(address: "192.168.1.3", name: "stale", source: .lan))
        streams[1].yield(.init(address: "192.168.1.4", name: "current", source: .lan))
        try await Task.sleep(nanoseconds: 180_000_000)
        XCTAssertTrue(model.scanning, "The first run's deadline must not cancel the replacement")
        XCTAssertEqual(model.hosts.map(\.name), ["current"])
        await wait { !model.scanning }
        XCTAssertEqual(model.hosts.map(\.name), ["current"])
    }
    func testStoppingCancelsProducersAndRetainsResultsUntilRescan() async {
        let terminated = expectation(description: "producer cancelled")
        var stream: AsyncStream<Discovery.Host>.Continuation!
        let model = SSHDiscoveryModel(sources: { _ in [AsyncStream { stream = $0; $0.onTermination = { _ in terminated.fulfill() } }] })
        model.start(store: store())
        stream.yield(.init(address: "192.168.1.2", name: "fixture", source: .lan))
        await wait { model.hosts.count == 1 }
        model.stop()
        await fulfillment(of: [terminated], timeout: 3)
        XCTAssertFalse(model.scanning)
        XCTAssertEqual(model.hosts.first?.name, "fixture")
    }
}
