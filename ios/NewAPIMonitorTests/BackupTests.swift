import XCTest
import Security
@testable import NewAPIMonitor

final class BackupTests: XCTestCase {
    func testDecryptsAndroidJavaJCEVectorAndBOM() throws {
        let envelope = """
        \u{FEFF}{
          "format": "newapi-monitor-encrypted",
          "version": 1,
          "kdf": "PBKDF2-SHA256",
          "cipher": "AES-256-GCM",
          "iterations": 210000,
          "salt": "AQIDBAUGBwgJCgsMDQ4PEA==",
          "iv": "ISIjJCUmJygpKiss",
          "data": "fnPVG2fivqPXuD0yqllvJmB74MoedwALFiWyc0l1OaCxMpzaSFLR97/UrjuFK92+OQESDdLXZ7dTU9QS29EAM+KxxCRygmKY8nY2LiNqjbJ0wmKs9kDIkKvXjS8BnBzcMK61obPay9zieP+i9HGAsZmrvPJYnX3WCKMV8Qf2tS/fvbSqBcGU+T5TJCSTQ//Kl0fzGeCBvHkYIX+2H9iXxeRs/p8nJb6DdmlTuedbwBEmch5hs+b8cXfuQTZYd5sZ3vm0Aw=="
        }
        """
        XCTAssertTrue(BackupCipher.isEncrypted(envelope))
        let plain = try BackupCipher.decrypt(text: envelope, password: "correct-horse-battery-staple")
        let parsed = try XCTUnwrap(BackupManager.parse(plain))
        XCTAssertEqual(parsed.instances.count, 1)
        XCTAssertEqual(parsed.instances.first?.name, "AndroidVector")
        XCTAssertEqual(parsed.instances.first?.baseUrl, "https://api.example.com")
        XCTAssertEqual(parsed.instances.first?.accessToken, "tok-vec")
        XCTAssertEqual(parsed.instances.first?.apiKey, "sk-vec")
    }

    func testEncryptDecryptRoundTripAndTamperRejection() throws {
        let plain = #"{"app":"newapi-monitor","version":1,"instances":[]}"#
        XCTAssertThrowsError(try BackupCipher.encrypt(plain: plain, password: "short"))
        let encrypted = try BackupCipher.encrypt(plain: plain, password: "ten-chars!")
        XCTAssertTrue(BackupCipher.isEncrypted(encrypted))
        XCTAssertEqual(try BackupCipher.decrypt(text: encrypted, password: "ten-chars!"), plain)
        XCTAssertThrowsError(try BackupCipher.decrypt(text: encrypted, password: "wrong-password"))

        let tampered = encrypted.replacingOccurrences(of: "\"version\":1", with: "\"version\":2")
        XCTAssertThrowsError(try BackupCipher.decrypt(text: tampered, password: "ten-chars!"))
    }

    func testDefaultExportOmitsCredentialsAndPreservesPartialAppearance() throws {
        let instance = Instance(
            id: "i1",
            name: "Main",
            baseUrl: "https://api.example.com",
            accessToken: "secret-access",
            apiKey: "sk-secret",
            quotaPerUnit: 500_000,
            authStyle: .raw,
            authStyleKey: .raw,
            createdAt: 100
        )
        let json = try BackupManager.toJson(
            instances: [instance],
            appearance: Appearance(language: "yue", mode: "dark", accent: "purple"),
            alertSettings: nil,
            includeCredentials: false
        )
        XCTAssertFalse(json.contains("secret-access"))
        XCTAssertFalse(json.contains("sk-secret"))

        let partial = """
        {
          "app": "newapi-monitor",
          "version": 1,
          "instances": [
            {
              "name": "Legacy",
              "baseUrl": "https://legacy.example.com",
              "alertRule": {}
            }
          ],
          "appearance": { "mode": "oled" }
        }
        """
        let parsed = try XCTUnwrap(BackupManager.parse(partial))
        XCTAssertEqual(parsed.appearance?.mode, "oled")
        XCTAssertNil(parsed.appearance?.language)
        XCTAssertNil(parsed.appearance?.accent)
        XCTAssertEqual(parsed.instanceOptions.first?.alertRule?.quietStart, "23:00")
        XCTAssertEqual(parsed.instanceOptions.first?.alertRule?.quietEnd, "07:00")
        XCTAssertEqual(parsed.instanceOptions.first?.alertRule?.cooldownMinutes, 60)
    }

    func testKeychainClientUpdateBeforeAddAndPropagatesErrors() throws {
        var store: [String: Data] = [:]
        var updates = 0
        var adds = 0
        let client = KeychainStore.Client(
            update: { query, attrs in
                updates += 1
                let acct = query[kSecAttrAccount as String] as! String
                guard store[acct] != nil else { return errSecItemNotFound }
                store[acct] = attrs[kSecValueData as String] as? Data
                return errSecSuccess
            },
            add: { query in
                adds += 1
                let acct = query[kSecAttrAccount as String] as! String
                store[acct] = query[kSecValueData as String] as? Data
                return errSecSuccess
            },
            copy: { query in
                let acct = query[kSecAttrAccount as String] as! String
                guard let data = store[acct] else { return (errSecItemNotFound, nil) }
                return (errSecSuccess, data)
            },
            delete: { query in
                if let acct = query[kSecAttrAccount as String] as? String {
                    store.removeValue(forKey: acct)
                } else {
                    store.removeAll()
                }
                return errSecSuccess
            }
        )

        XCTAssertNil(try KeychainStore.loadCredentials(for: "id1", using: client))
        try KeychainStore.saveCredentials(for: "id1", accessToken: "a1", apiKey: "k1", using: client)
        XCTAssertEqual(updates, 1)
        XCTAssertEqual(adds, 1)
        try KeychainStore.saveCredentials(for: "id1", accessToken: "a2", apiKey: "k2", using: client)
        XCTAssertEqual(updates, 2)
        XCTAssertEqual(adds, 1)
        let loaded = try XCTUnwrap(KeychainStore.loadCredentials(for: "id1", using: client))
        XCTAssertEqual(loaded, .init(accessToken: "a2", apiKey: "k2"))

        let lockedClient = KeychainStore.Client(
            update: { _, _ in errSecInteractionNotAllowed },
            add: { _ in errSecInteractionNotAllowed },
            copy: { _ in (errSecInteractionNotAllowed, nil) },
            delete: { _ in errSecInteractionNotAllowed }
        )
        XCTAssertThrowsError(try KeychainStore.loadCredentials(for: "id1", using: lockedClient))
        XCTAssertThrowsError(try KeychainStore.saveCredentials(for: "id1", accessToken: "x", apiKey: "y", using: lockedClient))
    }
}
