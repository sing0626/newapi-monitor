import XCTest
import Security
@testable import NewAPIMonitor

@MainActor
final class AppStoreTests: XCTestCase {
    private static func memoryKeychain() -> KeychainStore.Client {
        var storage: [String: Data] = [:]
        func key(_ query: [String: Any]) -> String {
            let svc = (query[kSecAttrService as String] as? String) ?? ""
            let acct = (query[kSecAttrAccount as String] as? String) ?? ""
            return "\(svc)|\(acct)"
        }
        return KeychainStore.Client(
            update: { query, attrs in
                let k = key(query)
                guard storage[k] != nil else { return errSecItemNotFound }
                storage[k] = attrs[kSecValueData as String] as? Data
                return errSecSuccess
            },
            add: { query in
                let k = key(query)
                storage[k] = query[kSecValueData as String] as? Data
                return errSecSuccess
            },
            copy: { query in
                let k = key(query)
                guard let data = storage[k] else { return (errSecItemNotFound, nil) }
                return (errSecSuccess, data)
            },
            delete: { query in
                let svc = (query[kSecAttrService as String] as? String) ?? ""
                if let acct = query[kSecAttrAccount as String] as? String {
                    storage.removeValue(forKey: "\(svc)|\(acct)")
                } else {
                    for existing in Array(storage.keys) where existing.hasPrefix("\(svc)|") {
                        storage.removeValue(forKey: existing)
                    }
                }
                return errSecSuccess
            }
        )
    }

    func testHTTPSNormalizationPreservesCaseSensitivePath() throws {
        let normalized = try AppStore.normalizedURL("HTTPS://Example.COM:443/SubPath/v1/")
        XCTAssertEqual(normalized, "https://example.com/SubPath/v1")
        XCTAssertThrowsError(try AppStore.normalizedURL("http://example.com"))
        XCTAssertThrowsError(try AppStore.normalizedURL("https://user:pass@example.com"))
        XCTAssertThrowsError(try AppStore.normalizedURL("https://example.com?token=1"))
        XCTAssertThrowsError(try AppStore.normalizedURL("https://example.com#frag"))
    }

    func testCorruptedMetadataFailsClosedWithoutOverwriting() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("metadata-v1.json")
        try Data("not-json".utf8).write(to: file)

        let store = AppStore(fileURL: file)
        XCTAssertNotNil(store.loadFailure)
        XCTAssertFalse(store.canOperate)
        XCTAssertThrowsError(try store.updateSettings(hideMoney: true))
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "not-json")
    }

    func testImportPreviewAndSecretFreeMerge() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("metadata-v1.json")
        let store = AppStore(fileURL: file, keychain: Self.memoryKeychain())
        store.setLifecycle(active: true, protected: true)

        try store.saveInstance(
            Instance(id: "", name: "Prod", baseUrl: "https://api.example.com/Gateway", accessToken: "local-tok", apiKey: "sk-local",
                     quotaPerUnit: 500_000, authStyle: nil, authStyleKey: nil, createdAt: 1),
            budget: 25,
            pinned: false,
            included: true
        )
        try store.updateSettings(mode: "oled", accent: "green")

        let incoming = """
        {
          "app": "newapi-monitor",
          "version": 1,
          "instances": [
            {
              "name": "Prod",
              "baseUrl": "https://API.example.com:443/Gateway/",
              "monthlyBudgetUsd": 80,
              "pinned": true,
              "alertRule": {}
            }
          ],
          "appearance": { "accent": "blue" }
        }
        """
        let preview = try store.prepareImport(incoming)
        XCTAssertEqual(preview.matchedCount, 1)
        XCTAssertEqual(preview.newCount, 0)
        try store.confirmImport(preview)
        XCTAssertEqual(store.instances.count, 1)
        XCTAssertEqual(store.instances[0].accessToken, "local-tok")
        XCTAssertEqual(store.instances[0].apiKey, "sk-local")
        XCTAssertEqual(store.budgets[store.instances[0].id], 80)
        XCTAssertEqual(store.appearance.mode, "oled")
        XCTAssertEqual(store.appearance.accent, "blue")
    }

    func testReinstallOrContainerResetRecoversFromKeychainMirror() throws {
        let keychain = Self.memoryKeychain()
        let dir1 = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let dir2 = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer {
            try? FileManager.default.removeItem(at: dir1)
            try? FileManager.default.removeItem(at: dir2)
        }
        let file1 = dir1.appendingPathComponent("metadata-v1.json")
        let store1 = AppStore(fileURL: file1, keychain: keychain)
        store1.setLifecycle(active: true, protected: true)
        try store1.saveInstance(
            Instance(id: "", name: "MySite", baseUrl: "https://api.example.com", accessToken: "tok-persist", apiKey: "sk-persist",
                     quotaPerUnit: 500_000, authStyle: nil, authStyleKey: nil, createdAt: 100),
            budget: 42,
            pinned: true,
            included: true
        )

        // Simulate sideload update / new container where metadata-v1.json does not exist yet
        let file2 = dir2.appendingPathComponent("metadata-v1.json")
        let store2 = AppStore(fileURL: file2, keychain: keychain)
        XCTAssertNil(store2.loadFailure)
        XCTAssertEqual(store2.instances.count, 1)
        XCTAssertEqual(store2.instances[0].name, "MySite")
        XCTAssertEqual(store2.instances[0].accessToken, "tok-persist")
        XCTAssertEqual(store2.instances[0].apiKey, "sk-persist")
        XCTAssertEqual(store2.budgets[store2.instances[0].id], 42)
    }
}
