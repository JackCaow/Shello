import Foundation
import Security
import CryptoKit
import ShelloCore

/// Generates the phone's SSH key and installs its public half on the computer through the QR-pinned HTTPS endpoint.
public enum PairingService {
    public struct Result {
        public var profile: SSHProfile
        public var secrets: SSHSecrets
    }

    /// RSA-3072 keypair. Pairing servers only accept `ssh-rsa` keys of at least 3072 bits.
    public static func generateKey(comment: String = "shello-ios") throws -> (privatePEM: Data, publicLine: String) {
        let attrs: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 3072]
        var err: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attrs as CFDictionary, &err), let pub = SecKeyCopyPublicKey(key),
              let priv = SecKeyCopyExternalRepresentation(key, &err) as Data?, let pubDER = SecKeyCopyExternalRepresentation(pub, &err) as Data?
        else { throw ShelloError.failed("Key generation failed") }
        let (n, e) = try parsePKCS1Public(pubDER)
        var blob = Data(); blob.appendSSH(Data("ssh-rsa".utf8)); blob.appendSSH(mpint(e)); blob.appendSSH(mpint(n))
        let b64 = priv.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
        let pem = "-----BEGIN RSA PRIVATE KEY-----\n\(b64)\n-----END RSA PRIVATE KEY-----\n"
        return (Data(pem.utf8), "ssh-rsa \(blob.base64EncodedString()) \(comment)")
    }
    static func mpint(_ v: Data) -> Data {
        var d = v.drop { $0 == 0 }; if let f = d.first, f & 0x80 != 0 { d = Data([0]) + d }; return Data(d)
    }
    /// RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
    static func parsePKCS1Public(_ der: Data) throws -> (Data, Data) {
        var i = der.startIndex
        func length() throws -> Int {
            guard i < der.endIndex else { throw ShelloError.failed("bad key") }
            let b = der[i]; i += 1
            if b < 0x80 { return Int(b) }
            var n = 0; for _ in 0..<Int(b & 0x7f) { guard i < der.endIndex else { throw ShelloError.failed("bad key") }; n = n << 8 | Int(der[i]); i += 1 }
            return n
        }
        func integer() throws -> Data {
            guard i < der.endIndex, der[i] == 0x02 else { throw ShelloError.failed("bad key") }; i += 1
            let l = try length(); guard i + l <= der.endIndex else { throw ShelloError.failed("bad key") }
            defer { i += l }; return der.subdata(in: i..<(i + l))
        }
        guard der.first == 0x30 else { throw ShelloError.failed("bad key") }; i += 1; _ = try length()
        let n = try integer(), e = try integer(); return (n, e)
    }

    /// Delegate that accepts exactly the certificate whose SHA-256 matches the QR pin (no CA or hostname fallback).
    final class PinDelegate: NSObject, URLSessionDelegate {
        let pin: String; let now: () -> Date
        init(pin: String, now: @escaping () -> Date = Date.init) { self.pin = pin; self.now = now }
        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust, let trust = challenge.protectionSpace.serverTrust,
                  let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else {
                completionHandler(.cancelAuthenticationChallenge, nil); return
            }
            let digest = SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map { String(format: "%02x", $0) }.joined()
            guard digest == pin else { completionHandler(.cancelAuthenticationChallenge, nil); return }
            completionHandler(.useCredential, URLCredential(trust: trust))
        }
    }

    public static func pair(ticket: PairingTicket) async throws -> Result {
        let key = try generateKey()
        let delegate = PinDelegate(pin: ticket.pin)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 7; cfg.timeoutIntervalForResource = 15; cfg.waitsForConnectivity = false
        let session = URLSession(configuration: cfg, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var req = URLRequest(url: URL(string: "https://\(ticket.host):\(ticket.port)/pair")!)
        req.httpMethod = "POST"; req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["token": ticket.token, "publicKey": key.publicLine])
        let (data, response) = try await session.data(for: req)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw ShelloError.failed("Pairing rejected or expired") }
        guard data.count <= 32768 else { throw ShelloError.failed("Too large") }
        let r = try PairingTicket.Response(json: data, ticket: ticket)
        let profile = SSHProfile(name: r.name, host: ticket.host, port: r.port, user: r.user, auth: .key, tmux: true)
        return Result(profile: profile, secrets: SSHSecrets(privateKey: key.privatePEM, knownHosts: Data(r.knownHosts.utf8)))
    }
}

extension Data {
    mutating func appendSSH(_ d: Data) {
        var n = UInt32(d.count).bigEndian
        append(Data(bytes: &n, count: 4)); append(d)
    }
}
