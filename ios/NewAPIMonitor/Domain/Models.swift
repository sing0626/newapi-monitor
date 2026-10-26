import Foundation

enum AuthStyle: String, Codable, CaseIterable, Sendable {
    case bearer = "bearer"
    case raw = "raw"
}

enum ErrorKind: String, Codable, Sendable {
    case auth
    case network
    case http
    case parse
    case api
}

struct AppError: LocalizedError, Codable, Equatable, Sendable {
    var errorDescription: String? { message }
    let kind: ErrorKind
    let message: String
    let status: Int?

    init(kind: ErrorKind, message: String, status: Int? = nil) {
        self.kind = kind
        self.message = message
        self.status = status
    }
}

struct Instance: Identifiable, Codable, Equatable, Sendable {
    var id: String
    var name: String
    var baseUrl: String
    var accessToken: String
    var apiKey: String
    var quotaPerUnit: Double?
    var authStyle: AuthStyle?
    var authStyleKey: AuthStyle?
    var createdAt: Int64

    var hasAccess: Bool { !accessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var hasKey: Bool { !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

struct AppSettings: Codable, Equatable, Sendable {
    var activeId: String? = nil
    var intervalSec: Int = 15
    var demo: Bool = false
}

struct Appearance: Codable, Equatable, Sendable {
    var language: String = "yue" // "yue" | "zh-TW" | "zh-CN" | "en"
    var mode: String = "dark"    // "dark" | "oled" | "light"
    var accent: String = "purple" // "purple" | "blue" | "green" | "orange"
}

struct ProbeSuite: Codable, Equatable, Identifiable, Sendable {
    var id: String { name }
    var name: String
    var models: [String]
}

struct InstanceAlertRule: Codable, Equatable, Sendable {
    var enabled: Bool = false
    var balanceBelow: Double? = nil
    var dailyAbove: Double? = nil
    var quietStart: String = "23:00"
    var quietEnd: String = "07:00"
    var cooldownMinutes: Int = 60

    func validate() throws {
        if let b = balanceBelow, (!b.isFinite || b < 0) {
            throw AppError(kind: .parse, message: "Invalid balanceBelow")
        }
        if let d = dailyAbove, (!d.isFinite || d <= 0) {
            throw AppError(kind: .parse, message: "Invalid dailyAbove")
        }
        for time in [quietStart, quietEnd] where !time.isEmpty {
            let parts = time.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 2, parts.allSatisfy({ $0.count == 2 }),
                  let hour = Int(parts[0]), let minute = Int(parts[1]),
                  (0...23).contains(hour), (0...59).contains(minute) else {
                throw AppError(kind: .parse, message: "靜音時間必須為 HH:mm")
            }
        }
        guard quietStart.isEmpty == quietEnd.isEmpty else {
            throw AppError(kind: .parse, message: "請同時填寫或清空兩個靜音時間")
        }
        guard (1...1440).contains(cooldownMinutes) else {
            throw AppError(kind: .parse, message: "Invalid cooldownMinutes")
        }
    }
}

struct MonitorPreferences: Codable, Equatable, Sendable {
    var compact: Bool = false
    var hideMoney: Bool = false
    var pinned: Set<String> = []
    var order: [String] = []
    var excluded: Set<String> = []
    var favorites: Set<String> = []
    var suites: [ProbeSuite] = []
}

struct AlertSettings: Codable, Equatable, Sendable {
    var enabled: Bool = false
    var balanceBelowUsd: Double? = nil
    var dailySpendAboveUsd: Double? = nil
    var spikeMultiplier: Double = 3.0
    var cooldownMinutes: Int = 60
    var notifyTokenBanned: Bool = true
    var monthlyBudgetUsd: Double? = nil
    var notifyErrorRate: Bool = false
    var notifyTokenExpiry: Bool = false
    var persistentStatus: Bool = false
}

enum AlertType: String, Codable, CaseIterable, Sendable {
    case balanceLow = "BALANCE_LOW"
    case dailySpend = "DAILY_SPEND"
    case spike = "SPIKE"
    case tokenBanned = "TOKEN_BANNED"
    case monthlyBudget90 = "MONTHLY_BUDGET_90"
    case monthlyBudget100 = "MONTHLY_BUDGET_100"
    case errorRate = "ERROR_RATE"
    case tokenExpiry3d = "TOKEN_EXPIRY_3D"
    case tokenExpiry1d = "TOKEN_EXPIRY_1D"
    case tokenExpired = "TOKEN_EXPIRED"
}

struct AlertRecord: Identifiable, Codable, Equatable, Sendable {
    var id: String
    var sourceId: String
    var title: String
    var body: String
    var type: String
    var time: Int64
    var read: Bool
}

struct StatusInfo: Codable, Equatable, Sendable {
    var systemName: String
    var version: String
    var startTime: Int64?
    var quotaPerUnit: Double?
    var quotaDisplayType: String?
    var usdExchangeRate: Double?
    var customCurrencySymbol: String?
    var customCurrencyExchangeRate: Double?
}

struct UserInfo: Codable, Equatable, Sendable {
    var id: Int64
    var username: String
    var displayName: String
    var group: String
    var quota: Double
    var usedQuota: Double
    var requestCount: Int64
}

struct BillingInfo: Codable, Equatable, Sendable {
    var hardLimitUsd: Double
    var totalUsageUsd: Double
    var remainingUsd: Double
}

struct LogEntry: Identifiable, Codable, Equatable, Sendable {
    var id: String
    var type: Int?
    var createdAt: Int64
    var modelName: String
    var tokenName: String
    var promptTokens: Int64
    var completionTokens: Int64
    var quota: Double
    var useTime: Int?
    var isStream: Bool
    var username: String
    var content: String
}

struct DailySpend: Identifiable, Codable, Equatable, Sendable {
    var id: String { date }
    var date: String // YYYY-MM-DD
    var usd: Double
    var count: Int
    var tokens: Int64
}

struct ModelStat: Identifiable, Codable, Equatable, Sendable {
    var id: String { model }
    var model: String
    var quota: Double
    var usd: Double
    var count: Int
    var tokens: Int64
    var share: Double
}

struct MonitorSnapshot: Identifiable, Codable, Equatable, Sendable {
    var id: String
    var balanceUsd: Double?
    var todayUsd: Double?
    var monthUsd: Double?
    var balanceAt: Int64
    var spendAt: Int64
    var attemptedAt: Int64
    var day: String
    var month: String
    var balanceFailure: String?
    var spendFailure: String?
    var sourceStamp: String

    func balanceFresh(nowMs: Int64) -> Bool {
        guard balanceUsd != nil else { return false }
        return balanceUsd?.isFinite == true && balanceAt > 0 && balanceAt == attemptedAt
            && nowMs >= balanceAt && (nowMs - balanceAt) < 90_000
    }

    func spendFresh(nowMs: Int64) -> Bool {
        guard todayUsd != nil || monthUsd != nil else { return false }
        let date = Date(timeIntervalSince1970: Double(nowMs) / 1000)
        return spendAt > 0 && spendAt == attemptedAt && nowMs >= spendAt
            && (nowMs - spendAt) < 90_000 && day == Fmt.dayString(from: date)
            && month == Fmt.monthString(from: date)
    }
}

struct DashboardSnapshot: Equatable, Sendable {
    var ok: Bool
    var demo: Bool
    var instanceId: String
    var name: String
    var keyOnly: Bool
    var noInstance: Bool
    var noTokens: Bool
    var fetchedAtMs: Int64
    var qpu: Double
    var status: StatusInfo?
    var user: UserInfo?
    var billing: BillingInfo?
    var balanceUsd: Double?
    var usedTotalUsd: Double?
    var todayUsd: Double?
    var todayTruncated: Bool
    var todayCount: Int
    var monthUsd: Double?
    var monthTruncated: Bool
    var weekDays: [DailySpend]
    var weekTotalUsd: Double?
    var recentLogs: [LogEntry]
    var modelStats: [ModelStat]
    var models: [String]
    var errorCount24h: Int
    var errorRate: Double
    var hourlySpend: [Double] // 24 hours
    var notice: String?
    var errors: [String: String]
}

struct TokenInfo: Identifiable, Codable, Equatable, Sendable {
    var id: Int64
    var name: String
    var key: String
    var status: Int // 1 enabled, 2 disabled, 3 banned
    var usedQuota: Double
    var remainQuota: Double
    var unlimitedQuota: Bool
    var expiredTime: Int64 // -1 never
    var createdTime: Int64
    var group: String
    var modelLimitsEnabled: Bool
    var modelLimits: String
    var allowIps: String
}

struct ChannelInfo: Identifiable, Codable, Equatable, Sendable {
    var id: Int64
    var name: String
    var status: Int // 1 enabled, 2 disabled, 3 banned
    var type: Int
    var responseTime: Int?
    var priority: Int64?
    var weight: Int64?
}

struct StreamProbeResult: Identifiable, Codable, Equatable, Sendable {
    var id: String { "\(sourceId):\(model):\(timestamp)" }
    var sourceId: String
    var sourceName: String
    var model: String
    var ok: Bool
    var firstTokenMs: Int64?
    var totalMs: Int64
    var completionTokens: Int64?
    var tokensPerSecond: Double?
    var failure: String?
    var timestamp: Int64
}

struct BalancePoint: Identifiable, Codable, Equatable, Sendable {
    var id: Int64 { timestampMs }
    var timestampMs: Int64
    var balanceUsd: Double
}

struct TopupRecord: Identifiable, Codable, Equatable, Sendable {
    var id: Int64
    var amount: Double
    var createTime: Int64
    var status: String
    var payType: String
}

struct SubscriptionInfo: Codable, Equatable, Sendable {
    var planName: String
    var expireTime: Int64
    var status: String
}

struct ModelPriceInfo: Identifiable, Codable, Equatable, Sendable {
    var id: String { model }
    var model: String
    var quotaType: Int // 0 = ratio, 1 = per-call fixed
    var modelRatio: Double
    var completionRatio: Double
    var fixedPriceUsd: Double?
    var groups: [String]
}
