import Foundation

/// QR pins the ephemeral TLS certificate; the one-time token authorizes only a public-key append.
public struct PairingTicket: Equatable {
    public let host: String, pin: String, token: String
    public let port: Int, expires: Int64

    public init(_ text: String, now: Date = Date()) throws {
        guard text.count <= 4096, let c = URLComponents(string: text), c.scheme == "ghostty-pair", c.host == "v1" else {
            throw ShelloError.invalid("Not a Shello pairing QR")
        }
        func q(_ n: String) -> String? { c.queryItems?.first { $0.name == n }?.value }
        guard let host = q("host"), let pin = q("pin"), let token = q("token"), let port = q("port").flatMap(Int.init), let expires = q("expires").flatMap(Int64.init),
              NetworkAddress.ipv4(host) != nil, pin.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              token.range(of: "^[A-Za-z0-9_-]{32,128}$", options: .regularExpression) != nil, (1...65535).contains(port)
        else { throw ShelloError.invalid("Invalid pairing QR") }
        guard NetworkAddress.isPairingAddress(host) else { throw ShelloError.invalid("LAN or Tailscale IPv4 pairing only") }
        let t = Int64(now.timeIntervalSince1970)
        guard expires >= t - 10, expires <= t + 900 else { throw ShelloError.invalid("QR expired; check clocks and regenerate") }
        self.host = host; self.pin = pin; self.token = token; self.port = port; self.expires = expires
    }

    /// Server response of `POST /pair` (version 1).
    public struct Response {
        public let port: Int, user: String, name: String, knownHosts: String
        public init(json: Data, ticket: PairingTicket) throws {
            guard let o = try JSONSerialization.jsonObject(with: json) as? [String: Any], (o["version"] as? Int) == 1,
                  let port = o["port"] as? Int, let user = o["user"] as? String, let name = o["name"] as? String, let keys = o["hostKeys"] as? [String]
            else { throw ShelloError.failed("Unsupported pairing response") }
            let prefix = port == 22 ? ticket.host : "[\(ticket.host)]:\(port)"
            var lines = [String]()
            for k in keys {
                guard k.range(of: "^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/=]+$", options: .regularExpression) != nil else { throw ShelloError.failed("Invalid host pin") }
                lines.append("\(prefix) \(k)")
            }
            guard !lines.isEmpty, (1...65535).contains(port), !user.isEmpty else { throw ShelloError.failed("Invalid paired profile") }
            self.port = port; self.user = user; self.name = name; self.knownHosts = lines.joined(separator: "\n") + "\n"
        }
    }
}
