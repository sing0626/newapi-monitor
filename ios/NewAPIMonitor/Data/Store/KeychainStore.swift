import Foundation
import Security

enum KeychainStore {
    private static let service = "com.singnewapimonitor.ios.credentials"

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
        let query = credentialQuery(instanceId)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = client.update(query, attributes)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw StoreError.status(status) }

        let addQuery = query.merging(attributes) { _, value in value }
        let addStatus = client.add(addQuery)
        // A concurrent writer may have inserted the item after our first update.
        if addStatus == errSecDuplicateItem {
            try requireSuccess(client.update(query, attributes))
        } else {
            try requireSuccess(addStatus)
        }
    }

    static func loadCredentials(for instanceId: String, using client: Client = .live) throws -> SecretPair? {
        var query = credentialQuery(instanceId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let (status, data) = client.copy(query)
        if status == errSecItemNotFound { return nil }
        try requireSuccess(status)
        guard let data else { throw StoreError.invalidData }
        // Corruption is not absence: callers must not overwrite it with blanks.
        return try JSONDecoder().decode(SecretPair.self, from: data)
    }

    static func deleteCredentials(for instanceId: String, using client: Client = .live) throws {
        let status = client.delete(credentialQuery(instanceId))
        if status != errSecItemNotFound { try requireSuccess(status) }
    }

    static func clearAllCredentials(using client: Client = .live) throws {
        let status = client.delete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service
        ])
        if status != errSecItemNotFound { try requireSuccess(status) }
    }

    private static func credentialQuery(_ instanceId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: instanceId
        ]
    }

    private static func requireSuccess(_ status: OSStatus) throws {
        guard status == errSecSuccess else { throw StoreError.status(status) }
    }
}
