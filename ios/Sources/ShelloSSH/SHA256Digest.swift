import Foundation
import CryptoKit

enum SHA256Digest {
    static func hash(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }
}
