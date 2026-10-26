import Foundation

struct BackupInstanceDto: Codable, Equatable {
    var name: String = ""
    var baseUrl: String = ""
    var accessToken: String = ""
    var apiKey: String = ""
    var authStyle: String? = nil
    var authStyleKey: String? = nil
    var quotaPerUnit: Double? = nil
    var monthlyBudgetUsd: Double? = nil
    var pinned: Bool? = nil
    var balanceIncluded: Bool? = nil
    var homeOrder: Int? = nil
    var alertRule: InstanceAlertRule? = nil
}

struct BackupAppearanceDto: Codable, Equatable {
    var language: String? = nil
    var mode: String? = nil
    var accent: String? = nil
}

struct BackupAlertSettingsDto: Codable, Equatable {
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

struct BackupFileDto: Codable, Equatable {
    var app: String = "newapi-monitor"
    var version: Int = BackupManager.version
    var exportedAt: Int64 = 0
    var instances: [BackupInstanceDto] = []
    var appearance: BackupAppearanceDto? = nil
    var alertSettings: BackupAlertSettingsDto? = nil
    var budgets: [String: Double] = [:]
    var compactHome: Bool? = nil
    var hideMoney: Bool? = nil
    var favoriteModels: [String] = []
    var probeSuites: [ProbeSuite] = []
}

// Swift's synthesized Decodable ignores stored-property defaults. Decode defaults
// only for absent legacy fields; a present field of the wrong type (including
// null for a nonoptional value) remains an error, matching kotlinx.serialization.
private extension KeyedDecodingContainer {
    func decode<T: Decodable>(_ type: T.Type, forKey key: Key, default defaultValue: T) throws -> T {
        contains(key) ? try decode(type, forKey: key) : defaultValue
    }
}

extension BackupInstanceDto {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name, default: "")
        baseUrl = try c.decode(String.self, forKey: .baseUrl, default: "")
        accessToken = try c.decode(String.self, forKey: .accessToken, default: "")
        apiKey = try c.decode(String.self, forKey: .apiKey, default: "")
        authStyle = try c.decodeIfPresent(String.self, forKey: .authStyle)
        authStyleKey = try c.decodeIfPresent(String.self, forKey: .authStyleKey)
        quotaPerUnit = try c.decodeIfPresent(Double.self, forKey: .quotaPerUnit)
        monthlyBudgetUsd = try c.decodeIfPresent(Double.self, forKey: .monthlyBudgetUsd)
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned)
        balanceIncluded = try c.decodeIfPresent(Bool.self, forKey: .balanceIncluded)
        homeOrder = try c.decodeIfPresent(Int.self, forKey: .homeOrder)
        alertRule = try c.decodeIfPresent(InstanceAlertRule.self, forKey: .alertRule)
    }
}

extension BackupAlertSettingsDto {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.decode(Bool.self, forKey: .enabled, default: false)
        balanceBelowUsd = try c.decodeIfPresent(Double.self, forKey: .balanceBelowUsd)
        dailySpendAboveUsd = try c.decodeIfPresent(Double.self, forKey: .dailySpendAboveUsd)
        spikeMultiplier = try c.decode(Double.self, forKey: .spikeMultiplier, default: 3.0)
        cooldownMinutes = try c.decode(Int.self, forKey: .cooldownMinutes, default: 60)
        notifyTokenBanned = try c.decode(Bool.self, forKey: .notifyTokenBanned, default: true)
        monthlyBudgetUsd = try c.decodeIfPresent(Double.self, forKey: .monthlyBudgetUsd)
        notifyErrorRate = try c.decode(Bool.self, forKey: .notifyErrorRate, default: false)
        notifyTokenExpiry = try c.decode(Bool.self, forKey: .notifyTokenExpiry, default: false)
        persistentStatus = try c.decode(Bool.self, forKey: .persistentStatus, default: false)
    }
}

extension BackupFileDto {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Require the identity, version and collection even for legacy imports.
        app = try c.decode(String.self, forKey: .app)
        version = try c.decode(Int.self, forKey: .version)
        instances = try c.decode([BackupInstanceDto].self, forKey: .instances)
        exportedAt = try c.decode(Int64.self, forKey: .exportedAt, default: 0)
        appearance = try c.decodeIfPresent(BackupAppearanceDto.self, forKey: .appearance)
        alertSettings = try c.decodeIfPresent(BackupAlertSettingsDto.self, forKey: .alertSettings)
        budgets = try c.decode([String: Double].self, forKey: .budgets, default: [:])
        compactHome = try c.decodeIfPresent(Bool.self, forKey: .compactHome)
        hideMoney = try c.decodeIfPresent(Bool.self, forKey: .hideMoney)
        favoriteModels = try c.decode([String].self, forKey: .favoriteModels, default: [])
        probeSuites = try c.decode([ProbeSuite].self, forKey: .probeSuites, default: [])
    }
}

