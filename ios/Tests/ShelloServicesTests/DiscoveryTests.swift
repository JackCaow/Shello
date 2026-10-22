import XCTest
import Network
@testable import ShelloServices

final class DiscoveryTests: XCTestCase {
    func testTargetsStayInsideSubnet() {
        let t = Discovery.targets(local: "192.168.1.20", prefix: 24)
        XCTAssertEqual(t.count, 253); XCTAssertFalse(t.contains("192.168.1.20")); XCTAssertEqual(t.first, "192.168.1.1"); XCTAssertEqual(t.last, "192.168.1.254")
        XCTAssertEqual(Discovery.targets(local: "10.1.2.3", prefix: 16).count, 253)         // broad networks cap at one /24
        XCTAssertEqual(Discovery.targets(local: "10.0.0.5", prefix: 30), ["10.0.0.6"])
        XCTAssertEqual(Discovery.targets(local: "10.0.0.5", prefix: 31), ["10.0.0.4"])
        XCTAssertTrue(Discovery.targets(local: "nope", prefix: 24).isEmpty)
        XCTAssertTrue(Discovery.targets(local: "10.0.0.1", prefix: 40).isEmpty)
    }
    func testTailscaleParsing() throws {
        let json = #"""
        {"BackendState":"Running","Self":{"HostName":"mac","TailscaleIPs":["100.64.0.1","fd7a::1"],"Online":true},
         "Peer":{"a":{"HostName":"box","TailscaleIPs":["100.64.0.2"],"Online":true},"b":{"HostName":"off","TailscaleIPs":["100.64.0.3"],"Online":false},
                 "c":{"HostName":"","DNSName":"nas.tail.ts.net.","TailscaleIPs":["100.64.0.4"],"Online":true},"d":{"HostName":"dns","TailscaleIPs":["100.100.100.100"],"Online":true},
                 "e":{"HostName":"public","TailscaleIPs":["8.8.8.8"],"Online":true}}}
        """#
        let hosts = try Discovery.parseTailscale(Data(json.utf8))
        XCTAssertEqual(hosts.map(\.address), ["100.64.0.1", "100.64.0.2", "100.64.0.4"])
        XCTAssertEqual(hosts.map(\.name), ["mac", "box", "nas.tail.ts.net"])
        XCTAssertTrue(try Discovery.parseTailscale(Data(#"{"BackendState":"Stopped"}"#.utf8)).isEmpty)
        XCTAssertThrowsError(try Discovery.parseTailscale(Data(count: 1_048_577)))
    }
    func testProbeRequiresSSHBanner() throws {
        let sshd = try listener(banner: "SSH-2.0-Fake_1.0\r\n"), other = try listener(banner: "HTTP/1.1 200 OK\r\n\r\n")
        XCTAssertEqual(Discovery.probe(host: "127.0.0.1", port: Int(sshd.port!.rawValue)), "SSH-2.0-Fake_1.0")
        XCTAssertNil(Discovery.probe(host: "127.0.0.1", port: Int(other.port!.rawValue)))
        XCTAssertNil(Discovery.probe(host: "127.0.0.1", port: 1, connectTimeout: 0.3))
        sshd.cancel(); other.cancel()
    }
    func testScanFindsOnlyHostsWithBanner() async throws {
        let sshd = try listener(banner: "SSH-2.0-Fake\r\n")
        let port = Int(sshd.port!.rawValue)
        var found = [Discovery.Host]()
        for await h in Discovery.scan(addresses: ["127.0.0.1", "127.0.0.2"], port: port, resolveNames: false) { found.append(h) }
        XCTAssertTrue(found.contains { $0.address == "127.0.0.1" })
        sshd.cancel()
    }
    func listener(banner: String) throws -> NWListener {
        let l = try NWListener(using: .tcp, on: .any)
        l.newConnectionHandler = { c in c.start(queue: .global()); c.send(content: Data(banner.utf8), completion: .contentProcessed { _ in DispatchQueue.global().asyncAfter(deadline: .now() + 2) { c.cancel() } }) }
        let ready = expectation(description: "ready"); l.stateUpdateHandler = { if case .ready = $0 { ready.fulfill() } }
        l.start(queue: .global()); wait(for: [ready], timeout: 5); return l
    }
}
