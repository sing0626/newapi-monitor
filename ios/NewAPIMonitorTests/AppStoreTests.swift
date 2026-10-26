import XCTest
@testable import NewAPIMonitor

@MainActor
final class AppStoreTests: XCTestCase {
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
        let store = AppStore(fileURL: file)
        store.setLifecycle(active: true, protected: true)

        try store.saveInstance(
            Instance(id: "", name: "Prod", baseUrl: "https://api.example.com/Gateway", accessToken: "", apiKey: "",
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
        XCTAssertEqual(store.budgets[store.instances[0].id], 80)
        XCTAssertEqual(store.appearance.mode, "oled")
        XCTAssertEqual(store.appearance.accent, "blue")
    }
}