enum BackupManager {
    static let version = 1

    struct InstanceBudget: Equatable {
        var name: String
        var baseUrl: String
        var usd: Double
    }

    struct InstanceOption: Equatable {
        var name: String
        var baseUrl: String
        var pinned: Bool?
        var balanceIncluded: Bool?
        var homeOrder: Int?
        var alertRule: InstanceAlertRule?
    }

    struct ParsedBackup: Equatable {
        var version: Int
        var exportedAt: Int64
        var instances: [Instance]
        // A missing field means leave that existing appearance setting unchanged.
        var appearance: BackupAppearanceDto?
        var alertSettings: AlertSettings?
        var budgets: [String: Double]
        var instanceBudgets: [InstanceBudget]
        var homeOptions: MonitorPreferences?
        var instanceOptions: [InstanceOption]
    }

    static func toJson(
        instances: [Instance],
        appearance: Appearance?,
        alertSettings: AlertSettings?,
        exportedAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        includeCredentials: Bool = false,
        budgetsByBaseUrl: [String: Double] = [:],
        budgetsById: [String: Double] = [:],
        homeOptions: MonitorPreferences? = nil,
        alertRules: [String: InstanceAlertRule] = [:]
    ) throws -> String {
        let dtoInstances: [BackupInstanceDto] = instances.map { inst in
            let safeInst: Instance
            if includeCredentials {
                safeInst = inst
            } else {
                var stripped = inst
                stripped.accessToken = ""
                stripped.apiKey = ""
                stripped.authStyle = nil
                stripped.authStyleKey = nil
                safeInst = stripped
            }
            let budget = budgetsById[inst.id].flatMap { ($0.isFinite && $0 > 0) ? $0 : nil }
            let pinned = homeOptions.map { $0.pinned.contains(inst.id) }
            let balanceIncluded = homeOptions.map { !$0.excluded.contains(inst.id) }
            let homeOrder = homeOptions?.order.firstIndex(of: inst.id)
            let rule = alertRules[inst.id].flatMap { (try? $0.validate()) != nil ? $0 : nil }
            return BackupInstanceDto(
                name: safeInst.name,
                baseUrl: safeInst.baseUrl,
                accessToken: safeInst.accessToken,
                apiKey: safeInst.apiKey,
                authStyle: safeInst.authStyle == .raw ? "raw" : nil,
                authStyleKey: safeInst.authStyleKey == .raw ? "raw" : nil,
                quotaPerUnit: safeInst.quotaPerUnit,
                monthlyBudgetUsd: budget,
                pinned: pinned,
                balanceIncluded: balanceIncluded,
                homeOrder: homeOrder,
                alertRule: rule
            )
        }

        let favs = Array((homeOptions?.favorites ?? []).filter { $0.count <= 150 }.prefix(100))
        let suites = Array((homeOptions?.suites ?? []).prefix(20))
        let cleanBudgets = budgetsByBaseUrl.filter { $0.value.isFinite && $0.value > 0 }

        let fileDto = BackupFileDto(
            app: "newapi-monitor",
            version: version,
            exportedAt: exportedAtMs,
            instances: dtoInstances,
            appearance: appearance.map { BackupAppearanceDto(language: $0.language, mode: $0.mode, accent: $0.accent) },
            alertSettings: alertSettings.map {
                BackupAlertSettingsDto(
                    enabled: $0.enabled,
                    balanceBelowUsd: $0.balanceBelowUsd,
                    dailySpendAboveUsd: $0.dailySpendAboveUsd,
                    spikeMultiplier: $0.spikeMultiplier,
                    cooldownMinutes: $0.cooldownMinutes,
                    notifyTokenBanned: $0.notifyTokenBanned,
                    monthlyBudgetUsd: $0.monthlyBudgetUsd,
                    notifyErrorRate: $0.notifyErrorRate,
                    notifyTokenExpiry: $0.notifyTokenExpiry,
                    persistentStatus: $0.persistentStatus
                )
            },
            budgets: cleanBudgets,
            compactHome: homeOptions?.compact,
            hideMoney: homeOptions?.hideMoney,
            favoriteModels: favs,
            probeSuites: suites
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(fileDto)
        return String(decoding: data, as: UTF8.self)
    }

    static func parse(_ text: String) -> ParsedBackup? {
        guard text.utf8.count <= 8 * 1024 * 1024 else { return nil }
        var cleaned = text
        if cleaned.hasPrefix("\u{FEFF}") {
            cleaned.removeFirst()
        }
        cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = cleaned.data(using: .utf8),
              let rawObj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              rawObj["instances"] is [Any],
              let f = try? JSONDecoder().decode(BackupFileDto.self, from: data),
              f.version == version,
              f.app == "newapi-monitor" else {
            return nil
        }

        let parsedInstances = f.instances.compactMap { draftOf($0) }
        let instBudgets: [InstanceBudget] = f.instances.compactMap { dto in
            guard let budget = dto.monthlyBudgetUsd, budget.isFinite, budget > 0,
                  let draft = draftOf(dto) else { return nil }
            return InstanceBudget(name: draft.name, baseUrl: draft.baseUrl, usd: budget)
        }
        let instOptions: [InstanceOption] = f.instances.compactMap { dto in
            guard let draft = draftOf(dto) else { return nil }
            let validOrder = dto.homeOrder.flatMap { (0...10_000).contains($0) ? $0 : nil }
            let validRule = dto.alertRule.flatMap { (try? $0.validate()) != nil ? $0 : nil }
            return InstanceOption(
                name: draft.name,
                baseUrl: draft.baseUrl,
                pinned: dto.pinned,
                balanceIncluded: dto.balanceIncluded,
                homeOrder: validOrder,
                alertRule: validRule
            )
        }

        let homeOpts: MonitorPreferences?
        if f.compactHome != nil || f.hideMoney != nil || !f.favoriteModels.isEmpty || !f.probeSuites.isEmpty {
            let favs = Set(f.favoriteModels.filter { $0.count <= 150 }.prefix(100))
            let validSuites = Array(f.probeSuites.filter {
                (1...40).contains($0.name.count) && (1...3).contains($0.models.count) && $0.models.allSatisfy { (1...150).contains($0.count) }
            }.prefix(20))
            homeOpts = MonitorPreferences(
                compact: f.compactHome ?? false,
                hideMoney: f.hideMoney ?? false,
                favorites: favs,
                suites: validSuites
            )
        } else {
            homeOpts = nil
        }

        let parsedAlert: AlertSettings? = f.alertSettings.map {
            AlertSettings(
                enabled: $0.enabled,
                balanceBelowUsd: $0.balanceBelowUsd,
                dailySpendAboveUsd: $0.dailySpendAboveUsd,
                spikeMultiplier: $0.spikeMultiplier,
                cooldownMinutes: $0.cooldownMinutes,
                notifyTokenBanned: $0.notifyTokenBanned,
                monthlyBudgetUsd: $0.monthlyBudgetUsd,
                notifyErrorRate: $0.notifyErrorRate,
                notifyTokenExpiry: $0.notifyTokenExpiry,
                persistentStatus: $0.persistentStatus
            )
        }

        return ParsedBackup(
            version: f.version,
            exportedAt: f.exportedAt,
            instances: parsedInstances,
            appearance: f.appearance,
            alertSettings: parsedAlert,
            budgets: f.budgets.filter { $0.value.isFinite && $0.value > 0 },
            instanceBudgets: instBudgets,
            homeOptions: homeOpts,
            instanceOptions: instOptions
        )
    }

    private static func draftOf(_ dto: BackupInstanceDto) -> Instance? {
        let baseUrl = InstanceSanitizer.sanitizeBaseUrl(dto.baseUrl)
        guard !baseUrl.isEmpty else { return nil }
        let trimmedName = String(dto.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        return Instance(
            id: "",
            name: trimmedName.isEmpty ? "未命名實例" : trimmedName,
            baseUrl: baseUrl,
            accessToken: String(dto.accessToken.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300)),
            apiKey: String(dto.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300)),
            quotaPerUnit: dto.quotaPerUnit.flatMap { ($0.isFinite && $0 > 0) ? $0 : nil },
            authStyle: dto.authStyle == "raw" ? .raw : nil,
            authStyleKey: dto.authStyleKey == "raw" ? .raw : nil,
            createdAt: 0
        )
    }
}
