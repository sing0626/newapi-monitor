import Foundation
import CoreFoundation

struct LogPage: Sendable {
    let rows: [LogEntry]
    let total: Int?
    let hasMore: Bool
}

/// Explicit, strict financial parsing: absent/null/boolean/non-finite money is
/// unavailable, never zero. Empty *valid* lists, in contrast, really sum to zero.
enum APIParsing {
    static func failure(_ field: String) -> AppError {
        AppError(kind: .parse, message: "Missing or invalid \(field)")
    }

    static func object(_ value: Any) throws -> [String: Any] {
        guard let result = value as? [String: Any] else { throw failure("JSON object") }
        return result
    }

    static func decode(_ data: Data, envelope: Bool) throws -> Any {
        let value: Any
        do { value = try JSONSerialization.jsonObject(with: data) }
        catch { throw failure("JSON response") }
        if let root = value as? [String: Any] {
            if root["error"] != nil && !(root["error"] is NSNull) {
                throw AppError(kind: .api, message: "API returned an error")
            }
            if let success = root["success"] {
                guard let flag = boolean(success) else { throw failure("success flag") }
                guard flag else { throw AppError(kind: .api, message: "API returned success=false") }
                guard let payload = root["data"], !(payload is NSNull) else { throw failure("data") }
                return envelope ? payload : value
            }
            if envelope, let payload = root["data"] { return payload }
        }
        return value
    }

    static func number(_ value: Any?) -> Double? {
        guard let value, !(value is NSNull) else { return nil }
        let result: Double?
        if let n = value as? NSNumber {
            guard CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
            result = n.doubleValue
        } else if let string = value as? String, !string.isEmpty,
                  string == string.trimmingCharacters(in: .whitespacesAndNewlines) {
            result = Double(string)
        } else { return nil }
        return result.flatMap { $0.isFinite ? $0 : nil }
    }

    static func integer(_ value: Any?) -> Int64? {
        if let n = value as? NSNumber {
            guard CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
            if let exact = Int64(n.stringValue) { return exact }
            return Int64(exactly: n.doubleValue)
        }
        if let s = value as? String { return Int64(s) }
        return nil
    }

    static func boolean(_ value: Any?) -> Bool? {
        guard let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
        return n.boolValue
    }

    static func money(_ object: [String: Any], _ key: String, nonnegative: Bool = false) throws -> Double {
        guard let result = number(object[key]), !nonnegative || result >= 0 else { throw failure(key) }
        return result
    }

    static func requiredInt(_ object: [String: Any], _ key: String) throws -> Int64 {
        guard let result = integer(object[key]) else { throw failure(key) }
        return result
    }

    static func optionalInt(_ object: [String: Any], _ key: String) throws -> Int64? {
        guard let value = object[key], !(value is NSNull) else { return nil }
        guard let result = integer(value) else { throw failure(key) }
        return result
    }

    static func text(_ object: [String: Any], _ key: String) -> String {
        object[key] as? String ?? ""
    }

    static func items(_ value: Any) throws -> [[String: Any]] {
        if let array = value as? [Any] { return try array.map(object) }
        let root = try object(value)
        for key in ["items", "records", "logs"] {
            if let array = root[key] as? [Any] { return try array.map(object) }
        }
        throw failure("list items")
    }

    static func pagination(_ value: Any, page: Int, count: Int, size: Int) throws -> (Int?, Bool) {
        guard page > 0, size > 0, count <= size, page <= Int.max / size else { throw failure("pagination") }
        let root = value as? [String: Any]
        var total: Int?
        if let raw = root?["total"], !(raw is NSNull) {
            guard let n = integer(raw), n >= 0, let converted = Int(exactly: n) else { throw failure("total") }
            total = converted
        }
        let offset = (page - 1) * size
        if let total {
            guard offset + count <= total || (count == 0 && offset >= total) else { throw failure("pagination total") }
            // A server that silently caps page size cannot be treated as complete.
            if count < size && offset + count < total { throw failure("incomplete page") }
            return (total, offset + count < total)
        }
        return (nil, count == size)
    }

