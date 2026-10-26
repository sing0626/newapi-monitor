import Foundation
import CryptoKit
import CommonCrypto
import Security

enum BackupCipherError: LocalizedError {
    case passwordTooShort
    case payloadTooLarge
    case invalidEnvelope
    case keyDerivationFailed
    case encryptionFailed
    case decryptionFailed

    var errorDescription: String? {
        switch self {
        case .passwordTooShort:
            return "密碼至少需要 10 個字元"
        case .payloadTooLarge:
            return "備份檔案過大（超過 8 MB 限制）"
        case .invalidEnvelope:
            return "加密備份格式不受支援或已損毀"
        case .keyDerivationFailed:
            return "金鑰衍生失敗"
        case .encryptionFailed:
            return "加密備份失敗"
        case .decryptionFailed:
            return "密碼錯誤或備份檔案已被篡改"
        }
    }
}

enum BackupCipher {
    static let format = "newapi-monitor-encrypted"
    static let iterations = 210_000
    static let maxBytes = 8 * 1024 * 1024
    private static let aad = Data("newapi-monitor-backup:v1".utf8)

    struct Envelope: Codable, Equatable {
        var format: String = BackupCipher.format
        var version: Int = 1
        var kdf: String = "PBKDF2-SHA256"
        var cipher: String = "AES-256-GCM"
        var iterations: Int = BackupCipher.iterations
        var salt: String
        var iv: String
        var data: String
    }

    static func isEncrypted(_ text: String) -> Bool {
        guard text.utf8.count <= maxBytes * 2 else { return false }
        let trimmed = cleanText(text)
        guard let data = trimmed.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let fmt = json["format"] as? String else {
            return false
        }
        return fmt == format
    }

    static func encrypt(plain: String, password: String) throws -> String {
        // Kotlin CharArray.size counts UTF-16 code units, not grapheme clusters.
        guard password.utf16.count >= 10 else {
            throw BackupCipherError.passwordTooShort
        }
        let plainData = Data(plain.utf8)
        guard plainData.count <= maxBytes else {
            throw BackupCipherError.payloadTooLarge
        }

        var salt = Data(count: 16)
        var iv = Data(count: 12)
        let saltStatus = salt.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
        }
        let ivStatus = iv.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
        }
        guard saltStatus == errSecSuccess, ivStatus == errSecSuccess else {
            throw BackupCipherError.encryptionFailed
        }

        let key = try deriveKey(password: password, salt: salt, iterations: iterations)
        let nonce = try AES.GCM.Nonce(data: iv)
        let sealed = try AES.GCM.seal(plainData, using: key, nonce: nonce, authenticating: aad)

        var combinedPayload = Data()
        combinedPayload.append(sealed.ciphertext)
        combinedPayload.append(sealed.tag)

        let envelope = Envelope(
            salt: salt.base64EncodedString(),
            iv: iv.base64EncodedString(),
            data: combinedPayload.base64EncodedString()
        )
        let encoded = try JSONEncoder().encode(envelope)
        guard let result = String(data: encoded, encoding: .utf8) else {
            throw BackupCipherError.encryptionFailed
        }
        return result
    }

    static func decrypt(text: String, password: String) throws -> String {
        guard text.utf8.count <= maxBytes * 2 else {
            throw BackupCipherError.payloadTooLarge
        }
        let requiredFields: Set<String> = ["format", "version", "kdf", "cipher", "iterations", "salt", "iv", "data"]
        guard let rawData = cleanText(text).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: rawData) as? [String: Any],
              Set(object.keys) == requiredFields,
              let envelope = try? JSONDecoder().decode(Envelope.self, from: rawData) else {
            throw BackupCipherError.invalidEnvelope
        }
        guard envelope.format == format,
              envelope.version == 1,
              envelope.iterations == iterations,
              envelope.kdf == "PBKDF2-SHA256",
              envelope.cipher == "AES-256-GCM" else {
            throw BackupCipherError.invalidEnvelope
        }
        guard let salt = Data(base64Encoded: envelope.salt),
              let iv = Data(base64Encoded: envelope.iv),
              let data = Data(base64Encoded: envelope.data),
              salt.count == 16,
              iv.count == 12,
              data.count >= 16,
              data.count <= maxBytes + 16 else {
            throw BackupCipherError.invalidEnvelope
        }

        let key = try deriveKey(password: password, salt: salt, iterations: iterations)
        let nonce = try AES.GCM.Nonce(data: iv)
        let ciphertext = data.prefix(data.count - 16)
        let tag = data.suffix(16)

        do {
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            let decrypted = try AES.GCM.open(box, using: key, authenticating: aad)
            guard let plain = String(data: decrypted, encoding: .utf8) else {
                throw BackupCipherError.decryptionFailed
            }
            return plain
        } catch {
            throw BackupCipherError.decryptionFailed
        }
    }

    private static func cleanText(_ text: String) -> String {
        var cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("\u{FEFF}") { cleaned.removeFirst() }
        return cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func deriveKey(password: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        var passwordData = Data(password.utf8)
        var derivedBytes = [UInt8](repeating: 0, count: 32)
        defer {
            // Best effort: Swift String and CryptoKit may retain their own copies.
            passwordData.resetBytes(in: 0..<passwordData.count)
            derivedBytes.withUnsafeMutableBytes { $0.initializeMemory(as: UInt8.self, repeating: 0) }
        }
        let status = passwordData.withUnsafeBytes { pwdPtr in
            salt.withUnsafeBytes { saltPtr in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pwdPtr.baseAddress?.assumingMemoryBound(to: Int8.self),
                    passwordData.count,
                    saltPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    &derivedBytes,
                    32
                )
            }
        }
        guard status == kCCSuccess else {
            throw BackupCipherError.keyDerivationFailed
        }
        return SymmetricKey(data: derivedBytes)
    }
}
