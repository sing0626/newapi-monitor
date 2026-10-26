import Foundation
import CryptoKit

enum Quota {
    static let defaultQpu: Double = 500_000.0

    static func resolveQpu(instanceOverride: Double?, statusQpu: Double?) -> Double {
        if let v = instanceOverride, v.isFinite, v > 0 { return v }
        if let s = statusQpu, s.isFinite, s > 0 { return s }
        return defaultQpu
    }

    static func toUsd(quota: Double, qpu: Double) -> Double {
        let divisor = (qpu.isFinite && qpu > 0) ? qpu : defaultQpu
        return quota / divisor
    }

    static func billingUsageHundredthsToAmount(_ hundredths: Double) -> Double {
        hundredths / 100.0
    }
}

enum DisplayUnits: Equatable, Sendable {
    case unknown
    case usd
    case cny(usdExchangeRate: Double)
    case tokens
    case custom(symbol: String, usdExchangeRate: Double)

    var symbol: String? {
        switch self {
        case .unknown, .tokens: return nil
        case .usd: return "$"
        case .cny: return "¥"
        case .custom(let sym, _): return sym
        }
    }

    static func fromStatus(_ status: StatusInfo?) -> DisplayUnits {
        guard let status else { return .unknown }
        switch status.quotaDisplayType {
        case "USD":
            return .usd
        case "TOKENS":
            return .tokens
        case "CNY":
            if let rate = status.usdExchangeRate, rate.isFinite, rate > 0 {
                return .cny(usdExchangeRate: rate)
            }
            return .unknown
        case "CUSTOM":
            if let rate = status.customCurrencyExchangeRate, rate.isFinite, rate > 0 {
                let sym = (status.customCurrencySymbol?.isEmpty == false) ? status.customCurrencySymbol! : "¤"
                return .custom(symbol: sym, usdExchangeRate: rate)
            }
            return .unknown
        default:
            return .unknown
        }
    }

    func quotaAmount(quota: Double, qpu: Double) -> Double? {
        guard quota.isFinite, qpu.isFinite, qpu > 0 else { return nil }
        let usdVal = quota / qpu
        let result: Double
        switch self {
        case .unknown: return nil
        case .usd: result = usdVal
        case .cny(let rate): result = usdVal * rate
        case .tokens: result = quota
        case .custom(_, let rate): result = usdVal * rate
        }
        return result.isFinite ? result : nil
    }

    func billingUsd(_ value: Double) -> Double? {
        guard value.isFinite else { return nil }
        let result: Double
        switch self {
        case .unknown, .tokens: return nil
        case .usd, .custom: result = value
        case .cny(let rate): result = value / rate
        }
        return result.isFinite ? result : nil
    }
}

enum InstanceSanitizer {
    static func sanitizeBaseUrl(_ raw: String) -> String {
        var trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        return String(trimmed.prefix(300))
    }

    static func normalizeBaseUrlInput(_ raw: String) -> String {
        let url = sanitizeBaseUrl(raw)
        guard !url.isEmpty else { return "" }
        let lower = url.lowercased()
        if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") {
            return "https://\(url)"
        }
        return url
    }

    static func sanitize(_ instance: Instance?) -> Instance? {
        guard let o = instance else { return nil }
        let trimmedId = o.id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty else { return nil }
        let id = String(trimmedId.prefix(64))
        let trimmedName = String(o.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        let name = trimmedName.isEmpty ? "未命名實例" : trimmedName
        let baseUrl = sanitizeBaseUrl(o.baseUrl)
        let accessToken = String(o.accessToken.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300))
        let apiKey = String(o.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300))
        let qpu = (o.quotaPerUnit != nil && o.quotaPerUnit!.isFinite && o.quotaPerUnit! > 0) ? o.quotaPerUnit : nil
        return Instance(
            id: id,
            name: name,
            baseUrl: baseUrl,
            accessToken: accessToken,
            apiKey: apiKey,
            quotaPerUnit: qpu,
            authStyle: o.authStyle == .raw ? .raw : nil,
            authStyleKey: o.authStyleKey == .raw ? .raw : nil,
            createdAt: o.createdAt
        )
    }

    static func newInstanceSeed() -> String {
        let nowBase36 = String(Int64(Date().timeIntervalSince1970 * 1000), radix: 36)
        let chars = Array("0123456789abcdefghijklmnopqrstuvwxyz")
        let suffix = String((0..<5).map { _ in chars.randomElement()! })
        return "i\(nowBase36)\(suffix)"
    }

    static func sourceStamp(for instance: Instance) -> String {
        let qpuStr = instance.quotaPerUnit.map { String($0) } ?? ""
        let raw = "\(instance.baseUrl)\0\(instance.accessToken)\0\(instance.apiKey)\0\(qpuStr)"
        let digest = SHA256.hash(data: Data(raw.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

struct BurnForecast: Equatable, Sendable {
    let todayExtrapolatedUsd: Double?
    let baselineDailyAvgUsd: Double?
    let daysRemaining: Double?

    static func calculate(balanceUsd: Double?, todaySpendUsd: Double?, weekDays: [DailySpend], nowMs: Int64) -> BurnForecast {
        let todayRateVal = todaySpendUsd.flatMap { todayRate(todaySpendUsd: $0, nowMs: nowMs) }
        let pastDays = weekDays.dropLast().map(\.usd).filter { $0 > 0 && $0.isFinite }
        let baseline: Double? = pastDays.isEmpty ? nil : (pastDays.reduce(0, +) / Double(pastDays.count))
        let effectiveDaily = todayRateVal ?? baseline
        var daysLeft: Double? = nil
        if let bal = balanceUsd, bal.isFinite, bal >= 0, let rate = effectiveDaily, rate > 0.001 {
            daysLeft = bal / rate
        }
        return BurnForecast(todayExtrapolatedUsd: todayRateVal, baselineDailyAvgUsd: baseline, daysRemaining: daysLeft)
    }

    static func todayRate(todaySpendUsd: Double, nowMs: Int64) -> Double? {
        guard todaySpendUsd.isFinite, todaySpendUsd >= 0 else { return nil }
        let date = Date(timeIntervalSince1970: TimeInterval(nowMs) / 1000.0)
        let startOfDay = Calendar.current.startOfDay(for: date)
        let elapsedSec = date.timeIntervalSince(startOfDay)
        guard elapsedSec >= 15 * 60 else { return nil }
        guard let nextDay = Calendar.current.date(byAdding: .day, value: 1, to: startOfDay) else { return nil }
        let dayLength = nextDay.timeIntervalSince(startOfDay)
        let fraction = min(max(elapsedSec / dayLength, 0.01), 1.0)
        return todaySpendUsd / fraction
    }
}