    static func log(_ root: [String: Any]) throws -> LogEntry {
        let created = try requiredInt(root, "created_at")
        guard created > 0 else { throw failure("created_at") }
        let quota = try money(root, "quota", nonnegative: true)
        let prompt = try optionalInt(root, "prompt_tokens") ?? 0
        let completion = try optionalInt(root, "completion_tokens") ?? 0
        guard prompt >= 0, completion >= 0, prompt <= Int64.max - completion else { throw failure("token count") }
        let id: String
        if let numeric = integer(root["id"]) { id = String(numeric) }
        else if let string = root["id"] as? String, !string.isEmpty { id = string }
        else { throw failure("log id") }
        let type = try optionalInt(root, "type")
        let useTime = try optionalInt(root, "use_time")
        return LogEntry(id: id, type: type.flatMap(Int.init(exactly:)), createdAt: created,
                        modelName: text(root, "model_name"), tokenName: text(root, "token_name"),
                        promptTokens: prompt, completionTokens: completion, quota: quota,
                        useTime: useTime.flatMap(Int.init(exactly:)),
                        isStream: boolean(root["is_stream"]) ?? false,
                        username: text(root, "username"), content: text(root, "content"))
    }

    static func logPage(_ value: Any, page: Int, size: Int = 100) throws -> LogPage {
        let rows = try items(value).map(log)
        let (total, more) = try pagination(value, page: page, count: rows.count, size: size)
        return LogPage(rows: rows, total: total, hasMore: more)
    }

    static func aggregate(_ value: Any, start: Int64, end: Int64, qpu: Double) throws -> Double {
        guard qpu.isFinite, qpu > 0 else { throw failure("quota_per_unit") }
        let rows = try items(value)
        if let root = value as? [String: Any], let rawTotal = root["total"], !(rawTotal is NSNull) {
            guard let total = integer(rawTotal), total == Int64(rows.count) else {
                throw failure("incomplete aggregate response")
            }
        }
        var amount = 0.0
        let calendar = Calendar.current
        let firstDay = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(start)))
        let lastDay = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(end)))
        for row in rows {
            let quota = try money(row, "quota", nonnegative: true)
            let at = try requiredInt(row, "created_at")
            guard at > 0, at <= 253_402_300_799 else { throw failure("aggregate timestamp") }
            let day = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(at)))
            if day >= firstDay && day <= lastDay { amount += quota / qpu }
            guard amount.isFinite else { throw failure("aggregate amount") }
        }
        return amount
    }

    static func status(_ value: Any) throws -> StatusInfo {
        let root = try object(value)
        return StatusInfo(systemName: text(root, "system_name"), version: text(root, "version"),
                          startTime: integer(root["start_time"]), quotaPerUnit: number(root["quota_per_unit"]),
                          quotaDisplayType: root["quota_display_type"] as? String,
                          usdExchangeRate: number(root["usd_exchange_rate"]),
                          customCurrencySymbol: root["custom_currency_symbol"] as? String,
                          customCurrencyExchangeRate: number(root["custom_currency_exchange_rate"]))
    }

    static func token(_ root: [String: Any]) throws -> TokenInfo {
        let id = try requiredInt(root, "id")
        guard id > 0, let status = integer(root["status"]).flatMap(Int.init(exactly:)),
              let unlimited = boolean(root["unlimited_quota"]) else { throw failure("token metadata") }
        return TokenInfo(id: id, name: text(root, "name"), key: text(root, "key"), status: status,
                         usedQuota: try money(root, "used_quota", nonnegative: true),
                         remainQuota: try money(root, "remain_quota"), unlimitedQuota: unlimited,
                         expiredTime: try requiredInt(root, "expired_time"),
                         createdTime: try requiredInt(root, "created_time"), group: text(root, "group"),
                         modelLimitsEnabled: boolean(root["model_limits_enabled"]) ?? false,
                         modelLimits: text(root, "model_limits"), allowIps: text(root, "allow_ips"))
    }

    static func channel(_ root: [String: Any]) throws -> ChannelInfo {
        let id = try requiredInt(root, "id")
        guard id > 0, let status = integer(root["status"]).flatMap(Int.init(exactly:)),
              let type = integer(root["type"]).flatMap(Int.init(exactly:)) else { throw failure("channel metadata") }
        return ChannelInfo(id: id, name: text(root, "name"), status: status, type: type,
                           responseTime: try optionalInt(root, "response_time").flatMap(Int.init(exactly:)),
                           priority: try optionalInt(root, "priority"), weight: try optionalInt(root, "weight"))
    }
}
