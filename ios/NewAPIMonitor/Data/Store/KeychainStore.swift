import Foundation
import Security

enum KeychainStore {
    static let service = "com.singnewapimonitor.ios.credentials"
    static let legacyService = "com.newapimonitor.ios.credentials"
    private static let metadataAccount = "__app_store_metadata_v1__"

    enum StoreError: LocalizedError, Equatable {
        case status(OSStatus)
        case invalidData

        var errorDescription: String? {
            switch self {
            case .status(let status):
                return (SecCopyErrorMessageString(status, nil) as String?) ?? "Keychain error (\(status))"
            case .invalidData:
                return "Keychain credential data is invalid"
            }
        }
    }

    struct SecretPair: Codable, Equatable, Sendable {
        var accessToken: String
        var apiKey: String
    }

    // An immutable internal seam lets tests exercise Keychain failure paths
    // without entitlements, global mocks, or deleting real credentials.
    struct Client {
        var update: ([String: Any], [String: Any]) -> OSStatus
        var add: ([String: Any]) -> OSStatus
        var copy: ([String: Any]) -> (OSStatus, Data?)
        var delete: ([String: Any]) -> OSStatus

        static let live = Client(
            update: { SecItemUpdate($0 as CFDictionary, $1 as CFDictionary) },
            add: { SecItemAdd($0 as CFDictionary, nil) },
            copy: { query in
                var item: CFTypeRef?
                let status = SecItemCopyMatching(query as CFDictionary, &item)
                return (status, item as? Data)
            },
            delete: { SecItemDelete($0 as CFDictionary) }
        )
    }

    static func saveCredentials(
        for instanceId: String, accessToken: String, apiKey: String, using client: Client = .live
    ) throws {
        let pair = SecretPair(accessToken: accessToken, apiKey: apiKey)
        let data = try JSONEncoder().encode(pair)
        try upsertData(data, account: instanceId, serviceName: service, using: client)
    }

    static func loadCredentials(for instanceId: String, using client: Client = .live) throws -> SecretPair? {
        if let data = try loadRawData(account: instanceId, serviceName: service, using: client) {
            return try JSONDecoder().decode(SecretPair.self, from: data)
        }
        // Fallback to legacy bundle ID service ("com.newapimonitor.ios.credentials") and migrate automatically
        if let legacyData = try loadRawData(account: instanceId, serviceName: legacyService, using: client) {
            let pair = try JSONDecoder().decode(SecretPair.self, from: legacyData)
            try? upsertData(legacyData, account: instanceId, serviceName: service, using: client)
            return pair
        }
        return nil
    }

    static func deleteCredentials(for instanceId: String, using client: Client = .live) throws {
        let status = client.delete(credentialQuery(instanceId, serviceName: service))
        if status != errSecItemNotFound { try requireSuccess(status) }
        _ = client.delete(credentialQuery(instanceId, serviceName: legacyService))
    }

    static func clearAllCredentials(using client: Client = .live) throws {
        for svc in [service, legacyService] {
            let status = client.delete([
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: svc
            ])
            if status != errSecItemNotFound { try requireSuccess(status) }
        }
    }

    /// Saves a mirror of the non-secret StoreDocument in Keychain so sideload reinstalls
    /// or container path changes never reset user instances or preferences.
    static func saveMetadataMirror(_ data: Data, using client: Client = .live) {
        try? upsertData(data, account: metadataAccount, serviceName: service, using: client)
    }

    static func loadMetadataMirror(using client: Client = .live) -> Data? {
        if let data = try? loadRawData(account: metadataAccount, serviceName: service, using: client) {
            return data
        }
        return try? loadRawData(account: metadataAccount, serviceName: legacyService, using: client)
    }

    private static func upsertData(_ data: Data, account: String, serviceName: String, using client: Client) throws {
        let query = credentialQuery(account, serviceName: serviceName)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = client.update(query, attributes)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw StoreError.status(status) }

        let addQuery = query.merging(attributes) { _, value in value }
        let addStatus = client.add(addQuery)
        if addStatus == errSecDuplicateItem {
            try requireSuccess(client.update(query, attributes))
        } else {
            try requireSuccess(addStatus)
        }
    }

    private static func loadRawData(account: String, serviceName: String, using client: Client) throws -> Data? {
        var query = credentialQuery(account, serviceName: serviceName)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let (status, data) = client.copy(query)
        if status == errSecItemNotFound { return nil }
        try requireSuccess(status)
        guard let data else { throw StoreError.invalidData }
        return data
    }

    private static func credentialQuery(_ instanceId: String, serviceName: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: instanceId
        ]
    }

    private static func requireSuccess(_ status: OSStatus) throws {
        guard status == errSecSuccess else { throw StoreError.status(status) }
    }
}
