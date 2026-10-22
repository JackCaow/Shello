import Foundation
import Security
import ShelloCore

/// Keychain-backed `SecretStore`. Items never leave this device and are unavailable before first unlock.
public final class KeychainSecretStore: SecretStore {
    private let service: String
    public init(service: String = "dev.ghostty.shello.profiles") { self.service = service }
    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }
    public func read(_ key: String) throws -> Data? {
        var q = query(key); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let s = SecItemCopyMatching(q as CFDictionary, &out)
        if s == errSecItemNotFound { return nil }
        guard s == errSecSuccess else { throw ShelloError.failed("Keychain read failed (\(s))") }
        return out as? Data
    }
    public func write(_ key: String, _ value: Data) throws {
        var q = query(key)
        let update = [kSecValueData as String: value]
        var s = SecItemUpdate(q as CFDictionary, update as CFDictionary)
        if s == errSecItemNotFound {
            q[kSecValueData as String] = value
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            s = SecItemAdd(q as CFDictionary, nil)
        }
        guard s == errSecSuccess else { throw ShelloError.failed("Keychain write failed (\(s))") }
    }
    public func delete(_ key: String) throws {
        let s = SecItemDelete(query(key) as CFDictionary)
        guard s == errSecSuccess || s == errSecItemNotFound else { throw ShelloError.failed("Keychain delete failed (\(s))") }
    }
}
